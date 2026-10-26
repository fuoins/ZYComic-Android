package com.zycomic.app.net

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** JVM-safe contracts used by the Android coordinator; full object orchestration is exercised by the offline harness. */
class StartupSpeedTestCoordinatorRuntimeTest {
    @Test
    fun `gate holds speculative apply until first authoritative completion`() = runBlocking {
        val gate = StartupSpeedTestGate()
        val arrived = async { gate.awaitConfigurationUpdated() }
        assertFalse(arrived.isCompleted)
        gate.complete(configurationUpdated = true)
        assertTrue(withTimeout(1_000) { arrived.await() })
        gate.complete(configurationUpdated = false)
        assertEquals(true, gate.configurationUpdated)
    }

    @Test
    fun `cancelling one gate waiter does not cancel completion for other waiters`() = runBlocking {
        val gate = StartupSpeedTestGate()
        val cancelledWaiter = async { gate.awaitConfigurationUpdated() }
        val survivingWaiter = async { gate.awaitConfigurationUpdated() }
        cancelledWaiter.cancelAndJoin()
        gate.complete(configurationUpdated = false)
        assertEquals(false, withTimeout(1_000) { survivingWaiter.await() })
        assertTrue(cancelledWaiter.isCancelled)
    }

    @Test
    fun `generation changes invalidate dedupe while preserving independent platform identity`() {
        val first = SpeedTestRunKey(7, 11, 13, "wifi|direct")
        assertTrue(SpeedTestRunPolicy.canSkip(first, first, forceFresh = false, platformOnly = false))
        assertFalse(SpeedTestRunPolicy.canSkip(first, first.copy(manwaConfigRevision = 12), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(first, first.copy(jmConfigRevision = 14), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(first, first.copy(platformRevision = 8), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(first, first.copy(networkIdentity = "cell|direct"), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(first, first, forceFresh = false, platformOnly = true))
    }

    @Test
    fun `updated remote configuration keeps quick reuse and defers new endpoints to reconciliation`() {
        for (requested in listOf(false, true)) {
            // 配置更新不再强制全量：新增端点由 reconcile 补测，已有身份沿用历史。
            assertTrue(
                SpeedTestRunPolicy.canFastTest(requested, forceFresh = false, remoteConfigUpdated = true) == requested,
            )
            assertTrue(
                SpeedTestRunPolicy.canReuseImages(
                    requested = requested,
                    forceFresh = false,
                    initial = SpeedTestRunKey(1, 1, 1, "n"),
                    current = SpeedTestRunKey(1, 2, 2, "n"),
                    completed = null,
                    remoteConfigUpdated = true,
                ) == requested,
            )
        }
    }

    @Test
    fun `cancelled coroutine waiter reports cancellation rather than completing`() = runBlocking {
        val gate = StartupSpeedTestGate()
        val waiter = async { gate.awaitConfigurationUpdated() }
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
        assertTrue(gate.configurationUpdated == null)
    }
}
