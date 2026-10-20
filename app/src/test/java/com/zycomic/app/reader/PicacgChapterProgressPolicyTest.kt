package com.zycomic.app.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PicacgChapterProgressPolicyTest {
    @Test
    fun `chapter order belongs to a specific comic and cannot collide with JM chapter urls`() {
        assertEquals("/chapter/comic1/2", PicacgChapterProgressPolicy.chapterUrl("comic1", 2))
        assertNotEquals(PicacgChapterProgressPolicy.chapterUrl("comic1", 2), PicacgChapterProgressPolicy.chapterUrl("comic2", 2))
        assertNotEquals("/chapter/2", PicacgChapterProgressPolicy.chapterUrl("comic1", 2))
    }

    @Test
    fun `reopening preserves database row and read progress`() {
        val saved = PicacgSavedChapterProgress(id = 55L, read = true, lastPageRead = 26L)
        assertEquals(saved, PicacgChapterProgressPolicy.preserve("/chapter/book/1", mapOf("/chapter/book/1" to saved)))
    }

    @Test
    fun `new comics and chapters do not borrow another chapter progress`() {
        val other = PicacgSavedChapterProgress(id = 55L, read = true, lastPageRead = 26L)
        assertEquals(PicacgSavedChapterProgress(), PicacgChapterProgressPolicy.preserve("/chapter/new/1", mapOf("/chapter/old/1" to other)))
    }

    @Test
    fun `negative legacy page index is sanitized without changing identity`() {
        assertEquals(
            PicacgSavedChapterProgress(id = 12L, lastPageRead = 0L),
            PicacgChapterProgressPolicy.preserve("/chapter/book/1", mapOf("/chapter/book/1" to PicacgSavedChapterProgress(id = 12L, lastPageRead = -1L))),
        )
    }

    @Test
    fun `invalid order and identifiers cannot create malformed chapter paths`() {
        assertThrows(IllegalArgumentException::class.java) { PicacgChapterProgressPolicy.chapterUrl("book", 0) }
        assertThrows(IllegalArgumentException::class.java) { PicacgChapterProgressPolicy.chapterUrl("book/other", 1) }
        assertThrows(IllegalArgumentException::class.java) { PicacgChapterProgressPolicy.chapterUrl("book?x=1", 1) }
    }
}
