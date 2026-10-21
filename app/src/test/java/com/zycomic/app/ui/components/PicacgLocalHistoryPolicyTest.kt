package com.zycomic.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover

class PicacgLocalHistoryPolicyTest {
    @Test
    fun `remote identity is parsed from source URL never from local database id`() {
        assertEquals("remote123", PicacgLocalHistoryPolicy.comicId("/comic/remote123", PICACG_SOURCE_ID, PICACG_SOURCE_ID))
        assertEquals(null, PicacgLocalHistoryPolicy.comicId("/album/123", PICACG_SOURCE_ID, PICACG_SOURCE_ID))
        assertEquals(null, PicacgLocalHistoryPolicy.comicId("/comic/remote123", 100L, PICACG_SOURCE_ID))
    }

    @Test
    fun `filters strictly by source id`() {
        val picacgEntry = historyEntry(id = 1L, sourceId = PICACG_SOURCE_ID)
        val manwaEntry = historyEntry(id = 2L, sourceId = 100L)
        val jmEntry = historyEntry(id = 3L, sourceId = 200L)

        val filtered = PicacgLocalHistoryPolicy.onlyPicacg(
            listOf(manwaEntry, picacgEntry, jmEntry),
            PICACG_SOURCE_ID,
        )

        assertEquals(listOf(picacgEntry), filtered)
    }

    @Test
    fun `returns empty when there are no entries from Picacg`() {
        val filtered = PicacgLocalHistoryPolicy.onlyPicacg(
            listOf(historyEntry(id = 1L, sourceId = 100L)),
            PICACG_SOURCE_ID,
        )

        assertEquals(emptyList<HistoryWithRelations>(), filtered)
    }

    @Test
    fun `keeps the saved last page and exposes a user-facing one-based page number`() {
        val entry = historyEntry(id = 1L, sourceId = PICACG_SOURCE_ID, lastPageRead = 8L)

        val filtered = PicacgLocalHistoryPolicy.onlyPicacg(listOf(entry), PICACG_SOURCE_ID)

        assertEquals(8L, filtered.single().lastPageRead)
        assertEquals(9L, PicacgLocalHistoryPolicy.lastReadPageNumber(filtered.single().lastPageRead))
        assertEquals(1L, PicacgLocalHistoryPolicy.lastReadPageNumber(-1L))
    }

    @Test
    fun `selection toggle removes a long-pressed row when it is tapped again`() {
        val selectedAfterLongPress = PicacgLocalHistoryPolicy.toggleSelection(emptySet(), 12L)

        assertEquals(setOf(12L), selectedAfterLongPress)
        assertEquals(emptySet<Long>(), PicacgLocalHistoryPolicy.toggleSelection(selectedAfterLongPress, 12L))
    }

    private fun historyEntry(id: Long, sourceId: Long, lastPageRead: Long = 0L) = HistoryWithRelations(
        id = id,
        chapterId = id,
        mangaId = id,
        ogTitle = "Manga $id",
        chapterNumber = 1.0,
        read = false,
        lastPageRead = lastPageRead,
        totalCountCalculated = 1,
        readCountCalculated = 0,
        readAt = null,
        readDuration = 0,
        coverData = MangaCover(
            mangaId = id,
            sourceId = sourceId,
            isMangaFavorite = false,
            ogUrl = null,
            lastModified = 0,
        ),
    )

    private companion object {
        const val PICACG_SOURCE_ID = 300L
    }
}
