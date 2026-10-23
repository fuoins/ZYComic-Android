package com.zycomic.app.net

import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests real coordinator early-return behavior while the fake platform workers remain blocked. */
class CoordinatorForegroundReadyRuntimeTest {
    private fun configureAllPlatforms() {
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(false)
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(
            disableManwa = false,
            disablePicacg = false,
            disableJm = false,
            useLastResult = true,
            useLastLineResult = true,
            revision = 81,
        )
        PicacgRouteManager.verifiedImageSampleUrl = "https://fixture.invalid/cover.jpg"
        SpeedTester.emitQualifiedEndpoint = true
        PicacgSpeedTester.emitQualifiedEndpoint = true
        JmSpeedTester.emitQualifiedEndpoint = true
    }

    private suspend fun holdAllPlatformsAfterReady(): Pair<List<CompletableDeferred<Unit>>, List<CompletableDeferred<Unit>>> {
        val entered = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        SpeedTester.suspendAfterQualifiedEndpointHook = { entered[0].complete(Unit); release[0].await() }
        PicacgSpeedTester.suspendAfterQualifiedEndpointHook = { entered[1].complete(Unit); release[1].await() }
        JmSpeedTester.suspendAfterQualifiedEndpointHook = { entered[2].complete(Unit); release[2].await() }
        return entered to release
    }

    private suspend fun awaitFullCompletion() {
        withTimeout(5_000) {
            while (PlatformSpeedTestCoordinator.running.value || PlatformSpeedTestCoordinator.reports.value.size != 3) {
                yield()
            }
        }
    }

    @Test
    fun `startup releases on qualified endpoints while reports remain running`() = runBlocking {
        configureAllPlatforms()
        val (entered, release) = holdAllPlatformsAfterReady()
        val startup = async { PlatformSpeedTestCoordinator.awaitStartup() }

        withTimeout(5_000) { entered.forEach { it.await() } }
        val early = withTimeout(5_000) { startup.await() }
        assertTrue(early.isEmpty(), "early readiness must not fabricate completed reports")
        assertTrue(PlatformSpeedTestCoordinator.foregroundReady.value)
        assertTrue(PlatformSpeedTestCoordinator.running.value, "background platform work is still active")
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty(), "reports publish only after complete work")

        release.forEach { it.complete(Unit) }
        awaitFullCompletion()
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.all { it.status == "tested" })
    }

    @Test
    fun `full retest returns early at qualified readiness then eventually publishes completed reports`() = runBlocking {
        configureAllPlatforms()
        val (entered, release) = holdAllPlatformsAfterReady()
        val full = async { PlatformSpeedTestCoordinator.retestAllEnabled() }

        withTimeout(5_000) { entered.forEach { it.await() } }
        val early = withTimeout(5_000) { full.await() }
        assertTrue(early.isEmpty(), "full retest readiness is not a completed report list")
        assertTrue(PlatformSpeedTestCoordinator.foregroundReady.value)
        assertTrue(PlatformSpeedTestCoordinator.running.value)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty())

        release.forEach { it.complete(Unit) }
        awaitFullCompletion()
        assertEquals(3, PlatformSpeedTestCoordinator.reports.value.size)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.all { it.status == "tested" })
    }

    @Test
    fun `old generation qualified callback cannot release startup waiter`() = runBlocking {
        configureAllPlatforms()
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.state.value.copy(
            disableManwa = true,
            disableJm = true,
            revision = 82,
        )
        val oldCallbackEntered = CompletableDeferred<Unit>()
        val releaseOldCallback = CompletableDeferred<Unit>()
        val currentCallbackEntered = CompletableDeferred<Unit>()
        val releaseCurrentCallback = CompletableDeferred<Unit>()
        var callbackAttempt = 0
        PicacgSpeedTester.suspendBeforeQualifiedEndpointHook = {
            when (++callbackAttempt) {
                1 -> { oldCallbackEntered.complete(Unit); releaseOldCallback.await() }
                2 -> { currentCallbackEntered.complete(Unit); releaseCurrentCallback.await() }
            }
        }
        val currentWorkEntered = CompletableDeferred<Unit>()
        val releaseCurrentWork = CompletableDeferred<Unit>()
        PicacgSpeedTester.suspendAfterQualifiedEndpointHook = {
            if (callbackAttempt == 2) {
                currentWorkEntered.complete(Unit)
                releaseCurrentWork.await()
            }
        }

        val startup = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { oldCallbackEntered.await() }
        PicacgRouteManager.configRevision++
        PicacgRouteManager.apiUrls = listOf("https://current-generation.picacg.test")
        releaseOldCallback.complete(Unit)

        withTimeout(5_000) { currentCallbackEntered.await() }
        assertFalse(PlatformSpeedTestCoordinator.foregroundReady.value, "stale callback must not satisfy current generation readiness")
        assertFalse(startup.isCompleted, "the startup waiter must continue waiting for the new generation")

        releaseCurrentCallback.complete(Unit)
        withTimeout(5_000) { currentWorkEntered.await() }
        assertTrue(PlatformSpeedTestCoordinator.foregroundReady.value)
        assertTrue(withTimeout(5_000) { startup.await() }.isEmpty())
        assertTrue(PlatformSpeedTestCoordinator.running.value)
        assertTrue(PlatformSpeedTestCoordinator.reports.value.isEmpty())

        releaseCurrentWork.complete(Unit)
        withTimeout(5_000) {
            while (PlatformSpeedTestCoordinator.running.value) yield()
        }
        val finalReports = PlatformSpeedTestCoordinator.reports.value
        assertEquals(3, finalReports.size)
        assertEquals("tested", finalReports.single { it.platform == "哔咔" }.status)
        assertTrue(finalReports.filter { it.platform != "哔咔" }.all { it.status == "skipped" })
    }
}
