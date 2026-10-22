package com.zycomic.app.net

import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Runtime contracts for the production startup request and coordinator, using offline tester fakes. */
class StartupSpeedTestSharingRuntimeTest {
    private suspend fun awaitMaccmsHealth() {
        withTimeout(5_000) {
            while (MaccmsSpeedTester.snapshot.value.completedAtEpochMs == null ||
                MaccmsSpeedTester.snapshot.value.lines3Ip != MaccmsSpeedTester.routeSelection().lines3Ip ||
                MaccmsSpeedTester.running.value
            ) {
                yield()
            }
        }
    }

    private fun configurePicacgOnly() {
        MaccmsProbeRecorder.install()
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(false)
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(
            disableManwa = true,
            disablePicacg = false,
            disableJm = true,
            useLastResult = true,
            useLastLineResult = true,
            revision = 71,
        )
        PicacgRouteManager.verifiedImageSampleUrl = "https://fixture.invalid/cover.jpg"
    }

    @Test
    fun `concurrent startup waiters share one run and completed result without resetting checks`() = runBlocking {
        configurePicacgOnly()
        val enteredApply = CompletableDeferred<Unit>()
        val allowApply = CompletableDeferred<Unit>()
        PicacgSpeedTester.suspendBeforeApplyHook = {
            enteredApply.complete(Unit)
            allowApply.await()
        }

        val owner = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { enteredApply.await() }
        val checksDuringRun = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        assertEquals(7, checksDuringRun.size)

        val waiterOne = async(start = CoroutineStart.UNDISPATCHED) { PlatformSpeedTestCoordinator.awaitStartup() }
        val waiterTwo = async(start = CoroutineStart.UNDISPATCHED) { PlatformSpeedTestCoordinator.awaitStartup() }
        assertEquals(checksDuringRun, PlatformSpeedTestCoordinator.startupCheckStatuses.value)
        assertEquals(1, PicacgSpeedTester.runCount)

        allowApply.complete(Unit)
        val result = withTimeout(5_000) { owner.await() }
        assertEquals(result, withTimeout(5_000) { waiterOne.await() })
        assertEquals(result, withTimeout(5_000) { waiterTwo.await() })
        val completedChecks = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        assertEquals(7, completedChecks.size)
        assertEquals(result, withTimeout(5_000) { PlatformSpeedTestCoordinator.awaitStartup() })
        assertEquals(completedChecks, PlatformSpeedTestCoordinator.startupCheckStatuses.value)
        assertEquals(1, PicacgSpeedTester.runCount, "late waiter must reuse the completed startup request")
        awaitMaccmsHealth()
        assertEquals(3, MaccmsProbeRecorder.invocations.size, "the shared run probes Maccms only once")
    }

