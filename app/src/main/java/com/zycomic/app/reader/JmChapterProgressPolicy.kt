package com.zycomic.app.reader

import com.zycomic.app.data.dto.Chapter

internal data class JmChapterProgress(
    val chapterId: String,
    val read: Boolean,
    val lastPageRead: Long,
    val lastReadAt: Long? = null,
)

internal data class JmSavedChapterProgress(
    val read: Boolean,
    val lastPageRead: Long,
)

/** Pure policy for mapping the existing reader records back onto JM detail chapters. */
internal object JmChapterProgressPolicy {
    fun existingChapterId(url: String, existingIdsByUrl: Map<String, Long>): Long? = existingIdsByUrl[url]

    fun chapterIdFromUrl(url: String): String? = url
        .takeIf { it.startsWith("/chapter/") }
        ?.removePrefix("/chapter/")
        ?.takeIf { id -> id.isNotBlank() && id.all { it in '0'..'9' } }

    fun preserveProgress(
        serverRead: Boolean,
        saved: JmSavedChapterProgress?,
    ): JmSavedChapterProgress =
        JmSavedChapterProgress(
            read = serverRead || saved?.read == true,
            lastPageRead = saved?.lastPageRead?.coerceAtLeast(0) ?: 0,
        )

    fun resumeChapterId(chapters: List<Chapter>, recentChapterId: String?, serverStart: String): String {
        val chapterIds = chapters.asSequence().map { it.id }.toSet()
        return recentChapterId?.takeIf { it in chapterIds }
            ?: serverStart.takeIf { it in chapterIds }
            ?: chapters.minByOrNull { it.sort }?.id
            ?: chapters.firstOrNull()?.id.orEmpty()
    }

    fun pageForChapter(chapterId: String, progress: List<JmChapterProgress>): Int {
        val saved = progress.firstOrNull { it.chapterId == chapterId } ?: return 0
        return saved.lastPageRead.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    fun recentChapterId(progress: List<JmChapterProgress>): String? = progress
        .asSequence()
        .filter { it.lastReadAt != null }
        .maxByOrNull { it.lastReadAt ?: Long.MIN_VALUE }
        ?.chapterId
}
