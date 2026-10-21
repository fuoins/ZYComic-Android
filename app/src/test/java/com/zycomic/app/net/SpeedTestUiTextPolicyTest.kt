package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedTestUiTextPolicyTest {
    @Test
    fun `endpoint labels preserve domain and IP and image description does not claim they are hidden`() {
        assertEquals("example.test · 192.0.2.1", SpeedTestUiTextPolicy.endpointLabel("example.test", "192.0.2.1"))

        val description = SpeedTestUiTextPolicy.imageSourceDescription(
            ImageSourceDisplayPolicy.Kind.MANUAL_PIN,
            inSuccessfulList = false,
        )
        assertTrue(description.contains("不代表测速成功"))
        assertFalse(description.contains("已隐藏"))
    }

    @Test
    fun `startup automatic selection reports only missing line addresses`() {
        assertEquals(
            "启动自动选线未解析到线路地址，已保留当前线路",
            SpeedTestUiTextPolicy.startupNoAddressToast(hasLineAddress = false),
        )
        assertNull(SpeedTestUiTextPolicy.startupNoAddressToast(hasLineAddress = true))
    }

    @Test
    fun `startup suppresses success and skipped completion toasts but shows failures and manual runs`() {
        assertFalse(SpeedTestUiTextPolicy.shouldShowCompletionToast(isStartupRun = true, status = "tested"))
        assertFalse(SpeedTestUiTextPolicy.shouldShowCompletionToast(isStartupRun = true, status = "reused"))
        assertFalse(SpeedTestUiTextPolicy.shouldShowCompletionToast(isStartupRun = true, status = "skipped"))
        assertTrue(SpeedTestUiTextPolicy.shouldShowCompletionToast(isStartupRun = true, status = "incomplete"))
        assertTrue(SpeedTestUiTextPolicy.shouldShowCompletionToast(isStartupRun = true, status = "failed"))
        assertTrue(SpeedTestUiTextPolicy.shouldShowCompletionToast(isStartupRun = false, status = "tested"))
        assertFalse(SpeedTestUiTextPolicy.shouldShowCompletionToast(isStartupRun = false, status = "skipped"))
    }

    @Test
    fun `platform stage progress keeps real counters without endpoint identities`() {
        val line = SpeedTestUiTextPolicy.platformEndpointProgress(
            platform = "漫蛙",
            endpointKind = "线路",
            completed = 3,
            total = 7,
            successful = 2,
            failed = 1,
        )
        val image = SpeedTestUiTextPolicy.platformEndpointProgress(
            platform = "漫蛙",
            endpointKind = "图源",
            completed = 4,
            total = 9,
            successful = 2,
            failed = 1,
            noImage = 1,
        )

        assertTrue(line.contains("3/7"))
        assertTrue(line.contains("可用 2，失败 1"))
        assertTrue(image.contains("4/9"))
        assertTrue(image.contains("可用 2，连不上 1，无图 1"))
        assertFalse(line.contains("example"))
        assertFalse(image.contains("192.0.2.1"))
    }
}
