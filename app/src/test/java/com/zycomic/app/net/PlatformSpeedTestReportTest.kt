package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformSpeedTestReportTest {
    private fun report(status: String = "tested", manual: Boolean = true) = PlatformSpeedTestReport(
        platform = "禁漫天堂",
        status = status,
        reason = "当前配置候选",
        selectedLine = "https://chosen.example",
        selectedImage = "chosen-image.example",
        manualLine = manual,
        manualImage = manual,
        lineCandidates = listOf("https://chosen.example", "https://faster.example"),
        imageCandidates = listOf("chosen-image.example", "faster-image.example"),
        lineDelays = mapOf("chosen.example" to 200L, "faster.example" to 10L),
        imageDelays = mapOf("chosen-image.example" to 200L, "faster-image.example" to 10L),
    )

    @Test
    fun `manual selection summary names actual choices without claiming fastest selected`() {
        val summary = report().summary()
        assertTrue(summary.contains("https://chosen.example（手动）"))
        assertTrue(summary.contains("chosen-image.example（手动）"))
        assertFalse(summary.contains("最快"))
        assertFalse(summary.contains("faster"))
    }

    @Test
    fun `skipped platform never claims measured or selected`() {
        val summary = report("skipped").summary()
        assertTrue(summary.contains("跳过"))
        assertFalse(summary.contains("本次新测"))
        assertFalse(summary.contains("chosen.example"))
    }

    @Test
    fun `reuse and fresh tests remain distinguishable`() {
        assertTrue(report("reused", false).summary().contains("复用上次有效结果"))
        assertTrue(report("tested", false).summary().contains("本次新测"))
        assertTrue(report("incomplete", false).summary().contains("测速未完成"))
    }
}
