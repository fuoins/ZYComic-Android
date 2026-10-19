package com.zycomic.app.ui.components

/** JM favorites-only display and sort choices; these are intentionally independent from Manwa preferences. */
internal object JmFavoriteLibraryUiPolicy {
    const val DEFAULT_DISPLAY_MODE = 1
    const val DEFAULT_GRID_COLUMNS = 3
    const val MAX_GRID_COLUMNS = 10
    const val DEFAULT_SORT_ORDER = "mr"
    const val DISPLAY_MODE_PREFERENCE_KEY = "jm_library_display_mode"
    private const val GRID_COLUMNS_PREFERENCE_KEY = "jm_library_grid_columns"

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

    fun gridColumnsPreferenceKey(isLandscape: Boolean): String =
        "${GRID_COLUMNS_PREFERENCE_KEY}_${if (isLandscape) "landscape" else "portrait"}"

    fun normalizeGridColumns(value: Int): Int = value.coerceIn(0, MAX_GRID_COLUMNS)

    /** Search is intentionally a local filter over the list already loaded by JM endpoints. */
    fun matchesLocalSearch(query: String, name: String, author: String): Boolean {
        val text = query.trim()
        return text.isEmpty() || name.contains(text, ignoreCase = true) || author.contains(text, ignoreCase = true)
    }

    /** A stored value of zero matches Manwa's automatic setting; JM's comfortable default is three. */
    fun gridColumns(configuredColumns: Int = 0): Int {
        val normalized = normalizeGridColumns(configuredColumns)
        return if (normalized == 0) DEFAULT_GRID_COLUMNS else normalized
    }
}
