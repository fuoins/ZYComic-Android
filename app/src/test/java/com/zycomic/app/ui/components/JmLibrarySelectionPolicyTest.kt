package com.zycomic.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JmLibrarySelectionPolicyTest {
    @Test
    fun `toggle selects and deselects a single album`() {
        val selected = JmLibrarySelectionPolicy.toggle(emptySet(), "10")
        assertEquals(setOf("10"), selected)
        assertEquals(emptySet<String>(), JmLibrarySelectionPolicy.toggle(selected, "10"))
    }

    @Test
    fun `select all uses only the current loaded filtered albums`() {
        assertEquals(setOf("10", "20"), JmLibrarySelectionPolicy.selectAll(listOf("10", "20", "10")))
        assertEquals(emptySet<String>(), JmLibrarySelectionPolicy.selectAll(emptyList()))
    }

    @Test
    fun `invert preserves selections outside the current filter`() {
        assertEquals(setOf("30", "20"), JmLibrarySelectionPolicy.invert(setOf("10", "30"), listOf("10", "20")))
        assertEquals(setOf("30"), JmLibrarySelectionPolicy.invert(setOf("30"), emptyList()))
    }

    @Test
    fun `inverting twice restores the original selection even with duplicate ids`() {
        val original = setOf("10", "30")
        val visible = listOf("10", "20", "20")
        val inverted = JmLibrarySelectionPolicy.invert(original, visible)
        assertEquals(setOf("20", "30"), inverted)
        assertEquals(original, JmLibrarySelectionPolicy.invert(inverted, visible))
    }
}
