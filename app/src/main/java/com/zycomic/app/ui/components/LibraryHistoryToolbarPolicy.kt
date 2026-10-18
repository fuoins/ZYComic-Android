package com.zycomic.app.ui.components

/** Keep history refresh aligned with the favorites filter, without moving selection/platform. */
internal object LibraryHistoryToolbarPolicy {
    enum class Action { SEARCH, REFRESH, FILTER, EMPTY }

    fun leadingActions(history: Boolean): List<Action> = if (history) {
        listOf(Action.EMPTY, Action.SEARCH, Action.REFRESH)
    } else {
        listOf(Action.SEARCH, Action.REFRESH, Action.FILTER)
    }
}
