package com.zycomic.app.reader

import com.zycomic.app.data.dto.Chapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JmChapterProgressPolicyTest {
    private val chapters = listOf(
        Chapter(id = "11", sort = 1),
        Chapter(id = "22", sort = 2),
    )

    @Test
    fun `latest local history chapter takes precedence over server start`() {
        val progress = listOf(
            JmChapterProgress(chapterId = "11", read = false, lastPageRead = 3, lastReadAt = 100L),
            JmChapterProgress(chapterId = "22", read = false, lastPageRead = 7, lastReadAt = 200L),
        )

        assertEquals(
            "22",
            JmChapterProgressPolicy.resumeChapterId(chapters, JmChapterProgressPolicy.recentChapterId(progress), "11"),
        )
        assertEquals(7, JmChapterProgressPolicy.pageForChapter("22", progress))
    }

    @Test
    fun `invalid local history falls back to a valid server start then first chapter`() {
        assertEquals("11", JmChapterProgressPolicy.resumeChapterId(chapters, "missing", "11"))
        assertEquals("11", JmChapterProgressPolicy.resumeChapterId(chapters, "missing", "missing"))
    }

    @Test
    fun `JM row rebuild preserves local page and read status without regressing server read state`() {
        val saved = JmSavedChapterProgress(read = false, lastPageRead = 9)

        assertEquals(saved, JmChapterProgressPolicy.preserveProgress(serverRead = false, saved = saved))
        assertEquals(
            JmSavedChapterProgress(read = true, lastPageRead = 9),
            JmChapterProgressPolicy.preserveProgress(serverRead = true, saved = saved),
        )
        assertEquals(
            JmSavedChapterProgress(read = true, lastPageRead = 14),
            JmChapterProgressPolicy.preserveProgress(
                serverRead = false,
                saved = JmSavedChapterProgress(read = true, lastPageRead = 14),
            ),
        )
        assertEquals(
            JmSavedChapterProgress(read = true, lastPageRead = 0),
            JmChapterProgressPolicy.preserveProgress(serverRead = true, saved = null),
        )
    }

    @Test
    fun `completed chapters preserve saved page and chapter URL maps to the JM id`() {
        val progress = listOf(JmChapterProgress(chapterId = "22", read = true, lastPageRead = 14))

        assertEquals(14, JmChapterProgressPolicy.pageForChapter("22", progress))
        assertEquals("22", JmChapterProgressPolicy.chapterIdFromUrl("/chapter/22"))
        assertEquals(null, JmChapterProgressPolicy.chapterIdFromUrl("/chapter/not-a-number"))
        assertEquals(456L, JmChapterProgressPolicy.existingChapterId("/chapter/22", mapOf("/chapter/22" to 456L)))
        assertEquals(null, JmChapterProgressPolicy.existingChapterId("/chapter/33", mapOf("/chapter/22" to 456L)))
    }
}
