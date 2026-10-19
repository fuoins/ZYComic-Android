package com.zycomic.app.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartupSpeedTestGateTest {
    @Test
    fun `startup and network callers wait for final config then share one full generation`() = runBlocking {
        withTimeout(5_000) {
            val gate = StartupSpeedTestGate()
            val lock = Mutex()
            var current = SpeedTestRunKey(1, 2, 3, "wifi")
            var completed: SpeedTestRunKey? = null
            var fullRuns = 0
            val requests = List(3) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    val updated = gate.awaitConfigurationUpdated()
                    lock.withLock {
                        if (!SpeedTestRunPolicy.canSkip(completed, current, false, false)) {
                            assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, current, current, completed, updated))
                            fullRuns++
                            completed = current
                        }
                    }
                }
            }
            assertEquals(0, fullRuns)
            assertTrue(requests.none { it.isCompleted })
            current = current.copy(manwaConfigRevision = 4, jmConfigRevision = 5)
            gate.complete(configurationUpdated = true)
            requests.awaitAll()
            assertEquals(1, fullRuns)
            assertEquals(current, completed)
        }
    }

    @Test
    fun `unchanged or failed startup check releases cached-config probes`() = runBlocking {
        withTimeout(5_000) {
            val gate = StartupSpeedTestGate()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitConfigurationUpdated() }
            assertNull(gate.configurationUpdated)
            assertFalse(waiter.isCompleted)
            gate.complete(configurationUpdated = false)
            assertFalse(waiter.await())
            assertEquals(false, gate.configurationUpdated)
        }
    }

    @Test
    fun `later retry cannot replace the startup outcome`() = runBlocking {
        val gate = StartupSpeedTestGate()
        gate.complete(configurationUpdated = true)
        gate.complete(configurationUpdated = false)
        assertTrue(gate.awaitConfigurationUpdated())
        assertEquals(true, gate.configurationUpdated)
    }

    @Test
    fun `cancelled waiter does not cancel other callers`() = runBlocking {
        withTimeout(5_000) {
            val gate = StartupSpeedTestGate()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitConfigurationUpdated() }
            val remaining = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitConfigurationUpdated() }
            cancelled.cancel()
            gate.complete(configurationUpdated = true)
            assertTrue(remaining.await())
        }
    }
}
