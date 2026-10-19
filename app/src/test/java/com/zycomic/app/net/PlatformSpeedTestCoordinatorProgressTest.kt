package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformSpeedTestCoordinatorProgressTest {
    @Test
    fun `JM progress text keeps stage and counters without endpoint identity`() {
        val progress = JmSpeedTestProgress(
            phase = JmSpeedTestProgress.Phase.TESTING_LINES,
            completed = 3,
            total = 8,
            successful = 2,
            failed = 1,
            endpoint = "jm.example.test [192.0.2.18]",
            endpointSuccessful = true,
            endpointElapsedMs = 42,
            elapsedMs = 12_000,
        )

        val message = progress.message()

        assertTrue(message.contains("线路测速"))
        assertTrue(message.contains("3/8"))
        assertTrue(message.contains("成功 2 / 失败 1"))
        assertTrue(message.contains("已用 12秒"))
        assertFalse(message.contains("jm.example.test"))
        assertFalse(message.contains("192.0.2.18"))
        assertEquals("jm.example.test [192.0.2.18]", progress.endpoint)
        assertEquals(42L, progress.endpointElapsedMs)
    }

    @Test
    fun `Manwa line and image progress messages expose real counters`() {
        val line = PlatformSpeedTestCoordinator.manwaProgressMessage(
            SpeedTester.Progress(
                phase = SpeedTester.Phase.TESTING_LINES,
                completed = 3,
                total = 7,
                successful = 2,
                failed = 1,
            ),
        )
        val images = PlatformSpeedTestCoordinator.manwaProgressMessage(
            SpeedTester.Progress(
                phase = SpeedTester.Phase.TESTING_IMAGES,
                completed = 4,
                total = 9,
                successful = 2,
                failed = 1,
                noImage = 1,
            ),
        )

        assertTrue(line.contains("漫蛙"))
        assertTrue(line.contains("3/7"))
        assertTrue(line.contains("可用 2，失败 1"))
        assertTrue(images.contains("4/9"))
        assertTrue(images.contains("无图 1"))
    }

    @Test
    fun `Manwa completion message reports results and image reuse`() {
        val message = PlatformSpeedTestCoordinator.manwaProgressMessage(
            SpeedTester.Progress(
                phase = SpeedTester.Phase.COMPLETE,
                completed = 12,
                total = 12,
                successful = 8,
                failed = 2,
                noImage = 2,
                freshImages = 10,
                reusedImages = 2,
            ),
        )

        assertTrue(message.contains("12/12"))
        assertTrue(message.contains("可用 8、失败 2、无图 2"))
        assertTrue(message.contains("新测 10、保留历史 2（本轮未测）"))
    }

    @Test
    fun `all failed top five recommends explicit full retest rather than expanding`() {
        val message = PlatformSpeedTestCoordinator.manwaProgressMessage(
            SpeedTester.Progress(
                phase = SpeedTester.Phase.COMPLETE,
                fullRetestRecommended = true,
            ),
        )
        assertTrue(message.contains("本轮不再扩测"))
        assertTrue(message.contains("完整重新测速"))
    }
}
