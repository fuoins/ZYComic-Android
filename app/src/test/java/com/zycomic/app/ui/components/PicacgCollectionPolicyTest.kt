package com.zycomic.app.ui.components

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgCollectionPolicyTest {
    @Test
    fun `favorite sort codes retain the confirmed meanings and invalid values fall back to recent favorites`() {
        assertEquals(listOf("dd", "da", "ld", "vd"), PicacgCollectionPolicy.sortCodes)
        assertEquals("dd", PicacgCollectionPolicy.normalizeSort("dd"))
        assertEquals("da", PicacgCollectionPolicy.normalizeSort("da"))
        assertEquals(PicacgCollectionPolicy.DEFAULT_SORT, PicacgCollectionPolicy.normalizeSort("publishedAt"))
    }

    @Test
    fun `favorite display preferences are dedicated and grid columns are bounded`() {
        assertEquals("picacg_favorites_display_mode", PicacgCollectionPolicy.displayPreferenceKey())
        assertEquals("picacg_favorites_grid_columns", PicacgCollectionPolicy.gridColumnsPreferenceKey())
        assertFalse(PicacgCollectionPolicy.displayPreferenceKey().contains("category"))
        assertEquals(PicacgCollectionPolicy.DISPLAY_GRID, PicacgCollectionPolicy.DEFAULT_FAVORITES_DISPLAY)
        assertEquals(PicacgCollectionPolicy.DISPLAY_GRID, PicacgCollectionPolicy.normalizeDisplayMode(-1))
        assertEquals(PicacgCollectionPolicy.DISPLAY_LIST, PicacgCollectionPolicy.normalizeDisplayMode(99))
        assertEquals(2, PicacgCollectionPolicy.normalizeGridColumns(1))
        assertEquals(3, PicacgCollectionPolicy.normalizeGridColumns(3))
        assertEquals(5, PicacgCollectionPolicy.normalizeGridColumns(9))
    }

    @Test
    fun `only online favorites require an account and local history stays available signed out`() {
        assertTrue(PicacgCollectionPolicy.requiresLogin(history = false, loggedIn = false))
        assertFalse(PicacgCollectionPolicy.requiresLogin(history = true, loggedIn = false))
        assertFalse(PicacgCollectionPolicy.requiresLogin(history = false, loggedIn = true))
    }

    @Test
    fun `accepts only results from an authenticated unchanged account session`() {
        assertTrue(PicacgCollectionPolicy.acceptsResult(3, 3, loggedIn = true))
        assertFalse(PicacgCollectionPolicy.acceptsResult(3, 4, loggedIn = true))
        assertFalse(PicacgCollectionPolicy.acceptsResult(3, 3, loggedIn = false))
    }

    @Test
    fun `first page replaces old account data instead of appending it`() {
        assertEquals(listOf("new"), PicacgCollectionPolicy.merge(listOf("old"), listOf("new"), true) { it })
        assertEquals(emptyList<String>(), PicacgCollectionPolicy.merge(listOf("old"), emptyList(), true) { it })
    }

    @Test
    fun `subsequent pages preserve order and remove duplicated comic identifiers`() {
        assertEquals(listOf("a", "b", "c"), PicacgCollectionPolicy.merge(listOf("a", "b"), listOf("b", "c"), false) { it })
    }

    @Test
    fun `search trims input and never requires a different platform repository`() {
        assertTrue(PicacgCollectionPolicy.matchesSearch("  NIGHT ", "Night Market"))
        assertTrue(PicacgCollectionPolicy.matchesSearch(null, "Any title"))
        assertTrue(PicacgCollectionPolicy.matchesSearch("  ", "Any title"))
        assertFalse(PicacgCollectionPolicy.matchesSearch("missing", "Night Market"))
    }

    @Test
    fun `bulk removal checks session before every toggle`() {
        var sessionActive = true
        var sessionChecks = 0
        val removed = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                PicacgCollectionPolicy.removeSequentially(
                    ids = listOf("first", "second"),
                    ensureSession = {
                        sessionChecks++
                        check(sessionActive)
                    },
                    remove = { id ->
                        removed += id
                        sessionActive = false
                    },
                )
            }
        }

        assertEquals(listOf("first"), removed)
        assertEquals(2, sessionChecks)
    }

    @Test
    fun `uncertain toggle failure is propagated without automatic retry`() {
        var toggleAttempts = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                PicacgCollectionPolicy.removeSequentially(
                    ids = listOf("uncertain", "later"),
                    ensureSession = {},
                    remove = {
                        toggleAttempts++
                        error("request outcome is uncertain")
                    },
                )
            }
        }

        assertEquals(1, toggleAttempts)
    }
}
