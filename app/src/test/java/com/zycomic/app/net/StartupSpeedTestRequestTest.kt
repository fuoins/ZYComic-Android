package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupSpeedTestRequestTest {
    @Test
    fun `completion caches the published identity not a newer unmeasured identity`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val request = StartupSpeedTestRequest<Int, Any>(scope)
            var key = 1
            val first = Any()
            assertSame(
                first,
                request.run({ key }) {
                    key = 2
                    1 to first
                },
            )
            val second = Any()
            assertSame(second, request.run({ key }) { 2 to second })
            assertSame(second, request.run({ key }) { error("must share the completed job") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed startup worker can be retried by the next waiter`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val request = StartupSpeedTestRequest<Int, String>(scope)
            var failed = false
            try {
                request.run({ 1 }) { error("fixture failure") }
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals("retried", request.run({ 1 }) { 1 to "retried" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `invalidated old worker cannot replace the newer cached request`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val request = StartupSpeedTestRequest<Int, String>(scope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                request.run({ 1 }) {
                    started.complete(Unit)
                    release.await()
                    1 to "old"
                }
            }
            started.await()
            request.invalidate()
            assertEquals("new", request.run({ 2 }) { 2 to "new" })
            release.complete(Unit)
            assertEquals("old", old.await())
            assertEquals("new", request.run({ 2 }) { error("old completion must not replace new entry") })
        } finally {
            scope.cancel()
        }
    }
}
