package com.zycomic.app.picacg

import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.dto.TagItem
import com.zycomic.app.picacg.filter.PicacgKeywordCandidate
import com.zycomic.app.picacg.filter.PicacgKeywordFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class PicacgKeywordFilterTest {
    @Test
    fun `normalization trims lowercases with root locale and rejects invalid values`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i", PicacgKeywordFilter.normalize("  I  "))
            assertNull(PicacgKeywordFilter.normalize(" \t "))
            assertNull(PicacgKeywordFilter.normalize("x".repeat(51)))
            assertEquals("x".repeat(50), PicacgKeywordFilter.normalize("x".repeat(50)))
            assertNull(PicacgKeywordFilter.add(listOf("BL"), " bl "))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `add and remove keep normalized values unique`() {
        assertEquals(listOf("first", "second"), PicacgKeywordFilter.addAll(listOf(" FIRST "), listOf("first", "Second", "")))
        assertEquals(listOf("first"), PicacgKeywordFilter.remove(listOf("first", "second", "SECOND"), " second "))
    }

    @Test
    fun `four documented preset groups expose every documented entry`() {
        val groups = PicacgKeywordFilter.presetGroups
        assertEquals(listOf("bl_femboy", "ai_art", "ntr", "heavy"), groups.map { it.id })
        assertEquals(10, groups[0].keywords.size)
        assertEquals(23, groups[1].keywords.size)
        assertEquals(9, groups[2].keywords.size)
        assertEquals(5, groups[3].keywords.size)
        assertTrue(groups[1].keywords.containsAll(listOf("AI作畫", "Stable Diffusion", "（AI）", "AI塗色")))
    }

    @Test
    fun `matching is case insensitive substring across title author tags and categories only`() {
        val keys = listOf("example", "artist", "dark", "genre")
        assertTrue(PicacgKeywordFilter.isBlocked(PicacgKeywordCandidate("an Example title", "", emptyList()), keys))
        assertTrue(PicacgKeywordFilter.isBlocked(PicacgKeywordCandidate("clean", "ARTIST42", emptyList()), keys))
        assertTrue(PicacgKeywordFilter.isBlocked(PicacgKeywordCandidate("clean", "", listOf("darkness")), keys))
        assertTrue(PicacgKeywordFilter.isBlocked(PicacgKeywordCandidate("clean", "", emptyList(), listOf("subgenre")), keys))
        assertFalse(PicacgKeywordFilter.isBlocked(PicacgKeywordCandidate("clean title", "author", listOf("unrelated")), keys))
        assertFalse(PicacgKeywordFilter.isBlocked(PicacgKeywordCandidate("clean", "", listOf("xEXAMPLEy")), listOf("not-present")))
    }

    @Test
    fun `Manga adapter uses title author and Manga tags without description`() {
        val manga = Manga(name = "ordinary title", author = "author", text = "blocked-description", tags = listOf(TagItem("visible tag")))
        assertFalse(PicacgKeywordFilter.isBlocked(manga, listOf("blocked-description")))
        assertTrue(PicacgKeywordFilter.isBlocked(manga, listOf("visible")))
        assertTrue(PicacgKeywordFilter.isBlocked(manga, listOf("thor")))
    }
}
