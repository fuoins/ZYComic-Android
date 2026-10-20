package com.zycomic.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgCollectionPolicyTest {
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
}