    @Test
    fun `queued ordinary same-key run is skipped after the lock owner completes`() = runBlocking {
        configurePicacgOnly()
        val enteredImages = CompletableDeferred<Unit>()
        val releaseImages = CompletableDeferred<Unit>()
        PicacgSpeedTester.suspendBeforeImagesHook = {
            enteredImages.complete(Unit)
            releaseImages.await()
        }

        val owner = async { PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true) }
        withTimeout(5_000) { enteredImages.await() }
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { PlatformSpeedTestCoordinator.run() }
        assertEquals(1, PicacgSpeedTester.runCount)
        releaseImages.complete(Unit)
        val ownerResult = withTimeout(5_000) { owner.await() }
        assertEquals(ownerResult, withTimeout(5_000) { duplicate.await() })
        assertEquals(1, PicacgSpeedTester.runCount, "the queued normal run checks completedKey after acquiring the lock")
    }

    @Test
    fun `cancelling one startup waiter leaves worker and surviving waiter intact`() = runBlocking {
        configurePicacgOnly()
        val enteredApply = CompletableDeferred<Unit>()
        val allowApply = CompletableDeferred<Unit>()
        PicacgSpeedTester.suspendBeforeApplyHook = {
            enteredApply.complete(Unit)
            allowApply.await()
        }

        val cancelledWaiter = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { enteredApply.await() }
        val survivingWaiter = async(start = CoroutineStart.UNDISPATCHED) { PlatformSpeedTestCoordinator.awaitStartup() }
        cancelledWaiter.cancelAndJoin()
        assertTrue(cancelledWaiter.isCancelled)
        assertTrue(!allowApply.isCompleted, "cancelling a waiter must not release or cancel the independent worker")
        assertEquals(1, PicacgSpeedTester.runCount)

        allowApply.complete(Unit)
        val result = withTimeout(5_000) { survivingWaiter.await() }
        assertEquals("tested", result.single { it.platform == "哔咔" }.status)
        assertEquals(1, PicacgSpeedTester.runCount)
        awaitMaccmsHealth()
        assertEquals(3, MaccmsProbeRecorder.invocations.size)
    }

    @Test
    fun `force fresh and identity changes create new startup generations`() = runBlocking {
        configurePicacgOnly()
        PlatformSpeedTestCoordinator.awaitStartup()
        assertEquals(1, PicacgSpeedTester.runCount)

        PlatformSpeedTestCoordinator.retestAllEnabled()
        assertEquals(2, PicacgSpeedTester.runCount, "explicit full retest starts an independent fresh run")
        PlatformSpeedTestCoordinator.awaitStartup()
        assertEquals(2, PicacgSpeedTester.runCount, "ordinary same-key startup reuses the completed full retest")
        PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        assertEquals(3, PicacgSpeedTester.runCount, "an explicit config change must not reuse a startup request")
        PlatformSpeedTestCoordinator.awaitStartup()

        val mutations: List<() -> Unit> = listOf(
            { NetworkModule.direct = !NetworkModule.direct },
            { DevConfig.version += 1 },
            {
                StartupPlatformPolicy.state.value = StartupPlatformPolicy.state.value.copy(
                    disableManwa = false,
                    revision = StartupPlatformPolicy.state.value.revision + 1,
                )
            },
            {
                PicacgRouteManager.configRevision += 1
                PicacgRouteManager.apiUrls = listOf("https://changed-${PicacgRouteManager.configRevision}.picacg.test")
            },
        )
        mutations.forEachIndexed { index, mutate ->
            val before = PicacgSpeedTester.runCount
            mutate()
            val result = PlatformSpeedTestCoordinator.awaitStartup()
            assertEquals(before + 1, PicacgSpeedTester.runCount, "identity mutation $index must start a new generation")
            assertEquals("tested", result.single { it.platform == "哔咔" }.status)
        }
    }

    @Test
    fun `stale startup generation cannot publish old report or lines3 IP`() = runBlocking {
        configurePicacgOnly()
        val enteredApply = CompletableDeferred<Unit>()
        val allowApply = CompletableDeferred<Unit>()
        PicacgSpeedTester.suspendBeforeApplyHook = {
            if (!enteredApply.isCompleted) {
                enteredApply.complete(Unit)
                allowApply.await()
            }
        }

        val startup = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { enteredApply.await() }
        val staleIp = "192.0.2.11"
        PicacgRouteManager.configRevision += 1
        PicacgRouteManager.apiUrls = listOf("https://latest-generation.picacg.test")
        allowApply.complete(Unit)

        val reports = withTimeout(5_000) { startup.await() }
        val report = reports.single { it.platform == "哔咔" }
        val latestIp = "192.0.2.12"
        assertEquals(2, PicacgSpeedTester.runCount, "stale work must be retried against the new route generation")
        assertEquals(listOf("https://latest-generation.picacg.test"), report.lineCandidates)
        assertTrue(report.selectedLine.startsWith("https://latest-generation.picacg.test"))
        assertEquals(latestIp, PicacgSpeedTester.snapshot.value?.roundSelectedLine?.ip)
        assertNotEquals(staleIp, PicacgSpeedTester.snapshot.value?.roundSelectedLine?.ip)
        awaitMaccmsHealth()
        assertTrue(MaccmsProbeRecorder.invocations.isNotEmpty())
        assertTrue(MaccmsProbeRecorder.invocations.all { it.ip == latestIp }, "only the latest generation may supply lines3 IP")
        assertEquals(3, MaccmsProbeRecorder.invocations.size, "stale generation must not probe or publish Maccms lines")
        assertEquals(reports, PlatformSpeedTestCoordinator.reports.value)
    }
}
