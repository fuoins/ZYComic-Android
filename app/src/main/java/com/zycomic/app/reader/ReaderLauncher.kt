package com.zycomic.app.reader

import android.content.Context
import com.zycomic.app.data.dto.Manga as MangaDto
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter as DomainChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga as DomainManga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 阅读器启动器：把漫画/章节插入 komikku 数据库，然后以 Activity 方式启动 [ReaderActivity]。
 *
 * 阅读器本身通过 mangaId/chapterId 从数据库加载，因此启动前必须先落库。
 */
object ReaderLauncher {

    /**
     * @param mangaDto 漫画详情 DTO（含 chapter_list）
     * @param chapterId 要打开的章节 id（对应 DTO 章节 id）
     * @param page 起始页（0 基）
     */
    suspend fun launch(
        context: Context,
        mangaDto: MangaDto,
        chapterId: String,
        page: Int = 0,
    ) = withContext(Dispatchers.IO) {
        val mangaRepository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()

        // 1. 构造 komikku Manga 并插入（upsert by url+source），拿到数据库 mangaId
        val domainManga = DomainManga.create().copy(
            source = ManwaSource.id,
            url = "/manga/${mangaDto.id}",
            ogTitle = mangaDto.name,
            ogAuthor = mangaDto.author.joinToString(", "),
            ogThumbnailUrl = ManwaSource.coverUrl(mangaDto).ifBlank { null },
            ogDescription = mangaDto.text.ifBlank { null },
            ogGenre = mangaDto.tags.map { it.name },
            ogStatus = if (mangaDto.end == 1) SManga.COMPLETED.toLong() else SManga.ONGOING.toLong(),
            initialized = true,
            // 强制 Webtoon 模式
            viewerFlags = ReadingMode.WEBTOON.flagValue.toLong(),
            dateAdded = System.currentTimeMillis(),
            lastUpdate = System.currentTimeMillis(),
        )
        val insertedManga = mangaRepository.insertNetworkManga(listOf(domainManga)).first()

        // 2. 构造章节列表并插入
        val domainChapters = mangaDto.chapterList.map { ch ->
            DomainChapter.create().copy(
                mangaId = insertedManga.id,
                url = "/chapter/${ch.id}",
                name = ch.name,
                dateUpload = ManwaSource.parseAddtime(ch.addtime),
                sourceOrder = ch.sort.toLong(),
            )
        }
        chapterRepository.addAll(domainChapters)

        // 3. 找到目标章节的数据库 id
        val targetUrl = "/chapter/$chapterId"
        val targetChapter = chapterRepository.getChapterByUrlAndMangaId(targetUrl, insertedManga.id)
            ?: error("目标章节未入库: $targetUrl")

        // 4. 启动阅读器 Activity
        val intent = ReaderActivity.newIntent(context, insertedManga.id, targetChapter.id, page)
        context.startActivity(intent)
    }
}
