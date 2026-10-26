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
    fun `network compatible readiness releases a waiter whose configuration generation changed`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val request = StartupSpeedTestRequest<SpeedTestRunKey, String>(scope)
            var key = SpeedTestRunKey(1, 1, 1, "wifi")
            val published = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                request.runUntilReady(
                    { key },
                    compatible = { publishedKey, currentKey -> publishedKey.networkIdentity == currentKey.networkIdentity },
                ) { publish ->
                    publish(key, "usable")
                    published.complete(Unit)
                    finish.await()
                    key to "complete"
                }
            }
            published.await()
            // 同一网络下配置代变化（例如后台刚应用了新配置）不应让前台继续等到整轮结束。
            key = key.copy(manwaConfigRevision = 2, remoteVersion = 2)
            assertEquals("usable", withTimeout(2_000) { waiter.await() })
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

    @Test
    fun `full takeover cancels blocked startup tail and redirects live waiters without a fake completion`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val startup = StartupSpeedTestRequest<Int, String>(scope)
            val full = StartupSpeedTestRequest<Int, String>(scope, reuseCompleted = false)
            val testerLock = kotlinx.coroutines.sync.Mutex()
            val oldStarted = CompletableDeferred<Unit>()
            val oldCancelled = CompletableDeferred<Unit>()
            val fullStarted = CompletableDeferred<Unit>()
            val qualify = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            var fullRuns = 0
            suspend fun awaitFull(): String = full.runUntilReady({ 1 }) { publish ->
                fullRuns++
                testerLock.lock()
                try {
                    assertTrue(oldCancelled.isCompleted)
                    fullStarted.complete(Unit)
                    qualify.await()
                    publish(1, "qualified")
                    finish.await()
                    completed.complete(Unit)
                    1 to "all actually measured"
                } finally {
                    testerLock.unlock()
                }
            }
            val oldWaiter = async {
                startup.runUntilReady({ 1 }) {
                    testerLock.lock()
                    try {
                        oldStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        error("retired startup cannot complete")
                    } finally {
                        testerLock.unlock()
                        oldCancelled.complete(Unit)
                    }
                }
            }
            oldStarted.await()
            startup.supersedeWith { awaitFull() }
            withTimeout(2_000) { fullStarted.await() }
            val explicit = async { awaitFull() }
            val repeated = async { awaitFull() }
            kotlinx.coroutines.yield()
            assertFalse(oldWaiter.isCompleted)
            assertFalse(completed.isCompleted)
            qualify.complete(Unit)
            assertEquals("qualified", withTimeout(2_000) { oldWaiter.await() })
            assertEquals("qualified", explicit.await())
            assertEquals("qualified", repeated.await())
            assertEquals(1, fullRuns)
            assertFalse(completed.isCompleted)
            finish.complete(Unit)
            completed.await()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling a UI waiter during takeover cannot resurrect it or cancel successor work`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val startup = StartupSpeedTestRequest<Int, String>(scope)
            val started = CompletableDeferred<Unit>()
            val waiter = async {
                startup.run({ 1 }) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    1 to "old"
                }
            }
            started.await()
            waiter.cancel()
            var transfers = 0
            startup.supersedeWith {
                transfers++
                "successor"
            }
            waiter.join()
            assertTrue(waiter.isCancelled)
            assertEquals(0, transfers)
            assertEquals("successor", startup.run({ 1 }) { error("retired request must not restart") })
            assertEquals(1, transfers)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `late transferred startup waiter reuses full completion but explicit request starts a new full round`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val full = StartupSpeedTestRequest<Int, String>(scope, reuseCompleted = false)
            assertEquals("measured", full.run({ 1 }) { 1 to "measured" })
            assertEquals("measured", full.runUntilReady({ 1 }, reuseCompletedResult = true) { error("late startup waiter must not restart full work") })
            assertEquals("explicit fresh", full.run({ 1 }) { 1 to "explicit fresh" })
        } finally {
            scope.cancel()
        }
    }
}
