package com.zycomic.app.ui.components

/** A collection response belongs only to the account session that issued it. */
internal object PicacgCollectionPolicy {
    const val DEFAULT_SORT = "dd"
    const val DISPLAY_GRID = 0
    const val DISPLAY_LIST = 1
    const val DEFAULT_FAVORITES_DISPLAY = DISPLAY_GRID
    const val DEFAULT_GRID_COLUMNS = 3
    val sortCodes = listOf("dd", "da", "ld", "vd")

    fun normalizeSort(sort: String): String = sort.takeIf(sortCodes::contains) ?: DEFAULT_SORT

    fun normalizeDisplayMode(mode: Int): Int = mode.coerceIn(DISPLAY_GRID, DISPLAY_LIST)

    fun normalizeGridColumns(columns: Int): Int = columns.coerceIn(2, 5)

    fun displayPreferenceKey(): String = "picacg_favorites_display_mode"

    fun gridColumnsPreferenceKey(): String = "picacg_favorites_grid_columns"

    fun requiresLogin(history: Boolean, loggedIn: Boolean): Boolean = !history && !loggedIn

    fun acceptsResult(startedSession: Long, currentSession: Long, loggedIn: Boolean): Boolean =
        loggedIn && startedSession == currentSession

    fun <T> merge(existing: List<T>, incoming: List<T>, firstPage: Boolean, id: (T) -> String): List<T> =
        (if (firstPage) incoming else existing + incoming).distinctBy(id)

    fun matchesSearch(query: String?, title: String): Boolean =
        query.isNullOrBlank() || title.contains(query.trim(), ignoreCase = true)

    /** Run each requested toggle once, checking that its originating session is still active. */
    suspend fun <T> removeSequentially(
        ids: List<T>,
        ensureSession: () -> Unit,
        remove: suspend (T) -> Unit,
    ) {
        ids.forEach { id ->
            ensureSession()
            remove(id)
        }
    }
}
