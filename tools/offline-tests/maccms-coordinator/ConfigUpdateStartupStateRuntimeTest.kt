package com.zycomic.app.net

import com.zycomic.app.platform.StartupPlatformPolicy
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

/** Each method runs in a fresh JVM against the production coordinator, with offline endpoint testers. */
class ConfigUpdateStartupStateRuntimeTest {
    private val healthyManwa = DomainIp("manwa.test", "192.0.2.31")
    private val healthyJm = DomainIp("jm.test", "192.0.2.32")
    private val failedManwa = DomainIp("new.manwa.test", "192.0.2.41")
    private val failedJm = DomainIp("new.jm.test", "192.0.2.42")
    private val nonMaccmsPhases = StartupCheckPhase.entries.filter { it != StartupCheckPhase.MACCMS_LINE }.toSet()

    private fun configure(completeGate: Boolean = true) {
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(revision = 91)
        PicacgRouteManager.verifiedImageSampleUrl = "https://fixture.invalid/cover.jpg"
        if (completeGate) PlatformSpeedTestCoordinator.finishStartupConfigCheck(false)
    }

    private fun stageEvents(successful: Int, total: Int) {
        SpeedTester.progressEvents = listOf(
            SpeedTester.Progress(SpeedTester.Phase.TESTING_LINES, total, total, successful, total - successful),
            SpeedTester.Progress(SpeedTester.Phase.TESTING_IMAGES, total, total, successful, total - successful),
        )
        JmSpeedTester.progressEvents = listOf(
            JmSpeedTestProgress(JmSpeedTestProgress.Phase.TESTING_LINES, total, total, successful, total - successful),
            JmSpeedTestProgress(JmSpeedTestProgress.Phase.TESTING_IMAGES, total, total, successful, total - successful),
        )
    }

    private fun retainedHealthyMaps(includeFailedAddition: Boolean = false) {
        RouteManager.lastLineIpDelays = mapOf(healthyManwa to 15L) + if (includeFailedAddition) mapOf(failedManwa to Long.MAX_VALUE) else emptyMap()
        RouteManager.lastImgIpDelays = RouteManager.lastLineIpDelays
        JmSpeedTester.lineIpDelaysForRun = mapOf(healthyJm to 16L) + if (includeFailedAddition) mapOf(failedJm to Long.MAX_VALUE) else emptyMap()
        JmSpeedTester.imageIpDelaysForRun = JmSpeedTester.lineIpDelaysForRun
    }

    private suspend fun awaitHealth() = withTimeout(5_000) {
        while (MaccmsSpeedTester.running.value || MaccmsSpeedTester.snapshot.value.completedAtEpochMs == null ||
            MaccmsSpeedTester.snapshot.value.lines3Ip != MaccmsSpeedTester.routeSelection().lines3Ip
        ) {
            yield()
        }
    }

