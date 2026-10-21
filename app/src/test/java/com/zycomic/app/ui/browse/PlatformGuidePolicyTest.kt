package com.zycomic.app.ui.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformGuidePolicyTest {
    @Test
    fun `each existing platform ID has its own non-empty guide`() {
        assertEquals("manwa", PlatformGuidePolicy.MANWA_ID)
        assertEquals("picacg", PlatformGuidePolicy.PICACG_ID)
        assertEquals("jm", PlatformGuidePolicy.JM_ID)

        val manwa = PlatformGuidePolicy.sections(PlatformGuidePolicy.MANWA_ID)
        val picacg = PlatformGuidePolicy.sections(PlatformGuidePolicy.PICACG_ID)
        val jm = PlatformGuidePolicy.sections(PlatformGuidePolicy.JM_ID)

        assertEquals(3, manwa.size)
        assertEquals(3, picacg.size)
        assertEquals(3, jm.size)
        assertTrue((manwa + picacg + jm).all { it.title.isNotBlank() && it.body.isNotBlank() })
        assertNotEquals(manwa, picacg)
        assertNotEquals(manwa, jm)
        assertNotEquals(picacg, jm)
    }

    @Test
    fun `guide copy describes platform-specific implemented entry points`() {
        val manwa = PlatformGuidePolicy.sections(PlatformGuidePolicy.MANWA_ID).joinToString(" ") { it.body }
        val picacg = PlatformGuidePolicy.sections(PlatformGuidePolicy.PICACG_ID).joinToString(" ") { it.body }
        val jm = PlatformGuidePolicy.sections(PlatformGuidePolicy.JM_ID).joinToString(" ") { it.body }

        assertTrue(manwa.contains("一般向热门") && manwa.contains("热血热门"))
        assertTrue(picacg.contains("哔咔首页") && picacg.contains("关键词搜索"))
        assertTrue(jm.contains("jm首页") && jm.contains("禁漫搜索"))
    }

    @Test
    fun `unknown platform IDs are not silently mapped to another guide`() {
        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            PlatformGuidePolicy.sections("unexpected")
        }
        assertTrue(error.message.orEmpty().contains("unexpected"))
    }
}
