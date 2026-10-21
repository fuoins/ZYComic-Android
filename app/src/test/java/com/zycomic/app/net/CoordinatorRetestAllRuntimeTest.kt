package com.zycomic.app.net

import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Runtime wiring contracts exercised against production orchestration and offline tester fakes. */
class CoordinatorRetestAllRuntimeTest {
    @BeforeEach
    fun configureStartupGate() {
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(false)
        PicacgRouteManager.verifiedImageSampleUrl = "https://img.picacg.test/chapter/cover.jpg"
    }

    @Test
    fun `retest all enabled forces fresh probes and disables tester reconciliation`() = runBlocking {
        val result = PlatformSpeedTestCoordinator.retestAllEnabled()

        assertTrue(result.all { it.status == "tested" })
        assertEquals(listOf(false), SpeedTester.reuseLines)
        assertEquals(listOf(false), SpeedTester.reuseImages)
        assertEquals(listOf(false), SpeedTester.reconcileConfigFlags)
        assertEquals(listOf(false to false), PicacgSpeedTester.reuseFlags)
        assertEquals(listOf(false to false), PicacgSpeedTester.fastFlags)
        assertEquals(listOf(false), PicacgSpeedTester.reconcileConfigFlags)
        assertEquals(listOf(true), PicacgSpeedTester.forceFreshFlags)
        assertEquals(listOf(false to false), JmSpeedTester.reuseFlags)
        assertEquals(listOf(false), JmSpeedTester.reconcileConfigFlags)
    }

    @Test
    fun `retest all enabled skips a disabled platform without invoking its tester`() = runBlocking {
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(
            disableManwa = false,
            disablePicacg = true,
            disableJm = false,
            useLastLineResult = true,
            useLastResult = true,
            revision = 1,
        )

        val result = PlatformSpeedTestCoordinator.retestAllEnabled()

        assertEquals(1, SpeedTester.runCount)
        assertEquals(0, PicacgSpeedTester.runCount)
        assertEquals(1, JmSpeedTester.runCount)
        assertEquals("skipped", result.single { it.platform == "哔咔" }.status)
        assertFalse(result.any { it.platform == "哔咔" && it.status != "skipped" })
    }

    @Test
    fun `stale generation is discarded and coordinator reruns against latest config`() = runBlocking {
        var changed = false
        SpeedTester.beforeApplyHook = {
            if (!changed) {
                changed = true
                RouteManager.configRevision++
            }
        }

        val result = PlatformSpeedTestCoordinator.run(forceFresh = true)

        assertTrue(changed)
        assertTrue(SpeedTester.runCount >= 2)
        assertTrue(result.all { it.status == "tested" })
        assertEquals(result, PlatformSpeedTestCoordinator.reports.value)
    }
}

/** Run in its own JVM: the authoritative startup gate is deliberately completed with an update. */
class CoordinatorUpdatedStartupRuntimeTest {
    @Test
    fun `incomplete images do not repeatedly mark the unchanged version as newly updated`() = runBlocking {
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(true)
        PicacgRouteManager.verifiedImageSampleUrl = null
        PlatformSpeedTestCoordinator.run()
        assertEquals(listOf(false), SpeedTester.reuseLines)
        assertEquals("incomplete", PlatformSpeedTestCoordinator.reports.value.single { it.platform == "哔咔" }.status)

        NetworkModule.direct = false
        PlatformSpeedTestCoordinator.run()
        assertEquals(listOf(false, true), SpeedTester.reuseLines)
        assertEquals(listOf(false, true), SpeedTester.reuseImages)
        assertEquals(listOf(false to false, true to true), JmSpeedTester.reuseFlags)
    }
}
