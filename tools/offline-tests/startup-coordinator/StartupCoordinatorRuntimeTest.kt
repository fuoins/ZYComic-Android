package com.zycomic.app.net

import com.zycomic.app.platform.StartupPlatformPolicy
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exercises the production coordinator/request classes with deterministic, offline endpoint fakes. */
class StartupCoordinatorRuntimeTest {
    @org.junit.jupiter.api.BeforeEach
    fun installOfflineHealthTransport() {
        MaccmsSpeedTester.installProbeTransportForTests { host, _ ->
            MaccmsSpeedTester.Probe(host, 2, true, 204)
        }
    }

    private fun configureManwaOnly() {
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(false)
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(
            disableManwa = false,
            disablePicacg = true,
            disableJm = true,
            useLastResult = true,
            useLastLineResult = true,
            revision = 910,
        )
        SpeedTester.emitQualifiedEndpoint = true
        SpeedTester.progressEvents = listOf(
            SpeedTester.Progress(SpeedTester.Phase.TESTING_LINES, completed = 1, total = 1, successful = 1),
            SpeedTester.Progress(SpeedTester.Phase.TESTING_IMAGES, completed = 1, total = 1, successful = 1),
        )
    }

    @Test
    fun `full retest cancels blocked quick tail and immediately starts full work`() = runBlocking {
        configureManwaOnly()
        val lowTailStarted = CompletableDeferred<Unit>()
        val lowTailCancelled = CompletableDeferred<Unit>()
        val fullTailStarted = CompletableDeferred<Unit>()
        val releaseFullTail = CompletableDeferred<Unit>()
        SpeedTester.suspendAfterQualifiedEndpointHook = { run ->
            when (run) {
                1 -> {
                    lowTailStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        lowTailCancelled.complete(Unit)
                    }
                }
                2 -> {
                    fullTailStarted.complete(Unit)
                    releaseFullTail.await()
                }
            }
        }