    @Test
    fun `config notification queued behind startup following new generation never starts a third run`() = runBlocking {
        configure(completeGate = false)
        stageEvents(successful = 1, total = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        PicacgSpeedTester.suspendBeforeApplyHook = {
            if (!entered.isCompleted) {
                entered.complete(Unit)
                release.await()
            }
        }
        val startup = async { PlatformSpeedTestCoordinator.awaitStartup() }
        withTimeout(5_000) { entered.await() }
        DevConfig.version += 1
        PicacgRouteManager.remoteVersion = DevConfig.version
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(true)
        val notification = async(start = CoroutineStart.UNDISPATCHED) {
            PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        }
        release.complete(Unit)

        val reports = withTimeout(5_000) { startup.await() }
        val notificationReports = withTimeout(5_000) { notification.await() }
        // 开屏等待者可能提前放行（空列表），重复请求返回本轮完整报告；关键是不得触发第三轮。
        assertTrue(reports.isEmpty() || reports == notificationReports)
        assertEquals(3, notificationReports.size)
        assertEquals(listOf(2, 2, 2), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
        assertEquals(7, startupChecksPassed(PlatformSpeedTestCoordinator.startupCheckStatuses.value))
        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        assertEquals(notificationReports, PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true))
        assertEquals(checks, PlatformSpeedTestCoordinator.startupCheckStatuses.value)
        assertEquals(2, PicacgSpeedTester.apiProbeCount)
        awaitHealth()
        assertEquals(3, MaccmsProbeRecorder.invocations.size, "only the authoritative round binds and checks the three Maccms hosts")
    }

    @Test
    fun `late duplicate config notifications preserve checks shared binding and all tester counts`() = runBlocking {
        configure()
        stageEvents(successful = 1, total = 1)
        val reports = PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        awaitHealth()
        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        val binding = MaccmsSpeedTester.routeSelection()
        assertEquals(7, startupChecksPassed(checks))

        repeat(2) { assertEquals(reports, PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)) }
        assertEquals(reports, PlatformSpeedTestCoordinator.awaitStartup())
        assertEquals(checks, PlatformSpeedTestCoordinator.startupCheckStatuses.value)
        assertEquals(binding, MaccmsSpeedTester.routeSelection())
        assertEquals(listOf(1, 1, 1), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
        assertEquals(3, MaccmsProbeRecorder.invocations.size)
    }

    @Test
    fun `incomplete Picacg retry preserves completed Manwa and JM success and failure checks throughout`() = runBlocking {
        configure()
        PicacgRouteManager.verifiedImageSampleUrl = null
        stageEvents(successful = 1, total = 1)
        SpeedTester.progressEvents = listOf(
            SpeedTester.Progress(SpeedTester.Phase.TESTING_LINES, 1, 1, 0, 1),
            SpeedTester.Progress(SpeedTester.Phase.TESTING_IMAGES, 1, 1, 1, 0),
        )
        PlatformSpeedTestCoordinator.run()
        val phases = setOf(StartupCheckPhase.MANWA_LINE, StartupCheckPhase.MANWA_IMAGE, StartupCheckPhase.JM_LINE, StartupCheckPhase.JM_IMAGE)
        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value.filter { it.phase in phases }
        assertEquals(StartupCheckResult.FAILED, checks.first { it.phase == StartupCheckPhase.MANWA_LINE }.result)
        assertEquals(3, startupChecksPassed(checks))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        PicacgSpeedTester.suspendBeforeImagesHook = {
            entered.complete(Unit)
            release.await()
        }
        val retry = async { PlatformSpeedTestCoordinator.run() }
        withTimeout(5_000) { entered.await() }
        assertEquals(checks, PlatformSpeedTestCoordinator.startupCheckStatuses.value.filter { it.phase in phases })
        release.complete(Unit)
        withTimeout(5_000) { retry.await() }
        assertEquals(checks, PlatformSpeedTestCoordinator.startupCheckStatuses.value.filter { it.phase in phases })
        assertEquals(listOf(1, 2, 1), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
    }

    @Test
    fun `persistent retained measurements do not become this rounds health`() = runBlocking {
        configure()
        stageEvents(successful = 0, total = 0)
        retainedHealthyMaps()
        PicacgSpeedTester.imageProbesOverride = listOf(
            PicacgSpeedTester.Probe(DomainIp("img.picacg.test", "192.0.2.50"), true, 9, 200, testedThisRound = false),
        )
        val reports = PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        assertTrue(reports.filter { it.platform != "哔咔" }.all { it.status == "tested" })
        assertEquals("incomplete", reports.single { it.platform == "哔咔" }.status)
        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        assertEquals(StartupCheckResult.NOT_TESTED, checks.first { it.phase == StartupCheckPhase.MANWA_LINE }.result)
        assertEquals(StartupCheckResult.NOT_TESTED, checks.first { it.phase == StartupCheckPhase.MANWA_IMAGE }.result)
        assertEquals(StartupCheckResult.NOT_TESTED, checks.first { it.phase == StartupCheckPhase.JM_LINE }.result)
        assertEquals(StartupCheckResult.NOT_TESTED, checks.first { it.phase == StartupCheckPhase.JM_IMAGE }.result)
        assertEquals(StartupCheckResult.SUCCESS, checks.first { it.phase == StartupCheckPhase.PICACG_LINE }.result)
        assertEquals(StartupCheckResult.NOT_TESTED, checks.first { it.phase == StartupCheckPhase.PICACG_IMAGE }.result)
        assertEquals(2, startupChecksPassed(checks))
        val picacg = reports.single { it.platform == "哔咔" }
        assertEquals("incomplete", picacg.imageStatus)
        assertTrue(picacg.imageDelays.containsKey("img.picacg.test [192.0.2.50]"))
    }

    @Test
    fun `old successful history cannot hide this rounds measured failures`() = runBlocking {
        configure()
        stageEvents(successful = 0, total = 1)
        retainedHealthyMaps(includeFailedAddition = true)
        PicacgSpeedTester.apiProbesOverride = listOf(
            PicacgSpeedTester.Probe(DomainIp("api.picacg.test", "192.0.2.60"), true, 9, 200, testedThisRound = false),
            PicacgSpeedTester.Probe(DomainIp("api.picacg.test", "192.0.2.61"), false, 30, null),
        )
        PicacgSpeedTester.imageProbesOverride = PicacgSpeedTester.apiProbesOverride
        val reports = PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        assertTrue(checks.filter { it.phase in nonMaccmsPhases }.all { it.result == StartupCheckResult.FAILED })
        assertEquals(0, startupChecksPassed(checks.filter { it.phase in nonMaccmsPhases }))
        assertTrue(checks.first { it.phase == StartupCheckPhase.PICACG_IMAGE }.detail.orEmpty().contains("封面和正文"))
        reports.forEach { report ->
            assertEquals(1, report.lineDelays.values.count { it == Long.MAX_VALUE }, report.platform)
            assertEquals(1, report.imageDelays.values.count { it == Long.MAX_VALUE }, report.platform)
        }
        assertFalse(PicacgSpeedTester.snapshot.value!!.apiProbes.last().success)
    }

    @Test
    fun `explicit full retest failures never become reused ready from old latency maps`() = runBlocking {
        configure()
        stageEvents(successful = 0, total = 1)
        retainedHealthyMaps()
        PicacgSpeedTester.apiProbesOverride = listOf(
            PicacgSpeedTester.Probe(DomainIp("api.picacg.test", "192.0.2.60"), true, 9, 200, testedThisRound = false),
            PicacgSpeedTester.Probe(DomainIp("api.picacg.test", "192.0.2.61"), false, 30, null),
        )
        PicacgSpeedTester.imageProbesOverride = PicacgSpeedTester.apiProbesOverride
        PlatformSpeedTestCoordinator.run(forceFresh = true, remoteConfigUpdated = true)
        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value.filter { it.phase in nonMaccmsPhases }
        assertTrue(checks.all { it.result == StartupCheckResult.FAILED })
        assertEquals(0, startupChecksPassed(checks))
        assertEquals(listOf(false), SpeedTester.reuseLines)
        assertEquals(listOf(false to false), JmSpeedTester.reuseFlags)
        assertEquals(listOf(true), PicacgSpeedTester.forceFreshFlags)
    }

    @Test
    fun `real updated generation with no effective success remains failed`() = runBlocking {
        configure()
        stageEvents(successful = 1, total = 1)
        PlatformSpeedTestCoordinator.run()
        DevConfig.version += 1
        PicacgRouteManager.remoteVersion = DevConfig.version
        stageEvents(successful = 0, total = 1)
        RouteManager.lastLineIpDelays = mapOf(failedManwa to Long.MAX_VALUE)
        RouteManager.lastImgIpDelays = RouteManager.lastLineIpDelays
        JmSpeedTester.lineIpDelaysForRun = mapOf(failedJm to Long.MAX_VALUE)
        JmSpeedTester.imageIpDelaysForRun = JmSpeedTester.lineIpDelaysForRun
        PicacgSpeedTester.apiProbesOverride = listOf(PicacgSpeedTester.Probe(DomainIp("api.picacg.test", "192.0.2.61"), false, 30, null))
        PicacgSpeedTester.imageProbesOverride = PicacgSpeedTester.apiProbesOverride

        PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        assertTrue(PlatformSpeedTestCoordinator.startupCheckStatuses.value.filter { it.phase in nonMaccmsPhases }.all { it.result == StartupCheckResult.FAILED })
        assertEquals(listOf(2, 2, 2), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
    }

    @Test
    fun `partial adaptive quick successes become healthy when the testers stop`() = runBlocking {
        configure()
        stageEvents(successful = 2, total = 8)

        PlatformSpeedTestCoordinator.run()

        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        listOf(StartupCheckPhase.MANWA_LINE, StartupCheckPhase.MANWA_IMAGE, StartupCheckPhase.JM_LINE, StartupCheckPhase.JM_IMAGE)
            .forEach { phase -> assertEquals(StartupCheckResult.SUCCESS, checks.first { it.phase == phase }.result, phase.name) }
        assertTrue(PicacgSpeedTester.reuseFlags.all { it == (false to false) }, "each Picacg run must freshly probe API and images")
    }

    @Test
    fun `Picacg history-only successful probes remain untested for health`() = runBlocking {
        configure()
        val api = PicacgSpeedTester.Probe(DomainIp("api.picacg.test", "192.0.2.70"), true, 9, 200, testedThisRound = false)
        val cover = PicacgSpeedTester.Probe(DomainIp("img.picacg.test", "192.0.2.71"), true, 9, 200, testedThisRound = false)
        val content = PicacgSpeedTester.Probe(
            DomainIp("img.picacg.test", "192.0.2.72"),
            true,
            9,
            200,
            testedThisRound = false,
            content = true,
        )
        PicacgSpeedTester.apiProbesOverride = listOf(api)
        PicacgSpeedTester.imageProbesOverride = listOf(cover, content)

        PlatformSpeedTestCoordinator.run()

        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        assertEquals(StartupCheckResult.NOT_TESTED, checks.first { it.phase == StartupCheckPhase.PICACG_LINE }.result)
        assertEquals(StartupCheckResult.NOT_TESTED, checks.first { it.phase == StartupCheckPhase.PICACG_IMAGE }.result)
    }

    @Test
    fun `Picacg cover success without a successful content probe is not healthy`() = runBlocking {
        configure()
        PicacgSpeedTester.imageProbesOverride = listOf(
            PicacgSpeedTester.Probe(DomainIp("img.picacg.test", "192.0.2.80"), true, 9, 200),
            PicacgSpeedTester.Probe(DomainIp("img.picacg.test", "192.0.2.81"), false, 30, null, content = true),
        )

        PlatformSpeedTestCoordinator.run()

        val image = PlatformSpeedTestCoordinator.startupCheckStatuses.value.first { it.phase == StartupCheckPhase.PICACG_IMAGE }
        assertEquals(StartupCheckResult.FAILED, image.result)
        assertTrue(image.detail.orEmpty().contains("封面和正文"))
    }
}
