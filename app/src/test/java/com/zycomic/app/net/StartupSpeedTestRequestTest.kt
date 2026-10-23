package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupSpeedTestRequestTest {
    @Test
    fun `routes ready releases waiters but does not finish or cancel the shared worker`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val request = StartupSpeedTestRequest<Int, String>(scope)
            val ready = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val waiter = async {
                request.runUntilReady({ 1 }) { publish ->
                    ready.await()
                    publish(1, "usable")
                    finish.await()
                    completed.complete(Unit)
                    1 to "complete"
                }
            }
            ready.complete(Unit)
            assertEquals("usable", withTimeout(2_000) { waiter.await() })
            assertFalse(completed.isCompleted)
            assertEquals("usable", request.runUntilReady({ 1 }) { error("must share active background worker") })
            finish.complete(Unit)
            withTimeout(2_000) { completed.await() }
            assertEquals("complete", request.run({ 1 }) { error("must reuse true completion") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `old generation readiness cannot release a waiter for the new generation`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val request = StartupSpeedTestRequest<Int, String>(scope)
            var key = 1
            val oldPublished = CompletableDeferred<Unit>()
            val publishCurrent = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                request.runUntilReady({ key }) { publish ->
                    key = 2
                    publish(1, "stale")
                    oldPublished.complete(Unit)
                    publishCurrent.await()
                    publish(2, "current")
                    finish.await()
                    2 to "complete"
                }
            }
            oldPublished.await()
            kotlinx.coroutines.yield()
            assertFalse(waiter.isCompleted)
            publishCurrent.complete(Unit)
            assertEquals("current", withTimeout(2_000) { waiter.await() })
            finish.complete(Unit)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `full request shares active background work but does not cache a completed full round`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val request = StartupSpeedTestRequest<Int, String>(scope, reuseCompleted = false)
            val finish = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            assertEquals(
                "usable",
                request.runUntilReady({ 1 }) { publish ->
                    publish(1, "usable")
                    finish.await()
                    completed.complete(Unit)
                    1 to "complete"
                },
            )
            assertEquals("usable", request.runUntilReady({ 1 }) { error("must share active full round") })
            finish.complete(Unit)
            completed.await()
            kotlinx.coroutines.yield()
            assertEquals("fresh", request.run({ 1 }) { 1 to "fresh" })
        } finally {
            scope.cancel()
        }
    }

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
