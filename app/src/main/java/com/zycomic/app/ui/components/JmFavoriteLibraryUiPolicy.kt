package com.zycomic.app.ui.components

/** JM favorites-only display and sort choices; these are intentionally independent from Manwa preferences. */
internal object JmFavoriteLibraryUiPolicy {
    const val DEFAULT_DISPLAY_MODE = 1
    const val DEFAULT_SORT_ORDER = "mr"
    const val DISPLAY_MODE_PREFERENCE_KEY = "jm_library_display_mode"

    data class DisplayMode(val value: Int, val label: String)
    data class SortMode(val order: String, val label: String)

    val displayModes = listOf(
        DisplayMode(0, "紧凑网格"),
        DisplayMode(1, "舒适网格"),
        DisplayMode(2, "仅封面网格"),
        DisplayMode(3, "列表1"),
        DisplayMode(4, "列表2"),
        DisplayMode(5, "列表3"),
    )

    val sortModes = listOf(
        SortMode("mr", "收藏时间"),
        SortMode("mp", "漫画更新时间"),
    )

    fun normalizeDisplayMode(value: Int): Int = value.coerceIn(displayModes.first().value, displayModes.last().value)

    fun isGrid(displayMode: Int): Boolean = normalizeDisplayMode(displayMode) <= 2

    fun gridColumns(displayMode: Int): Int = if (normalizeDisplayMode(displayMode) == 1) 2 else 3
}
