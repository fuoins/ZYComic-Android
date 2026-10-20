package com.zycomic.app.ui.components

/** Keep history refresh aligned with the favorites filter, without moving selection/platform. */
internal object LibraryHistoryToolbarPolicy {
    enum class Action { SEARCH, REFRESH, FILTER, EMPTY }

    const val ACTION_SLOT_WIDTH_DP = 36
    const val PLATFORM_SELECTOR_WIDTH_DP = 156
    const val COMPACT_PLATFORM_SELECTOR_WIDTH_DP = 130

    fun leadingActions(history: Boolean): List<Action> = if (history) {
        listOf(Action.EMPTY, Action.SEARCH, Action.REFRESH)
    } else {
        listOf(Action.SEARCH, Action.REFRESH, Action.FILTER)
    }

    fun visibleActions(history: Boolean, searchExpanded: Boolean): List<Action> =
        if (searchExpanded) {
            emptyList()
        } else {
            leadingActions(history)
        }

    fun showPlatformSelector(searchExpanded: Boolean): Boolean = !searchExpanded
}

/** Reject UI results captured for a different JM account or browse tab. */
internal object BrowseScreenSessionPolicy {
    fun acceptsResult(
        startedRevision: Long,
        currentRevision: Long,
        startedTabKey: String?,
        currentTabKey: String?,
        startedOnJm: Boolean,
        currentlyOnJm: Boolean,
    ): Boolean =
        startedRevision == currentRevision &&
            startedTabKey != null &&
            startedTabKey == currentTabKey &&
            startedOnJm && currentlyOnJm
}
