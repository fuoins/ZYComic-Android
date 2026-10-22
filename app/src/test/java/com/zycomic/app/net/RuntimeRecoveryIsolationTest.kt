package com.zycomic.app.net

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class RuntimeRecoveryIsolationTest {
    @Test
    fun `validated internet is required before runtime recovery`() {
        assertFalse(RuntimeNetworkPolicy.mayRecover(internet = false, validated = false))
        assertFalse(RuntimeNetworkPolicy.mayRecover(internet = true, validated = false))
        assertTrue(RuntimeNetworkPolicy.mayRecover(internet = true, validated = true))
    }

    @Test
    fun `status auth and decode failures are not transport signals`() {
        assertEquals(RuntimeRecoveryPolicy.FailureKind.RATE_LIMIT, RuntimeRecoveryPolicy.classifyFailure(httpCode = 429))
        assertEquals(RuntimeRecoveryPolicy.FailureKind.AUTH, RuntimeRecoveryPolicy.classifyFailure(httpCode = 401))
        assertEquals(RuntimeRecoveryPolicy.FailureKind.AUTH, RuntimeRecoveryPolicy.classifyFailure(httpCode = 403))
        assertEquals(RuntimeRecoveryPolicy.FailureKind.DECODE_OR_CACHE, RuntimeRecoveryPolicy.classifyFailure(IOException("bad image")))
        assertFalse(RuntimeRecoveryPolicy.isTransportFailure(IOException("HTTP 429")))
        assertFalse(RuntimeRecoveryPolicy.isTransportFailure(IllegalArgumentException("decode")))
        assertTrue(RuntimeRecoveryPolicy.isTransportFailure(ConnectException("offline")))
        assertTrue(RuntimeRecoveryPolicy.isTransportFailure(SocketTimeoutException("timeout")))
    }

    @Test
    fun `cover threshold requires three failures and a strict majority`() {
        val window = RuntimeRecoveryPolicy.Window()
        fun record(resource: String, failed: Boolean, at: Long) = window.record(
            RuntimeRecoveryPolicy.Sample(
                RuntimeRecoveryPolicy.Platform.MANWA,
                "img.example",
                resource,
                RuntimeRecoveryPolicy.SampleKind.COVER,
                failed,
                at,
            ),
        )

        assertFalse(record("a", true, 0))
        assertFalse(record("b", true, 1))
        assertTrue(record("c", true, 2))

        window.clear()
        assertFalse(record("a", true, 10))
        assertFalse(record("b", true, 11))
        assertFalse(record("c", false, 12))
        assertFalse(record("d", false, 13))
    }

    @Test
    fun `one local cover failure cannot trigger global route recovery`() {
        val window = RuntimeRecoveryPolicy.Window()
        repeat(8) { attempt ->
            assertFalse(
                window.record(
                    RuntimeRecoveryPolicy.Sample(
                        RuntimeRecoveryPolicy.Platform.JM,
                        "jm-image.test",
                        "local://cover/42",
                        RuntimeRecoveryPolicy.SampleKind.COVER,
                        failed = true,
                        atMs = attempt.toLong(),
                    ),
                ),
            )
        }
    }

    @Test
    fun `platform counters and sources stay isolated and only latest retries are kept`() {
        val window = RuntimeRecoveryPolicy.Window()
        fun sample(platform: RuntimeRecoveryPolicy.Platform, source: String, resource: String, at: Long) =
            RuntimeRecoveryPolicy.Sample(
                platform,
                source,
                resource,
                RuntimeRecoveryPolicy.SampleKind.VISIBLE_READER_PAGE,
                true,
                at,
            )

        assertFalse(window.record(sample(RuntimeRecoveryPolicy.Platform.MANWA, "same", "one", 1)))
        assertFalse(window.record(sample(RuntimeRecoveryPolicy.Platform.JM, "same", "two", 2)))
        assertTrue(window.record(sample(RuntimeRecoveryPolicy.Platform.MANWA, "same", "three", 3)))
        assertFalse(window.record(sample(RuntimeRecoveryPolicy.Platform.JM, "other", "four", 4)))
        assertTrue(window.record(sample(RuntimeRecoveryPolicy.Platform.JM, "same", "five", 5)))

        val retries = RuntimeRecoveryPolicy.RetryCallbacks()
        val calls = mutableListOf<String>()
        retries.record(RuntimeRecoveryPolicy.Platform.MANWA, "same", "one", 1) { calls += "old" }
        retries.record(RuntimeRecoveryPolicy.Platform.MANWA, "same", "one", 2) { calls += "new" }
        retries.record(RuntimeRecoveryPolicy.Platform.JM, "same", "two", 2) { calls += "jm" }
        retries.take(RuntimeRecoveryPolicy.Platform.MANWA, "same", 3).forEach { it() }
        assertEquals(listOf("new"), calls)
        retries.take(RuntimeRecoveryPolicy.Platform.JM, "same", 3).forEach { it() }
        assertEquals(listOf("new", "jm"), calls)
    }

    @Test
    fun `endpoint recovery needs three consecutive transport failures and serializes validation`() {
        val endpoint = DomainIp("API.Example", " 1.2.3.4 ")
        val recovery = JmApiEndpointRecovery<String>(cooldownMs = 100, nowMs = { 0 })
        assertFalse(recovery.recordConnectionFailure(endpoint))
        assertFalse(recovery.recordConnectionFailure(endpoint))
        assertTrue(recovery.recordConnectionFailure(endpoint))
        assertEquals(3, recovery.failureCount(DomainIp("api.example", "1.2.3.4")))
        recovery.recordResponse(endpoint)
        assertEquals(0, recovery.failureCount(endpoint))

        var validations = 0
        var committed: DomainIp? = null
        val recovered = recovery.tryRecover(
            capture = { JmApiEndpointRecovery.Candidate(endpoint, "captured") },
            validate = {
                validations++
                it == endpoint
            },
            commit = {
                committed = it.endpoint
                true
            },
        )
        assertTrue(recovered)
        assertEquals(1, validations)
        assertEquals(endpoint, committed)
        assertFalse(
            recovery.tryRecover({ null }, {
                validations++
                true
            }, { true }),
        )
        assertEquals(1, validations)
    }

    @Test
    fun `retry after honors delta seconds dates and normalized endpoint`() {
        var monotonic = 1_000L
        val policy = RuntimeRetryAfterPolicy(monotonicMs = { monotonic }, epochMs = { 1_700_000_000_000L })
        val endpoint = DomainIp("API.Example", "1.2.3.4")
        policy.record(endpoint, "2")
        assertTrue(policy.isBlocked(DomainIp("api.example", "1.2.3.4")))
        assertEquals(2_000L, policy.remainingDelayMs(endpoint))
        monotonic = 3_000L
        assertFalse(policy.isBlocked(endpoint))

        policy.record(endpoint, "Wed, 21 Oct 2015 07:28:00 GMT")
        assertFalse(policy.isBlocked(endpoint))
        policy.record(endpoint, "not a date")
        assertFalse(policy.isBlocked(endpoint))
    }

    @Test
    fun `actual image rate limit response is scoped to platform and host`() {
        val host = "rate-${System.nanoTime()}.example"
        val request = Request.Builder().url("https://$host/image.jpg").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("limited")
            .header("Retry-After", "60")
            .body(ByteArray(0).toResponseBody(null))
            .build()

        RuntimeImageRateLimit.record(RuntimeRecoveryPolicy.Platform.MANWA, response)
        assertEquals(429, RuntimeImageRateLimit.blockedResponse(RuntimeRecoveryPolicy.Platform.MANWA, request)?.code)
        assertNotNull(RuntimeImageRateLimit.blockedResponse(RuntimeRecoveryPolicy.Platform.MANWA, request.newBuilder().url("https://${host.uppercase()}/another").build()))
        assertNull(RuntimeImageRateLimit.blockedResponse(RuntimeRecoveryPolicy.Platform.JM, request))
        assertNull(RuntimeImageRateLimit.blockedResponse(RuntimeRecoveryPolicy.Platform.PICACG, request))
    }

    @Test
    fun `stale page generations and manual or unchanged routes cannot trigger retry`() {
        assertFalse(
            RuntimeRecoveryPolicy.mayRetryVisiblePage(
                expectedGeneration = 4,
                currentGeneration = 5,
                visible = true,
                attemptActive = false,
                failed = true,
                sourceChanged = true,
            ),
        )
        assertFalse(
            RuntimeRecoveryPolicy.mayRetryVisiblePage(
                expectedGeneration = 4,
                currentGeneration = 4,
                visible = true,
                attemptActive = false,
                failed = true,
                sourceChanged = false,
            ),
        )
        assertTrue(
            RuntimeRecoveryPolicy.mayRetryVisiblePage(
                expectedGeneration = 4,
                currentGeneration = 4,
                visible = true,
                attemptActive = false,
                failed = true,
                sourceChanged = true,
            ),
        )
    }
}
