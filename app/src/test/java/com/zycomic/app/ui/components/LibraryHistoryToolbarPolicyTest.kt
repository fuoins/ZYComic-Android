package com.zycomic.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryHistoryToolbarPolicyTest {
    @Test
    fun `history refresh occupies favorites filter position`() {
        val history = LibraryHistoryToolbarPolicy.leadingActions(history = true)
        val favorites = LibraryHistoryToolbarPolicy.leadingActions(history = false)
        assertEquals(favorites.indexOf(LibraryHistoryToolbarPolicy.Action.FILTER), history.indexOf(LibraryHistoryToolbarPolicy.Action.REFRESH))
    }

    @Test
    fun `history search occupies previous refresh position`() {
        val history = LibraryHistoryToolbarPolicy.leadingActions(history = true)
        val favorites = LibraryHistoryToolbarPolicy.leadingActions(history = false)
        assertEquals(favorites.indexOf(LibraryHistoryToolbarPolicy.Action.REFRESH), history.indexOf(LibraryHistoryToolbarPolicy.Action.SEARCH))
        assertEquals(LibraryHistoryToolbarPolicy.Action.EMPTY, history.first())
        assertEquals(3, history.size)
        assertEquals(3, favorites.size)
    }
}
