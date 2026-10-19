package com.zycomic.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmFavoriteLibraryUiPolicyTest {
    @Test
    fun `JM display mode defaults to comfortable grid with independent preference key`() {
        assertEquals(1, JmFavoriteLibraryUiPolicy.DEFAULT_DISPLAY_MODE)
        assertEquals("jm_library_display_mode", JmFavoriteLibraryUiPolicy.DISPLAY_MODE_PREFERENCE_KEY)
        assertEquals("舒适网格", JmFavoriteLibraryUiPolicy.displayModes[1].label)
        assertEquals(3, JmFavoriteLibraryUiPolicy.gridColumns())
    }

    @Test
    fun `display choices match Manwa mode labels and normalize invalid stored values`() {
        assertEquals(
            listOf("紧凑网格", "舒适网格", "仅封面网格", "列表1", "列表2", "列表3"),
            JmFavoriteLibraryUiPolicy.displayModes.map { it.label },
        )
        assertTrue(JmFavoriteLibraryUiPolicy.isGrid(0))
        assertTrue(JmFavoriteLibraryUiPolicy.isGrid(1))
        assertTrue(JmFavoriteLibraryUiPolicy.isGrid(2))
        assertFalse(JmFavoriteLibraryUiPolicy.isGrid(3))
        assertEquals(0, JmFavoriteLibraryUiPolicy.normalizeDisplayMode(-1))
        assertEquals(5, JmFavoriteLibraryUiPolicy.normalizeDisplayMode(6))
    }

    @Test
    fun `row count settings are JM-specific by orientation with auto fallback and bounded values`() {
        assertEquals("jm_library_grid_columns_portrait", JmFavoriteLibraryUiPolicy.gridColumnsPreferenceKey(false))
        assertEquals("jm_library_grid_columns_landscape", JmFavoriteLibraryUiPolicy.gridColumnsPreferenceKey(true))
        assertEquals(3, JmFavoriteLibraryUiPolicy.gridColumns(0))
        assertEquals(4, JmFavoriteLibraryUiPolicy.gridColumns(4))
        assertEquals(0, JmFavoriteLibraryUiPolicy.normalizeGridColumns(-1))
        assertEquals(10, JmFavoriteLibraryUiPolicy.normalizeGridColumns(11))
    }

    @Test
    fun `JM favorite sort exposes only official favorite time and manga update time`() {
        assertEquals("mr", JmFavoriteLibraryUiPolicy.DEFAULT_SORT_ORDER)
        assertEquals(listOf("mr", "mp"), JmFavoriteLibraryUiPolicy.sortModes.map { it.order })
        assertEquals(listOf("收藏时间", "漫画更新时间"), JmFavoriteLibraryUiPolicy.sortModes.map { it.label })
    }
}
