package com.zycomic.app.ui.picacg

import com.zycomic.app.data.dto.Manga
import com.zycomic.app.reader.PicacgChapterProgressPolicy
import com.zycomic.app.reader.PicacgSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal data class PicacgDetailReadingTarget(val chapterId: String, val page: Int = 0)

/** Resolve against the current directory, keeping all progress in the existing reader database. */
internal suspend fun picacgDetailReadingTarget(detail: Manga): PicacgDetailReadingTarget? = withContext(Dispatchers.IO) {
    val firstChapter = detail.chapterList.minByOrNull { it.sort } ?: return@withContext null
    val fallback = PicacgDetailReadingTarget(firstChapter.id)
    val localManga = Injekt.get<MangaRepository>()
        .getMangaByUrlAndSourceId("/comic/${detail.id}", PicacgSource.id) ?: return@withContext fallback
    val chapters = Injekt.get<ChapterRepository>().getChapterByMangaId(localManga.id).associateBy { it.id }
    val available = detail.chapterList.associateBy { PicacgChapterProgressPolicy.chapterUrl(detail.id, it.sort) }
    val history = Injekt.get<HistoryRepository>().getHistoryByMangaId(localManga.id)
    val recent = history.asSequence().filter { it.readAt != null }.sortedByDescending { it.readAt?.time }
        .mapNotNull { record -> chapters[record.chapterId]?.takeIf { it.url in available } }.firstOrNull()
        ?: return@withContext fallback
    PicacgDetailReadingTarget(
        chapterId = available.getValue(recent.url).id,
        page = recent.lastPageRead.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    )
}
