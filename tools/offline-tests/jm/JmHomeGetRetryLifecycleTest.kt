package com.zycomic.app.net

import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CancellationException

/** Runs the actual JM home retry coroutine policy with mutable offline lifecycle stubs. */
class JmHomeGetRetryLifecycleTest {
    private var ownerCurrent = true

    @BeforeEach
    fun reset() {
        ownerCurrent = true
        RuntimeNetworkGate.online = true
        RuntimeNetworkGate.revision = 1L
        JmAuthStore.sessionRevision = 1L
        JmRouteManager.baseUrl = "https://jm.example"
        JmRouteManager.runtimeConfigRevision = 1L
        JmRouteManager.lineEndpoint = DomainIp("jm.example", "198.51.100.2")
        StartupPlatformPolicy.enabled = true
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.Value(1L)
    }

    private fun capture() = JmHomeRequestScope.capture { ownerCurrent }

    @Test
    fun `one disconnect is retried once and returns second response`() = runBlocking {
        var calls = 0
        val result = JmHomeGetRetryPolicy.run("/promote", "GET", capture()) {
            calls++
            if (calls == 1) throw EOFException("disconnected")
            "fresh response"
        }
        assertEquals("fresh response", result)
        assertEquals(2, calls)
    }

    @Test
    fun `second disconnect does not make a third call`() {
        var calls = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                JmHomeGetRetryPolicy.run("/promote", "GET", capture()) {
                    calls++
                    throw EOFException("disconnected $calls")
                }
            }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `refresh exit account config network and endpoint changes during backoff prevent retry`() {
        val invalidations = listOf<() -> Unit>(
            { ownerCurrent = false },
            { StartupPlatformPolicy.enabled = false },
            { JmAuthStore.sessionRevision++ },
            { JmRouteManager.runtimeConfigRevision++ },
            { RuntimeNetworkGate.revision++ },
            { JmRouteManager.lineEndpoint = DomainIp("jm.example", "198.51.100.9") },
        )
        invalidations.forEach { invalidate ->
            reset()
            var calls = 0
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    val request = async {
                        JmHomeGetRetryPolicy.run("/promote", "GET", capture()) {
                            calls++
                            throw EOFException("disconnected")
                        }
                    }
                    delay(35)
                    invalidate()
                    request.await()
                }
            }
            assertEquals(1, calls, "invalidated scope must not invoke retry")
        }
    }

    @Test
    fun `invalidation during second call prevents returning its stale result`() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<String>()
        var calls = 0
        val request = async {
            JmHomeGetRetryPolicy.run("/promote", "GET", capture()) {
                calls++
                if (calls == 1) throw EOFException("disconnected")
                secondStarted.complete(Unit)
                releaseSecond.await()
            }
        }
        secondStarted.await()
        JmRouteManager.runtimeConfigRevision++
        releaseSecond.complete("stale response")
        assertThrows(CancellationException::class.java) { runBlocking { request.await() } }
        assertEquals(2, calls)
    }

    @Test
    fun `coroutine cancellation during second call prevents returning its result`() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<String>()
        var calls = 0
        val request = async {
            JmHomeGetRetryPolicy.run("/promote", "GET", capture()) {
                calls++
                if (calls == 1) throw EOFException("disconnected")
                withContext(NonCancellable) {
                    secondStarted.complete(Unit)
                    releaseSecond.await()
                }
            }
        }
        secondStarted.await()
        request.cancel()
        releaseSecond.complete("cancelled response")
        assertThrows(CancellationException::class.java) { runBlocking { request.await() } }
        assertEquals(2, calls)
    }

    @Test
    fun `bootstrap route allows its first request after endpoint is remembered but never retries`() = runBlocking {
        JmRouteManager.lineEndpoint = null
        val scope = capture()
        var calls = 0
        val result = JmHomeGetRetryPolicy.run("/promote", "GET", scope) {
            calls++
            JmRouteManager.rememberLineEndpoint(DomainIp("jm.example", "198.51.100.7"))
            "bootstrap response"
        }
        assertEquals("bootstrap response", result)
        assertTrue(scope.isCurrent(), "remembering the bootstrap-resolved endpoint must not stale its request")
        assertTrue(scope.matches("jm.example", JmRouteManager.lineEndpoint), "the bootstrap scope must accept its newly resolved host endpoint")
        assertEquals(1, calls)

        JmRouteManager.lineEndpoint = null
        var failedCalls = 0
        assertThrows(EOFException::class.java) {
            runBlocking {
                JmHomeGetRetryPolicy.run("/promote", "GET", capture()) {
                    failedCalls++
                    throw EOFException("bootstrap disconnect")
                }
            }
        }
        assertEquals(1, failedCalls, "an unpinned bootstrap route is never retried")
    }
}
