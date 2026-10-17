package com.zycomic.app.reader

import android.content.Context
import android.util.Log
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
 * 每次启动前先删除该 manga 的旧章节，避免重复插入导致 getChapterByUrlAndMangaId 返回多行。
 */
object ReaderLauncher {

    private const val TAG = "ReaderLauncher"

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
        sourceId: Long = ManwaSource.id,
    ) = withContext(Dispatchers.IO) {
        try {
            val mangaRepository = Injekt.get<MangaRepository>()
            val chapterRepository = Injekt.get<ChapterRepository>()

            // 1. 构造 komikku Manga 并插入（upsert by url+source），拿到数据库 mangaId
            val isJm = sourceId == JmSource.id
            val mangaPath = if (isJm) "/album/${mangaDto.id}" else "/manga/${mangaDto.id}"
            val domainManga = DomainManga.create().copy(
                source = sourceId,
                url = mangaPath,
                ogTitle = mangaDto.name,
                ogAuthor = mangaDto.author,
                ogThumbnailUrl = if (isJm) mangaDto.picx.takeIf { it.isNotBlank() } else ManwaSource.coverUrl(mangaDto).takeIf { it.isNotBlank() },
                ogDescription = mangaDto.text.takeIf { it.isNotBlank() },
                ogGenre = mangaDto.tags.map { it.name },
                ogStatus = when {
                    mangaDto.state.contains("完") || mangaDto.end.contains("完") -> SManga.COMPLETED.toLong()
                    mangaDto.state.contains("连载") || mangaDto.end.contains("连载") -> SManga.ONGOING.toLong()
                    else -> SManga.UNKNOWN.toLong()
                },
                // JM 详情只确认了 name/series；保持未初始化，避免把缺失字段缓存为完整详情。
                initialized = !isJm,
                // 强制 Webtoon 模式
                viewerFlags = ReadingMode.WEBTOON.flagValue.toLong(),
                dateAdded = System.currentTimeMillis(),
                lastUpdate = System.currentTimeMillis(),
            )
            val insertedManga = mangaRepository.insertNetworkManga(listOf(domainManga)).first()

            // 2. 先删除该 manga 的旧章节（避免重复插入导致查询返回多行）
            val oldChapters = chapterRepository.getChapterByMangaId(insertedManga.id)
            if (oldChapters.isNotEmpty()) {
                chapterRepository.removeChaptersWithIds(oldChapters.map { it.id })
            }

            // 3. 构造章节列表并插入
            val domainChapters = mangaDto.chapterList.map { ch ->
                DomainChapter.create().copy(
                    mangaId = insertedManga.id,
                    url = "/chapter/${ch.id}",
                    name = ch.name,
                    dateUpload = if (isJm) 0L else ManwaSource.parseAddtime(ch.addtime),
                    sourceOrder = ch.sort.toLong(),
                    chapterNumber = ch.sort.toDouble(),
                    read = ch.readed == 1,
                )
            }
            chapterRepository.addAll(domainChapters)

            // 4. 找到目标章节的数据库 id
            val targetUrl = "/chapter/$chapterId"
            val targetChapter = chapterRepository.getChapterByUrlAndMangaId(targetUrl, insertedManga.id)
                ?: error("目标章节未入库: $targetUrl (已插入 ${domainChapters.size} 章)")

            // 5. 启动阅读器 Activity
            val intent = ReaderActivity.newIntent(context, insertedManga.id, targetChapter.id, page)
            context.startActivity(intent)
        } catch (e: Exception) {
            // 打印完整堆栈到 Logcat，方便排查
            Log.e(TAG, "打开阅读器失败", e)
            throw e
        }
    }
}
