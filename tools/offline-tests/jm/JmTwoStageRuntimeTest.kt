package com.zycomic.jmcache

import com.zycomic.app.net.DevConfig
import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.JmSpeedTestSubmission
import com.zycomic.app.net.JmSpeedTester
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.platform.PolicyState
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.routecache.MemoryContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class JmTwoStageRuntimeTest {
    private val lines = listOf("https://api.example")
    private val images = listOf("img.example")

    @BeforeEach
    fun setup() {
        DevConfig.appContext = MemoryContext()
        DevConfig.followSystemVpn = false
        NetworkModule.systemVpnActive = false
        StartupPlatformPolicy.enabled = true
        StartupPlatformPolicy.state.value = PolicyState()
        JmNetworkModule.paths.clear()
        JmNetworkModule.urls.clear()
        JmNetworkModule.failedHosts = emptySet()
        JmNetworkModule.responseDelayMs = null
        JmNetworkModule.resetProbeCounts()
    }

    private fun configure(size: Int = 6, version: Int = 100) {
        JmRouteManager.applyRemoteConfig(
            lines,
            images,
            mapOf(
                "api.example" to (1..size).map { "192.0.2.$it" },
                "img.example" to (1..size).map { "198.51.100.$it" },
            ),
            version,
        )
    }

    @Test
    fun `quick and full announce usable routes before remaining probes finish but completion waits`() = runBlocking {
        configure()
        JmSpeedTester.run() // Populate eligible history for the quick retest.
        for (quick in listOf(true, false)) {
            JmNetworkModule.resetProbeCounts()
            JmNetworkModule.responseDelayMs = { _, _, sequence -> if (sequence == 1) 0 else 500 }
            val ready = CompletableDeferred<Unit>()
            val complete = AtomicBoolean(false)
            val run = async(Dispatchers.Default) {
                JmSpeedTester.run(
                    reuseLines = quick,
                    reuseImages = quick,
                    onRouteReady = { ready.complete(Unit) },
                    onProgress = { if (it.phase == com.zycomic.app.net.JmSpeedTestProgress.Phase.COMPLETE) complete.set(true) },
                )
            }
            withTimeout(5_000) { ready.await() }
            delay(40)
            assertFalse(complete.get(), "quick=$quick must not report COMPLETE while deferred endpoints are still in flight")
            assertTrue(JmSpeedTester.isRunning)
            val result = withTimeout(15_000) { run.await() }
            assertEquals(JmSpeedTestSubmission.State.COMPLETE, result.submission?.state)
            assertTrue(complete.get())
            assertEquals(6, result.snapshot.testedLineEndpoints.size)
            assertEquals(6, result.snapshot.testedImageEndpoints.size)
            JmNetworkModule.responseDelayMs = null
        }
    }

    @Test
    fun `successful probe clears previous round failure and makes endpoint eligible for early route publication`() = runBlocking {
        configure(size = 2)
        JmNetworkModule.failedHosts = setOf("api.example")
        val failed = JmSpeedTester.run()
        assertTrue(failed.snapshot.lineIpDelaysMs.values.all { it == Long.MAX_VALUE })
        JmNetworkModule.failedHosts = emptySet()
        var ready = false
        val recovered = JmSpeedTester.run(onRouteReady = { ready = true })
        assertTrue(ready)
        assertTrue(recovered.snapshot.lineIpDelaysMs.values.all { it < Long.MAX_VALUE })
        assertNotNull(JmRouteManager.selectedLineEndpoint)
        assertTrue(JmRouteManager.selectedLineEndpoint!!.ip in (1..2).map { "192.0.2.$it" })
    }

    @Test
    fun `later measured faster endpoint supersedes initial live route`() = runBlocking {
        configure(size = 6)
        val firstObserved = CompletableDeferred<DomainIp>()
        val delays = java.util.concurrent.ConcurrentHashMap<String, Long>()
        JmNetworkModule.responseDelayMs = { host, path, sequence ->
            val ms = if (sequence == 1) 20L else if (sequence <= 3) 250L else 0L
            delays["$host$path#$sequence"] = ms
            ms
        }
        val run = JmSpeedTester.run(reuseLines = true, reuseImages = true, onRouteReady = {
            JmRouteManager.selectedLineEndpoint?.let { firstObserved.complete(it) }
        })
        val atReady = firstObserved.await()
        val measuredBest = run.snapshot.lineIpDelaysMs.minBy { it.value }.key
        assertEquals(measuredBest, JmRouteManager.selectedLineEndpoint)
        assertEquals(6, run.snapshot.lineIpDelaysMs.size)
        assertNotEquals(atReady, JmRouteManager.selectedLineEndpoint)
        assertTrue(atReady.domain == "api.example")
        assertNotNull(JmRouteManager.currentImageEndpoint())
    }

    @Test
    fun `live measurements do not override manual line or image pins`() = runBlocking {
        configure(size = 3)
        val initial = JmSpeedTester.run().snapshot
        val linePin = initial.lineIpDelaysMs.keys.first()
        val imagePin = initial.imageIpDelaysMs.keys.first()
        assertTrue(JmRouteManager.selectLineEndpoint(linePin.domain, linePin.ip))
        assertTrue(JmRouteManager.selectImageEndpoint(imagePin.domain, imagePin.ip))
        JmNetworkModule.responseDelayMs = { _, _, sequence -> if (sequence == 1) 2L else 10L }
        JmSpeedTester.run(onRouteReady = { })
        assertEquals(linePin, JmRouteManager.selectedLineEndpoint)
        assertEquals(imagePin, JmRouteManager.currentImageEndpoint())
    }

    @Test
    fun `stale config or platform generation cannot apply measured routes`() {
        configure(size = 2)
        val endpoint = DomainIp("api.example", "192.0.2.1")
        val image = DomainIp("img.example", "198.51.100.1")
        val revision = JmRouteManager.currentSpeedTestConfigRevision()
        val selection = JmRouteManager.endpointSelection.value
        JmRouteManager.applyRemoteConfig(lines, images, mapOf("api.example" to listOf("192.0.2.9"), "img.example" to listOf("198.51.100.9")), 100)
        assertFalse(JmRouteManager.applyMeasuredLineRoute(endpoint, revision, StartupPlatformPolicy.state.value.revision))
        assertFalse(JmRouteManager.applyMeasuredImageRoute(image, revision, StartupPlatformPolicy.state.value.revision))
        assertNotEquals(endpoint.ip, JmRouteManager.selectedLineEndpoint?.ip)
        assertNotEquals(image.ip, JmRouteManager.currentImageEndpoint()?.ip)

        val currentRevision = JmRouteManager.currentSpeedTestConfigRevision()
        val platformRevision = StartupPlatformPolicy.state.value.revision
        StartupPlatformPolicy.state.value = PolicyState(platformRevision + 1)
        assertFalse(JmRouteManager.applyMeasuredLineRoute(DomainIp("api.example", "192.0.2.9"), currentRevision, platformRevision))
        assertFalse(JmRouteManager.applyMeasuredImageRoute(DomainIp("img.example", "198.51.100.9"), currentRevision, platformRevision))
        assertNotNull(selection)
    }
}
