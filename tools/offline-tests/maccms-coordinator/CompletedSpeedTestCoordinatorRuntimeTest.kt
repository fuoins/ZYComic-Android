package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Runtime-contract tests compiled offline against the production coordinator and isolated tester fakes. */
class CompletedSpeedTestCoordinatorRuntimeTest {
    @BeforeEach
    fun configureStartupGate() {
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(false)
    }

    @Test
    fun `updated config runs full probes and duplicate no-sample request reuses other phases`() = runBlocking {
        val first = PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)

        assertEquals(listOf(1, 1, 1), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
        assertEquals(listOf(false), SpeedTester.reuseLines)
        assertEquals(listOf(false to false), PicacgSpeedTester.reuseFlags)
        assertEquals(listOf(false to false), JmSpeedTester.reuseFlags)
        assertEquals("incomplete", first.single { it.platform == "哔咔" }.status)
        assertEquals("tested", first.single { it.platform == "漫蛙" }.status)
        assertEquals("tested", first.single { it.platform == "禁漫天堂" }.status)

        val duplicate = PlatformSpeedTestCoordinator.run()

        assertEquals(listOf(1, 2, 1), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
        assertEquals(2, PicacgSpeedTester.apiProbeCount)
        assertEquals(listOf(false to false, false to false), PicacgSpeedTester.reuseFlags)
        assertEquals("incomplete", duplicate.single { it.platform == "哔咔" }.status)
        assertEquals("tested", duplicate.single { it.platform == "漫蛙" }.status)
        assertEquals("tested", duplicate.single { it.platform == "禁漫天堂" }.status)
    }

    @Test
    fun `completed config update duplicate does not rerun any tester`() = runBlocking {
        PicacgRouteManager.verifiedImageSampleUrl = "https://img.picacg.test/chapter/cover.jpg"

        val first = PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        assertEquals(listOf(false), SpeedTester.reuseLines)
        assertEquals(listOf(false), SpeedTester.reuseImages)
        assertEquals(listOf(false to false), JmSpeedTester.reuseFlags)
        assertEquals(listOf(false to false), PicacgSpeedTester.reuseFlags)
        assertTrue(first.all { it.status == "tested" })

        val duplicate = PlatformSpeedTestCoordinator.run()

        assertEquals(listOf(1, 1, 1), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
        assertEquals(listOf(false), SpeedTester.reuseLines)
        assertEquals(listOf(false to false), JmSpeedTester.reuseFlags)
        assertEquals(1, PicacgSpeedTester.apiProbeCount)
        assertEquals(1, PicacgSpeedTester.imageProbeCount)
        assertEquals(listOf(false to false), PicacgSpeedTester.reuseFlags)
        assertTrue(duplicate.all { it.status == "tested" })
    }

    @Test
    fun `normal startup retains independent top five preferences and platform toggles`() = runBlocking {
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(
            disableManwa = false,
            disablePicacg = true,
            disableJm = false,
            useLastLineResult = true,
            useLastResult = false,
            revision = 1,
        )

        val result = PlatformSpeedTestCoordinator.run()

        assertEquals(listOf(1, 0, 1), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
        assertEquals(listOf(true), SpeedTester.reuseLines)
        assertEquals(listOf(false), SpeedTester.reuseImages)
        assertEquals(listOf(true to false), JmSpeedTester.reuseFlags)
        assertTrue(result.single { it.platform == "哔咔" }.status == "skipped")
    }

    @Test
    fun `new real image sample probes Picacg API and image phases`() = runBlocking {
        PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        PicacgRouteManager.verifiedImageSampleUrl = "https://img.picacg.test/chapter/cover.jpg"

        val result = PlatformSpeedTestCoordinator.run()

        assertEquals(1, SpeedTester.runCount)
        assertEquals(1, JmSpeedTester.runCount)
        assertEquals(2, PicacgSpeedTester.runCount)
        assertEquals(2, PicacgSpeedTester.apiProbeCount)
        assertEquals(1, PicacgSpeedTester.imageProbeCount)
        assertEquals(listOf(false to false, false to false), PicacgSpeedTester.reuseFlags)
        assertEquals("tested", result.single { it.platform == "哔咔" }.status)
        assertEquals("tested", result.single { it.platform == "哔咔" }.lineStatus)
        assertEquals("tested", result.single { it.platform == "哔咔" }.imageStatus)
    }

    @Test
    fun `forceFresh retests all platforms and onlyPlatform retests just its platform`() = runBlocking {
        PicacgRouteManager.verifiedImageSampleUrl = "https://img.picacg.test/chapter/cover.jpg"
        PlatformSpeedTestCoordinator.run()

        PlatformSpeedTestCoordinator.run(forceFresh = true)
        assertEquals(listOf(2, 2, 2), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
        assertEquals(false to false, PicacgSpeedTester.reuseFlags.last())
        assertEquals(false to false, JmSpeedTester.reuseFlags.last())

        PlatformSpeedTestCoordinator.run(onlyPlatform = ContentPlatform.PICACG)
        assertEquals(listOf(2, 3, 2), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
        assertEquals(false to false, PicacgSpeedTester.reuseFlags.last())
    }

    @Test
    fun `network and remote config version changes invalidate phase reuse`() = runBlocking {
        PicacgRouteManager.verifiedImageSampleUrl = "https://img.picacg.test/chapter/cover.jpg"
        PlatformSpeedTestCoordinator.run()
        NetworkModule.direct = false
        PlatformSpeedTestCoordinator.run()
        assertEquals(listOf(2, 2, 2), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))

        DevConfig.version++
        PicacgRouteManager.remoteVersion = DevConfig.version
        PlatformSpeedTestCoordinator.run()
        assertEquals(listOf(3, 3, 3), listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount))
    }

    @Test
    fun `disabled platform does not start its underlying tester`() = runBlocking {
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(
            disableManwa = true,
            disablePicacg = true,
            disableJm = false,
            revision = 1,
        )

        val result = PlatformSpeedTestCoordinator.run()

        assertEquals(0, SpeedTester.runCount)
        assertEquals(0, PicacgSpeedTester.runCount)
        assertEquals(1, JmSpeedTester.runCount)
        assertTrue(result.filter { it.platform != "禁漫天堂" }.all { it.status == "skipped" })
        assertFalse(result.single { it.platform == "禁漫天堂" }.status == "skipped")
    }

    @Test
    fun `failed explicit single platform retest cannot be hidden by previously completed phases`() = runBlocking {
        PicacgRouteManager.verifiedImageSampleUrl = "https://img.picacg.test/chapter/cover.jpg"
        PlatformSpeedTestCoordinator.run()
        PicacgSpeedTester.failNextRun = true

        val explicit = PlatformSpeedTestCoordinator.run(onlyPlatform = ContentPlatform.PICACG)
        assertEquals(2, PicacgSpeedTester.runCount)
        assertEquals("incomplete", explicit.single { it.platform == "哔咔" }.status)

        val automaticRetry = PlatformSpeedTestCoordinator.run()

        assertEquals(3, PicacgSpeedTester.runCount)
        assertEquals(1, SpeedTester.runCount)
        assertEquals(1, JmSpeedTester.runCount)
        assertEquals("tested", automaticRetry.single { it.platform == "哔咔" }.status)
    }

    @Test
    fun `completed same-key ordinary run preserves reports checks and adds no API probes`() = runBlocking {
        PicacgRouteManager.verifiedImageSampleUrl = "https://img.picacg.test/chapter/cover.jpg"
        val completed = PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        assertTrue(completed.all { it.status == "tested" })
        val checks = PlatformSpeedTestCoordinator.startupCheckStatuses.value
        val counts = listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount, PicacgSpeedTester.apiProbeCount)

        val duplicate = PlatformSpeedTestCoordinator.run()

        assertEquals(completed, duplicate)
        assertEquals(counts, listOf(SpeedTester.runCount, PicacgSpeedTester.runCount, JmSpeedTester.runCount, PicacgSpeedTester.apiProbeCount))
        assertEquals(checks, PlatformSpeedTestCoordinator.startupCheckStatuses.value)
    }
}
