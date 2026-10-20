package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlatformStartupStatusTest {
    @Test
    fun `progress fraction is absent without work and bounded for real counters`() {
        assertNull(platformStartupProgressFraction(0, 0))
        assertEquals(0.5f, platformStartupProgressFraction(2, 4))
        assertEquals(1f, platformStartupProgressFraction(8, 4))
        assertEquals(0f, platformStartupProgressFraction(-1, 4))
    }

    @Test
    fun `updating one platform leaves concurrent platform progress unchanged`() {
        val manwa = PlatformStartupStatus("漫蛙线路 2/5", fraction = 0.4f, running = true)
        val jm = PlatformStartupStatus("禁漫天堂图源 1/8", fraction = 0.125f, running = true)
        val current = mapOf(ContentPlatform.MANWA to manwa, ContentPlatform.JM to jm)

        val updated = updatePlatformStartupStatus(
            current,
            ContentPlatform.MANWA,
            PlatformStartupStatus("漫蛙测速完成"),
        )

        assertEquals("漫蛙测速完成", updated.getValue(ContentPlatform.MANWA).message)
        assertEquals(jm, updated.getValue(ContentPlatform.JM))
    }

    @Test
    fun `coordinator maps each native tester counter to its own active status`() {
        val manwa = PlatformSpeedTestCoordinator.manwaStartupStatus(
            SpeedTester.Progress(phase = SpeedTester.Phase.TESTING_IMAGES, completed = 2, total = 5),
        )
        val picacg = PlatformSpeedTestCoordinator.picacgStartupStatus(
            PicacgSpeedTester.Progress(PicacgSpeedTester.Stage.TESTING_API, completed = 3, total = 6),
        )
        val jm = PlatformSpeedTestCoordinator.jmStartupStatus(
            JmSpeedTestProgress(phase = JmSpeedTestProgress.Phase.TESTING_LINES, completed = 4, total = 8),
        )

        assertEquals(0.4f, manwa.fraction)
        assertEquals(0.5f, picacg.fraction)
        assertEquals(0.5f, jm.fraction)
        assertEquals(true, manwa.running)
        assertEquals(true, picacg.running)
        assertEquals(true, jm.running)
        assertEquals(
            "禁漫天堂 · 测速完成 0/0 · 成功 0 / 失败 0 · 已用 0秒",
            PlatformSpeedTestCoordinator.jmStartupStatus(
                JmSpeedTestProgress(phase = JmSpeedTestProgress.Phase.COMPLETE),
            ).message,
        )
        assertEquals(
            false,
            PlatformSpeedTestCoordinator.picacgStartupStatus(
                PicacgSpeedTester.Progress(PicacgSpeedTester.Stage.IMAGE_BLOCKED),
            ).running,
        )
    }
}
