package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException

class RuntimeRecoveryPolicyTest {
    @Test
    fun `cover signal requires three distinct resources and failure rate above half`() {
        val window = RuntimeRecoveryPolicy.Window()
        fun sample(resource: String, failed: Boolean, at: Long) = window.record(
            RuntimeRecoveryPolicy.Sample(
                RuntimeRecoveryPolicy.Platform.MANWA,
                "img.example",
                resource,
                RuntimeRecoveryPolicy.SampleKind.COVER,
                failed,
                at,
            ),
        )
        assertFalse(sample("a", true, 0))
        assertFalse(sample("a", true, 1))
        assertFalse(sample("b", false, 2))
        assertFalse(sample("c", false, 3))
        assertFalse(sample("d", true, 4))
        assertTrue(sample("e", true, 5))
    }

    @Test
    fun `reader recovery counts distinct visible page transport failures in ten seconds`() {
        val window = RuntimeRecoveryPolicy.Window()
        fun sample(resource: String, at: Long) = window.record(
            RuntimeRecoveryPolicy.Sample(
                RuntimeRecoveryPolicy.Platform.JM,
                "img.example",
                resource,
                RuntimeRecoveryPolicy.SampleKind.VISIBLE_READER_PAGE,
                true,
                at,
            ),
        )
        assertFalse(sample("page-1", 0))
        assertTrue(sample("page-2", 9_999))
    }

    @Test
    fun `one recovery decision drains retries for both distinct failed pages exactly once`() {
        val callbacks = RuntimeRecoveryPolicy.RetryCallbacks()
        val retried = mutableListOf<String>()
        callbacks.record(RuntimeRecoveryPolicy.Platform.JM, "img.example", "page-1", 100) { retried += "page-1" }
        callbacks.record(RuntimeRecoveryPolicy.Platform.JM, "IMG.EXAMPLE", "page-2", 101) { retried += "page-2" }

        callbacks.take(RuntimeRecoveryPolicy.Platform.JM, "img.example", 102).forEach { it() }
        callbacks.take(RuntimeRecoveryPolicy.Platform.JM, "img.example", 103).forEach { it() }

        assertEquals(listOf("page-1", "page-2"), retried)
    }

    @Test
    fun `expired callbacks and hidden or stale page generations are not retried`() {
        val callbacks = RuntimeRecoveryPolicy.RetryCallbacks()
        val retried = mutableListOf<String>()
        callbacks.record(RuntimeRecoveryPolicy.Platform.MANWA, "img.example", "expired", 0) { retried += "expired" }
        callbacks.expire(10_001)
        callbacks.take(RuntimeRecoveryPolicy.Platform.MANWA, "img.example", 10_001).forEach { it() }

        val visibleAndCurrent = RuntimeRecoveryPolicy.mayRetryVisiblePage(4, 4, true, false, true, true)
        val hidden = RuntimeRecoveryPolicy.mayRetryVisiblePage(4, 4, false, false, true, true)
        val stale = RuntimeRecoveryPolicy.mayRetryVisiblePage(4, 5, true, false, true, true)
        if (visibleAndCurrent) retried += "visible-current"
        if (hidden) retried += "hidden"
        if (stale) retried += "stale"

        assertEquals(listOf("visible-current"), retried)
    }

    @Test
    fun `expired samples and non-connection errors do not trigger transport recovery`() {
        val window = RuntimeRecoveryPolicy.Window()
        val sample = RuntimeRecoveryPolicy.Sample(
            RuntimeRecoveryPolicy.Platform.MANWA,
            "img.example",
            "page",
            RuntimeRecoveryPolicy.SampleKind.VISIBLE_READER_PAGE,
            true,
            0,
        )
        assertFalse(window.record(sample))
        assertFalse(window.record(sample.copy(resource = "page2", atMs = 10_001)))
        assertFalse(RuntimeRecoveryPolicy.isConnectionFailure(IOException("404/decode")))
        assertTrue(RuntimeRecoveryPolicy.isConnectionFailure(ConnectException("refused")))
        assertTrue(RuntimeRecoveryPolicy.isConnectionFailure(SocketTimeoutException("connect timeout")))
        assertTrue(RuntimeRecoveryPolicy.isConnectionFailure(ProtocolException("unexpected end of stream")))
        assertFalse(RuntimeRecoveryPolicy.isConnectionFailure(ProtocolException("malformed status line")))
        assertFalse(RuntimeRecoveryPolicy.isConnectionFailure(ProtocolException("invalid Content-Length header")))
        assertFalse(RuntimeRecoveryPolicy.mayRecoverApi("POST", ConnectException("refused")))
        assertTrue(RuntimeRecoveryPolicy.mayRecoverApi("GET", ConnectException("refused")))
        assertTrue(RuntimeRecoveryPolicy.mayRecoverApi("HEAD", IOException("transport wrapper", SocketTimeoutException("read timeout"))))
        assertFalse(RuntimeRecoveryPolicy.mayRecoverApi("GET", IOException("decode wrapper", IllegalArgumentException("bad JSON"))))
    }
}
