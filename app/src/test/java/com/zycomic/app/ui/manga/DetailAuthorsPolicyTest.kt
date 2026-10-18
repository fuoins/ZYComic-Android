package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailAuthorsPolicyTest {
    private val authors = listOf("作者甲", "作者乙", "作者丙")

    @Test
    fun `collapsed detail shows only the first author and expansion retains every author`() {
        assertEquals(listOf("作者甲"), detailAuthorsForDisplay(authors, expanded = false))
        assertEquals(authors, detailAuthorsForDisplay(authors, expanded = true))
        assertEquals(emptyList<String>(), detailAuthorsForDisplay(emptyList(), expanded = false))
    }

    @Test
    fun `author note is shown only while a JM detail is expanded`() {
        assertFalse(shouldShowJmAuthorNote(isJm = false, expanded = true))
        assertFalse(shouldShowJmAuthorNote(isJm = true, expanded = false))
        assertTrue(shouldShowJmAuthorNote(isJm = true, expanded = true))
    }
}
