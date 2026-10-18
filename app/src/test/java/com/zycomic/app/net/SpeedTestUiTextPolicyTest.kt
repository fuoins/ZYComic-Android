package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedTestUiTextPolicyTest {
    @Test
    fun `endpoint labels and selected image description do not disclose domain or IP`() {
        assertEquals("线路端点 1", SpeedTestUiTextPolicy.lineEndpointLabel(0))
        assertEquals("图源端点 3", SpeedTestUiTextPolicy.imageEndpointLabel(2))

        val description = SpeedTestUiTextPolicy.imageSourceDescription(
            ImageSourceDisplayPolicy.Kind.MANUAL_PIN,
            inSuccessfulList = false,
        )
        assertTrue(description.contains("具体域名/IP已隐藏"))
        assertTrue(description.contains("不代表测速成功"))
        assertFalse(description.contains("example"))
        assertFalse(description.contains("192.0.2.1"))
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
