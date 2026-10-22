package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real interceptors, with no network, Android runtime, or application build. */
class ApiTransportObservationTest {
    private val endpoint = DomainIp("api.test", "198.51.100.2")

    @BeforeEach
    fun reset() {
        RuntimeNetworkGate.online = true
        RuntimeNetworkGate.revision = 1L
        JmRouteManager.runtimeConfigRevision = 1L
        RouteManager.configRevision = 1L
        RouteManager.failures = 0
        RouteManager.responses = 0
    }

    @Test
    fun `JM partial body is a transport failure without premature response success`() {
        val failures = AtomicInteger()
        val responses = AtomicInteger()
        val client = jmClient(failures, responses, responder = { response(it, brokenBody()) })
        repeat(3) {
            client.newCall(request()).execute().use { result ->
                assertEquals(0, responses.get())
                assertThrows(SocketTimeoutException::class.java) { result.body!!.string() }
            }
        }
        assertEquals(3, failures.get())
        assertEquals(0, responses.get())
    }

    @Test
    fun `JM complete body breaks the streak only after consumption`() {
        val failures = AtomicInteger()
        val responses = AtomicInteger()
        val client = jmClient(failures, responses, responder = { response(it, "{}".toResponseBody()) })
        client.newCall(request()).execute().use { result ->
            assertEquals(0, responses.get())
            assertEquals("{}", result.body!!.string())
        }
        assertEquals(1, responses.get())
        assertEquals(0, failures.get())
    }

    @Test
    fun `JM rate limit reports Retry-After without transport failure`() {
        val failures = AtomicInteger()
        val responses = AtomicInteger()
        var observed: Pair<Int, String?>? = null
        val client = jmClient(failures, responses, { code, retry -> observed = code to retry }) {
            response(it, "limited".toResponseBody(), 429).newBuilder().header("Retry-After", "120").build()
        }
        client.newCall(request()).execute().close()
        assertEquals(429 to "120", observed)
        assertEquals(0, failures.get())
    }

    @Test
    fun `JM stale network or config and cancelled calls cannot report delayed body failures`() {
        for (invalidate in listOf<() -> Unit>(
            { RuntimeNetworkGate.revision++ },
            { JmRouteManager.runtimeConfigRevision++ },
            { RuntimeNetworkGate.online = false },
        )) {
            reset()
            val failures = AtomicInteger()
            val client = jmClient(failures, AtomicInteger(), responder = { response(it, brokenBody()) })
            client.newCall(request()).execute().use { result ->
                invalidate()
                assertThrows(SocketTimeoutException::class.java) { result.body!!.string() }
            }
            assertEquals(0, failures.get())
        }
        reset()
        val failures = AtomicInteger()
        val client = jmClient(failures, AtomicInteger(), responder = { response(it, brokenBody()) })
        val call = client.newCall(request())
        call.execute().use { result ->
            call.cancel()
            assertThrows(SocketTimeoutException::class.java) { result.body!!.string() }
        }
        assertEquals(0, failures.get())
    }

    @Test
    fun `JM write body failures never initiate route recovery`() {
        val failures = AtomicInteger()
        val client = jmClient(failures, AtomicInteger(), responder = { response(it, brokenBody()) })
        client.newCall(request().newBuilder().post("{}".toRequestBody()).build())
            .execute().use { result ->
                assertThrows(SocketTimeoutException::class.java) { result.body!!.string() }
            }
        assertEquals(0, failures.get())
    }

    @Test
    fun `Manwa body truncation is observed without resetting its failure streak`() {
        val client = OkHttpClient.Builder().addInterceptor(LineFailoverInterceptor())
            .addInterceptor { response(it, brokenBody()) }.build()
        repeat(3) {
            client.newCall(request()).execute().use { result ->
                assertEquals(0, RouteManager.responses)
                assertThrows(SocketTimeoutException::class.java) { result.body!!.string() }
            }
        }
        assertEquals(3, RouteManager.failures)
        assertEquals(0, RouteManager.responses)
    }

    @Test
    fun `Manwa HTTP policy errors are responses not route failures`() {
        for (code in listOf(401, 403, 404, 429, 500, 503)) {
            reset()
            val client = OkHttpClient.Builder().addInterceptor(LineFailoverInterceptor())
                .addInterceptor { response(it, "error".toResponseBody(), code) }.build()
            client.newCall(request()).execute().use { it.body!!.string() }
            assertEquals(0, RouteManager.failures, "HTTP $code")
            assertEquals(1, RouteManager.responses, "HTTP $code")
        }
    }

    private fun request() = Request.Builder().url("https://api.test/resource").build()

    private fun jmClient(
        failures: AtomicInteger,
        responses: AtomicInteger,
        http: (Int, String?) -> Unit = { _, _ -> },
        responder: (Interceptor.Chain) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            JmEndpointTransportInterceptor(
                prepareApiRoute = {}, prepareImageRoute = {},
                apiEndpoint = { endpoint }, imageEndpoint = { null },
                apiFailure = { _, _ -> failures.incrementAndGet() }, imageFailure = { _, _ -> },
                apiHasMeasurements = { true }, imageHasMeasurements = { true },
                apiSuccess = {}, imageSuccess = {},
                apiResponse = { responses.incrementAndGet() },
                apiHttpResponse = { _, code, retry -> http(code, retry) },
            ),
        )
        .addInterceptor(responder)
        .build()

    private fun response(chain: Interceptor.Chain, body: ResponseBody, code: Int = 200): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build()

    private fun brokenBody(): ResponseBody = object : ResponseBody() {
        private var emitted = false
        private val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!emitted) {
                    emitted = true
                    sink.writeUtf8("{")
                    return 1L
                }
                throw SocketTimeoutException("partial transport read")
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = input
    }
}
