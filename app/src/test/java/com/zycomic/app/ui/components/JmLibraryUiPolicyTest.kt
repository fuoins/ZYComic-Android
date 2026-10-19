package com.zycomic.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmLibraryUiPolicyTest {
    @Test
    fun `JM selection toggle select all and loaded inversion match Browse semantics`() {
        assertEquals(setOf("1", "2"), JmLibrarySelectionPolicy.selectAll(listOf("1", "2", "2")))
        assertEquals(setOf("2"), JmLibrarySelectionPolicy.invertLoaded(setOf("1", "stale"), listOf("1", "2")))
        assertEquals(setOf("1", "2"), JmLibrarySelectionPolicy.toggle(setOf("1"), "2"))
        assertEquals(emptySet<String>(), JmLibrarySelectionPolicy.toggle(setOf("1"), "1"))
        assertEquals(emptySet<String>(), JmLibrarySelectionPolicy.selectAll(emptyList()))
    }

    @Test
    fun `JM search matches only locally supplied name and author values`() {
        assertTrue(JmFavoriteLibraryUiPolicy.matchesLocalSearch("  NIGHT ", "Night Market", "A. Writer"))
        assertTrue(JmFavoriteLibraryUiPolicy.matchesLocalSearch("writer", "Other title", "A. Writer"))
        assertTrue(JmFavoriteLibraryUiPolicy.matchesLocalSearch("  ", "Any title", "Any author"))
        assertFalse(JmFavoriteLibraryUiPolicy.matchesLocalSearch("missing", "Night Market", "A. Writer"))
    }

    @Test
    fun `expanded search removes only the search action from toolbar`() {
        assertEquals(
            listOf(LibraryHistoryToolbarPolicy.Action.REFRESH, LibraryHistoryToolbarPolicy.Action.FILTER),
            LibraryHistoryToolbarPolicy.visibleActions(history = false, searchExpanded = true),
        )
        assertEquals(
            listOf(LibraryHistoryToolbarPolicy.Action.EMPTY, LibraryHistoryToolbarPolicy.Action.REFRESH),
            LibraryHistoryToolbarPolicy.visibleActions(history = true, searchExpanded = true),
        )
        assertTrue(LibraryHistoryToolbarPolicy.visibleActions(history = true, searchExpanded = false).contains(LibraryHistoryToolbarPolicy.Action.SEARCH))
    }

    @Test
    fun `expanded JM search hides actions and platform selector while other platforms retain approved actions`() {
        assertEquals(emptyList<LibraryHistoryToolbarPolicy.Action>(), LibraryHistoryToolbarPolicy.visibleActions(history = false, searchExpanded = true, jmPlatform = true))
        assertEquals(emptyList<LibraryHistoryToolbarPolicy.Action>(), LibraryHistoryToolbarPolicy.visibleActions(history = true, searchExpanded = true, jmPlatform = true))
        assertFalse(LibraryHistoryToolbarPolicy.showPlatformSelector(searchExpanded = true, jmPlatform = true))
        assertEquals(
            listOf(LibraryHistoryToolbarPolicy.Action.REFRESH, LibraryHistoryToolbarPolicy.Action.FILTER),
            LibraryHistoryToolbarPolicy.visibleActions(history = false, searchExpanded = true),
        )
        assertTrue(LibraryHistoryToolbarPolicy.showPlatformSelector(searchExpanded = true, jmPlatform = false))
        assertTrue(LibraryHistoryToolbarPolicy.showPlatformSelector(searchExpanded = false, jmPlatform = true))
    }
}
