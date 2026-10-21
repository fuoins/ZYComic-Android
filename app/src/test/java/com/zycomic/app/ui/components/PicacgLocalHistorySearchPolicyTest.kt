package com.zycomic.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class PicacgLocalHistorySearchPolicyTest {
    @Test
    fun `search matches title author category or tag case insensitively`() {
        assertTrue(matches("  STAR ", title = "Star Journey"))
        assertTrue(matches("artist", author = "Some Artist"))
        assertTrue(matches("slice", categories = listOf("Slice of Life")))
        assertTrue(matches("mystery", tags = listOf("Mystery")))
        assertFalse(matches("not here"))
        assertTrue(matches("   "))
    }

    @Test
    fun `history is ordered by latest read time descending`() {
        val older = historyEntry(id = 1, timestamp = 10_000)
        val newer = historyEntry(id = 2, timestamp = 30_000)
        val noTimestamp = historyEntry(id = 3, timestamp = null)
        assertEquals(listOf(newer, older, noTimestamp), PicacgLocalHistoryPolicy.newestFirst(listOf(older, noTimestamp, newer)))
    }

    @Test
    fun `select all returns only the visible identifiers`() {
        assertEquals(setOf(10L, 12L), PicacgLocalHistoryPolicy.allVisibleIds(listOf(10L, 12L, 10L)))
    }

    private fun matches(
        query: String,
        title: String = "",
        author: String = "",
        categories: List<String> = emptyList(),
        tags: List<String> = emptyList(),
    ) = PicacgLocalHistoryPolicy.matchesSearch(query, title, author, categories, tags)

    private fun historyEntry(id: Long, timestamp: Long?) = HistoryWithRelations(
        id = id,
        chapterId = id,
        mangaId = id,
        ogTitle = "Manga $id",
        chapterNumber = 1.0,
        read = false,
        lastPageRead = 0,
        totalCountCalculated = 1,
        readCountCalculated = 0,
        readAt = timestamp?.let(::Date),
        readDuration = 0,
        coverData = MangaCover(
            mangaId = id,
            sourceId = PICACG_SOURCE_ID,
            isMangaFavorite = false,
            ogUrl = null,
            lastModified = 0,
        ),
    )

    private companion object {
        const val PICACG_SOURCE_ID = 300L
    }
}
