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
    fun `expanded search hides toolbar actions and restoring search restores actions`() {
        assertEquals(
            emptyList<LibraryHistoryToolbarPolicy.Action>(),
            LibraryHistoryToolbarPolicy.visibleActions(history = false, searchExpanded = true),
        )
        assertEquals(
            emptyList<LibraryHistoryToolbarPolicy.Action>(),
            LibraryHistoryToolbarPolicy.visibleActions(history = true, searchExpanded = true),
        )
        assertEquals(
            listOf(LibraryHistoryToolbarPolicy.Action.SEARCH, LibraryHistoryToolbarPolicy.Action.REFRESH, LibraryHistoryToolbarPolicy.Action.FILTER),
            LibraryHistoryToolbarPolicy.visibleActions(history = false, searchExpanded = false),
        )
        assertTrue(LibraryHistoryToolbarPolicy.visibleActions(history = true, searchExpanded = false).contains(LibraryHistoryToolbarPolicy.Action.SEARCH))
    }

    @Test
    fun `expanded search hides platform selector and collapse restores it on either platform`() {
        assertFalse(LibraryHistoryToolbarPolicy.showPlatformSelector(searchExpanded = true))
        assertTrue(LibraryHistoryToolbarPolicy.showPlatformSelector(searchExpanded = false))
    }
}
