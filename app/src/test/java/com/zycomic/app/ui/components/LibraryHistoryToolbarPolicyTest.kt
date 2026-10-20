package com.zycomic.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryHistoryToolbarPolicyTest {
    @Test
    fun `toolbar reserves room for all platform options and fits a compact row`() {
        val platformMinimumWidth = 3 * 48 + 2 * 2 + 2 * 3
        val toolbarActionWidth = 4 * LibraryHistoryToolbarPolicy.ACTION_SLOT_WIDTH_DP +
            LibraryHistoryToolbarPolicy.PLATFORM_SELECTOR_WIDTH_DP

        assertTrue(LibraryHistoryToolbarPolicy.PLATFORM_SELECTOR_WIDTH_DP >= platformMinimumWidth)
        assertTrue(toolbarActionWidth <= 360 - 48)
    }

    @Test
    fun `history reserves the first action slot and keeps shared actions compact`() {
        assertEquals(
            listOf(
                LibraryHistoryToolbarPolicy.Action.EMPTY,
                LibraryHistoryToolbarPolicy.Action.SEARCH,
                LibraryHistoryToolbarPolicy.Action.REFRESH,
            ),
            LibraryHistoryToolbarPolicy.leadingActions(history = true),
        )
        assertEquals(
            listOf(
                LibraryHistoryToolbarPolicy.Action.SEARCH,
                LibraryHistoryToolbarPolicy.Action.REFRESH,
                LibraryHistoryToolbarPolicy.Action.FILTER,
            ),
            LibraryHistoryToolbarPolicy.leadingActions(history = false),
        )
    }

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
