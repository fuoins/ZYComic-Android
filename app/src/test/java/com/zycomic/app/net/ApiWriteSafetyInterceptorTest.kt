package com.zycomic.app.net

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ApiWriteSafetyInterceptorTest {
    @Test
    fun `POST redirects are never followed and surface uncertain outcome`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            withServer(code, mapOf("Location" to "/again")) { url, count ->
                val error = runCatching { safeClient().newCall(post(url)).execute().close() }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("可能已成功，请刷新确认"))
                assertEquals(1, count.get(), "HTTP $code must not replay the POST")
            }
        }
    }

    @Test
    fun `GET and HEAD redirects remain followable`() {
        for (method in listOf("GET", "HEAD")) {
            withServer(302, mapOf("Location" to "/again")) { url, count ->
                val request = Request.Builder().url(url).method(method, null).build()
                safeClient().newCall(request).execute().use { assertEquals(200, it.code) }
                assertEquals(2, count.get())
            }
        }
    }

    @Test
    fun `408 421 and server errors never replay writes and report an ambiguous result`() {
        for (code in listOf(408, 421, 500, 502, 503, 504)) {
            withServer(code, mapOf("Retry-After" to "0")) { url, count ->
                val error = runCatching { safeClient().newCall(post(url)).execute().close() }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("可能已成功，请刷新确认"))
                assertEquals(1, count.get())
            }
        }
    }

    @Test
    fun `marked mutating GET is not followed or retried`() {
        for ((code, headers) in listOf(301 to mapOf("Location" to "/again"), 503 to mapOf("Retry-After" to "0"))) {
            withServer(code, headers) { url, count ->
                val request = Request.Builder().url(url).get().header("X-ZYComic-Unsafe-Write", "true").build()
                val error = runCatching { safeClient().newCall(request).execute().close() }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue(error?.message.orEmpty().contains("可能已成功，请刷新确认"))
                assertEquals(1, count.get())
            }
        }
    }

    @Test
    fun `write transport failure after server receives body sends only once`() {
        rawDisconnect(false)
    }

    @Test
    fun `write response body disconnect reports possible success without replay`() {
        rawDisconnect(true)
    }

    private fun rawDisconnect(partialResponse: Boolean) {
        ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 500
            val count = AtomicInteger()
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    count.incrementAndGet()
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    if (partialResponse) {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nabc".toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
                try {
                    server.accept().use { count.incrementAndGet() }
                } catch (_: SocketTimeoutException) {
                    // No second connection is the expected result.
                }
            }
            val error = runCatching {
                safeClient().newCall(post("http://127.0.0.1:${server.localPort}/write")).execute().use { it.body!!.string() }
            }.exceptionOrNull()
            worker.join(3_000)
            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("可能已成功，请刷新确认"))
            assertEquals(1, count.get())
        }
    }

    private fun withServer(code: Int, headers: Map<String, String>, block: (String, AtomicInteger) -> Unit) {
        val count = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val first = count.incrementAndGet() == 1
                val response = MockResponse.Builder().code(if (first) code else 200)
                if (first) headers.forEach { (name, value) -> response.addHeader(name, value) }
                return response.build()
            }
        }
        server.start()
        try {
            block(server.url("/write").toString(), count)
        } finally {
            server.close()
        }
    }

    private fun post(url: String) = Request.Builder().url(url).post("body".toRequestBody()).build()

    private fun safeClient() = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .callTimeout(3, TimeUnit.SECONDS)
        .addInterceptor(ApiWriteSafetyInterceptor { "请求结果不确定，可能已成功，请刷新确认" })
        .addNetworkInterceptor(UnsafeWriteRedirectNetworkInterceptor())
        .build()
}
