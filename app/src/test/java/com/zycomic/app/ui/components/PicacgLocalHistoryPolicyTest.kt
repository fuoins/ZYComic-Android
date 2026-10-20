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

    private fun historyEntry(id: Long, sourceId: Long) = HistoryWithRelations(
        id = id,
        chapterId = id,
        mangaId = id,
        ogTitle = "Manga $id",
        chapterNumber = 1.0,
        read = false,
        lastPageRead = 0,
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