        val startup = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { lowTailStarted.await() }
        assertTrue(PlatformSpeedTestCoordinator.foregroundReady.value)
        assertTrue(PlatformSpeedTestCoordinator.running.value)
        assertEquals(StartupTestMode.QUICK, PlatformSpeedTestCoordinator.testMode.value)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty())
        assertTrue(withTimeout(5_000) { startup.await() }.isEmpty())

        val full = async { PlatformSpeedTestCoordinator.retestAllEnabled() }
        withTimeout(5_000) { fullTailStarted.await() }
        withTimeout(5_000) { lowTailCancelled.await() }
        assertEquals(2, SpeedTester.runCount, "the full request must not wait for the old low-priority tail")
        assertEquals(StartupTestMode.FULL, PlatformSpeedTestCoordinator.testMode.value)
        assertEquals(listOf(true, false), SpeedTester.reuseLines.take(2), "the replacement must be a non-reused full test")
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty(), "early endpoint readiness is not final report publication")

        releaseFullTail.complete(Unit)
        withTimeout(5_000) { full.await() }
        withTimeout(5_000) {
            while (PlatformSpeedTestCoordinator.running.value) yield()
        }
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
        assertEquals(StartupCheckResult.SUCCESS, PlatformSpeedTestCoordinator.startupCheckStatuses.value
            .single { it.phase == StartupCheckPhase.MANWA_LINE }.result)
    }

    @Test
    fun `qualified endpoint releases startup while reports wait for all measured work and duplicate retests share it`() = runBlocking {
        configureManwaOnly()
        val fullTailStarted = CompletableDeferred<Unit>()
        val releaseFullTail = CompletableDeferred<Unit>()
        SpeedTester.suspendAfterQualifiedEndpointHook = { run ->
            if (run == 1) {
                fullTailStarted.complete(Unit)
                releaseFullTail.await()
            }
        }

        val first = async { PlatformSpeedTestCoordinator.retestAllEnabled() }
        withTimeout(5_000) { fullTailStarted.await() }
        assertTrue(PlatformSpeedTestCoordinator.foregroundReady.value, "a real qualified endpoint should unblock foreground readiness")
        assertTrue(PlatformSpeedTestCoordinator.running.value)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty(), "reports stay unpublished while any platform test is in flight")

        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { PlatformSpeedTestCoordinator.retestAllEnabled() }
        yield()
        assertEquals(1, SpeedTester.runCount, "duplicate clicks must attach to the same full request")
        // This API is the splash waiter, not the owner of final report publication. Returning an
        // empty ready token must neither fabricate reports nor terminate the background worker.
        val firstResult = withTimeout(5_000) { first.await() }
        val duplicateResult = withTimeout(5_000) { duplicate.await() }
        assertTrue(firstResult.isEmpty())
        assertEquals(firstResult, duplicateResult)
        assertTrue(PlatformSpeedTestCoordinator.running.value)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty())
        releaseFullTail.complete(Unit)
        withTimeout(5_000) {
            while (PlatformSpeedTestCoordinator.running.value) yield()
        }
        assertEquals(1, SpeedTester.runCount)
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
    }

    @Test
    fun `configuration generation change keeps the same network waiter released and reruns current generation`() = runBlocking {
        configureManwaOnly()
        val oldGenerationReady = CompletableDeferred<Unit>()
        val releaseOldGeneration = CompletableDeferred<Unit>()
        SpeedTester.suspendAfterQualifiedEndpointHook = { run ->
            if (run == 1) {
                oldGenerationReady.complete(Unit)
                releaseOldGeneration.await()
            }
        }

        val startup = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { oldGenerationReady.await() }
        val oldRevision = RouteManager.configRevision
        // 配置代变化但网络未变：已实测可用的端点照样放行，用户不必再等一整轮。
        RouteManager.configRevision++
        releaseOldGeneration.complete(Unit)
        assertTrue(withTimeout(5_000) { startup.await() }.isEmpty(), "early readiness is not a completed report list")
        // 旧代结果不参与发布；后台按新代重跑并给出完整报告。
        withTimeout(5_000) {
            while (SpeedTester.runCount < 2) yield()
        }
        assertEquals(oldRevision + 1, RouteManager.configRevision)
        withTimeout(5_000) {
            while (PlatformSpeedTestCoordinator.running.value) yield()
        }
        assertEquals(2, SpeedTester.runCount)
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.all { it.status != "incomplete" })
    }

    @Test
    fun `network identity generation change discards old startup outcome and reruns current generation`() = runBlocking {
        configureManwaOnly()
        val connectivity = ConnectivityManager().apply { activeNetwork = Network("old-network") }
        DevConfig.appContext = object : Context() {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) connectivity else null
        }
        val oldGenerationReady = CompletableDeferred<Unit>()
        val releaseOldGeneration = CompletableDeferred<Unit>()
        SpeedTester.suspendAfterQualifiedEndpointHook = { run ->
            if (run == 1) {
                oldGenerationReady.complete(Unit)
                releaseOldGeneration.await()
            }
        }

        val startup = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { oldGenerationReady.await() }
        connectivity.activeNetwork = Network("new-network")
        releaseOldGeneration.complete(Unit)
        withTimeout(5_000) {
            while (SpeedTester.runCount < 2) yield()
        }
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty(), "no stale-network report may publish")
        withTimeout(5_000) { startup.await() }
        withTimeout(5_000) {
            while (PlatformSpeedTestCoordinator.running.value) yield()
        }
        assertEquals(2, SpeedTester.runCount)
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.all { it.status != "incomplete" })
    }

    @Test
    fun `failed probes do not become a qualified success`() = runBlocking {
        configureManwaOnly()
        SpeedTester.emitQualifiedEndpoint = false
        SpeedTester.progressEvents = listOf(
            SpeedTester.Progress(SpeedTester.Phase.TESTING_LINES, completed = 3, total = 3, successful = 0),
            SpeedTester.Progress(SpeedTester.Phase.TESTING_IMAGES, completed = 2, total = 2, successful = 0),
        )
        PlatformSpeedTestCoordinator.awaitStartup()

        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value.filter {
            it.phase == StartupCheckPhase.MANWA_LINE || it.phase == StartupCheckPhase.MANWA_IMAGE
        }
        assertEquals(2, checks.size)
        assertTrue(checks.all { it.result == StartupCheckResult.FAILED })
        assertFalse(checks.any { it.result == StartupCheckResult.SUCCESS })
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
    }
    @Test
    fun `full takeover transfers an unreleased startup waiter instead of cancelling or completing it from the old run`() = runBlocking {
        configureManwaOnly()
        SpeedTester.emitQualifiedEndpoint = false
        SpeedTester.emitRouteReady = true
        val oldBlocked = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        val fullBlocked = CompletableDeferred<Unit>()
        val releaseFull = CompletableDeferred<Unit>()
        SpeedTester.suspendBeforeRouteReadyHook = {
            if (SpeedTester.runCount == 1) {
                oldBlocked.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    oldCancelled.complete(Unit)
                }
            }
        }
        SpeedTester.suspendAfterQualifiedEndpointHook = {
            fullBlocked.complete(Unit)
            releaseFull.await()
        }
        val startup = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { oldBlocked.await() }
        assertFalse(startup.isCompleted)
        assertFalse(PlatformSpeedTestCoordinator.foregroundReady.value)
        SpeedTester.emitQualifiedEndpoint = true
        val full = async { PlatformSpeedTestCoordinator.retestAllEnabled() }
        withTimeout(5_000) { fullBlocked.await() }
        assertTrue(oldCancelled.isCompleted)
        assertEquals(2, SpeedTester.runCount)
        assertTrue(withTimeout(5_000) { startup.await() }.isEmpty())
        assertTrue(withTimeout(5_000) { full.await() }.isEmpty())
        assertTrue(PlatformSpeedTestCoordinator.running.value)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty())
        releaseFull.complete(Unit)
        withTimeout(5_000) {
            while (PlatformSpeedTestCoordinator.running.value) yield()
        }
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
        assertEquals(2, SpeedTester.runCount)
        // Automatic waiters arriving after completion reuse this full result rather than spawn a third run.
        assertEquals(3, PlatformSpeedTestCoordinator.awaitStartup().size)
        assertEquals(2, SpeedTester.runCount)
    }

    @Test
    fun `first qualified enabled business unblocks splash while other enabled platforms continue loading`() = runBlocking {
        configureManwaOnly()
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.state.value.copy(
            disablePicacg = false,
            disableJm = false,
            revision = 911,
        )
        PicacgRouteManager.verifiedImageSampleUrl = "https://fixture.invalid/cover.jpg"
        val entered = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        SpeedTester.suspendAfterQualifiedEndpointHook = { entered[0].complete(Unit); release[0].await() }
        PicacgSpeedTester.suspendBeforeApplyHook = { entered[1].complete(Unit); release[1].await() }
        JmSpeedTester.emitRouteReady = true
        JmSpeedTester.suspendBeforeRouteReadyHook = { entered[2].complete(Unit); release[2].await() }
        val splash = async { PlatformSpeedTestCoordinator.retestAllEnabled() }
        withTimeout(5_000) { entered.forEach { it.await() } }
        assertTrue(withTimeout(5_000) { splash.await() }.isEmpty())
        assertTrue(PlatformSpeedTestCoordinator.foregroundReady.value)
        assertTrue(PlatformSpeedTestCoordinator.running.value)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty())
        assertTrue(PlatformSpeedTestCoordinator.platformStartupStatuses.value.getValue(com.zycomic.app.platform.ContentPlatform.JM).running)
        assertTrue(PlatformSpeedTestCoordinator.platformStartupStatuses.value.getValue(com.zycomic.app.platform.ContentPlatform.PICACG).running)
        release.forEach { it.complete(Unit) }
        withTimeout(5_000) {
            while (PlatformSpeedTestCoordinator.running.value) yield()
        }
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
    }

}
