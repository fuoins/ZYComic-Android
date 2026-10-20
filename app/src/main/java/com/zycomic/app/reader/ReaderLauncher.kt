package com.zycomic.app.reader

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import com.zycomic.app.data.dto.Manga as MangaDto
import tachiyomi.domain.chapter.model.Chapter as DomainChapter
import tachiyomi.domain.manga.model.Manga as DomainManga

/**
 * 阅读器启动器：把漫画/章节插入 komikku 数据库，然后以 Activity 方式启动 [ReaderActivity]。
 *
 * 阅读器本身通过 mangaId/chapterId 从数据库加载，因此启动前必须先落库。
 * JM 通过章节 URL 复用数据库行，避免删除章节时级联清除阅读历史；Manwa 沿用删除重插。
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
            val isJm = sourceId == JmSource.id
            val isPicacg = sourceId == PicacgSource.id
            if (isJm) {
                require(mangaDto.chapterList.isNotEmpty()) { "JM 暂无可读章节，请重新加载详情" }
                require(mangaDto.chapterList.all { it.id.matches(Regex("[0-9]+")) && it.id.any { digit -> digit != '0' } }) {
                    "JM 章节 ID 异常，请重新加载详情"
                }
                require(mangaDto.chapterList.any { it.id == chapterId }) { "JM 目标章节不存在，请重新加载详情" }
            }
            val picacgChapter = if (isPicacg) {
                mangaDto.chapterList.firstOrNull { it.id == chapterId || it.sort.toString() == chapterId }
                    ?: error("Picacg 目标章节不存在，请重新加载详情")
            } else {
                null
            }
            if (isPicacg) {
                require(mangaDto.chapterList.isNotEmpty() && mangaDto.chapterList.all { it.sort > 0 }) {
                    "Picacg 章节顺序无效，请重新加载详情"
                }
                require(mangaDto.chapterList.map { it.sort }.distinct().size == mangaDto.chapterList.size) {
                    "Picacg 章节顺序重复，请重新加载详情"
                }
            }
            val mangaRepository = Injekt.get<MangaRepository>()
            val chapterRepository = Injekt.get<ChapterRepository>()

            // 1. 构造 komikku Manga 并插入（upsert by url+source），拿到数据库 mangaId
            val mangaPath = when {
                isJm -> "/album/${mangaDto.id}"
                isPicacg -> "/comic/${mangaDto.id}"
                else -> "/manga/${mangaDto.id}"
            }
            val domainManga = DomainManga.create().copy(
                source = sourceId,
                url = mangaPath,
                ogTitle = mangaDto.name,
                ogAuthor = mangaDto.author,
                ogThumbnailUrl = when {
                    isJm || isPicacg -> mangaDto.picx.takeIf { it.isNotBlank() }
                    else -> ManwaSource.coverUrl(mangaDto).takeIf { it.isNotBlank() }
                },
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

            // 2. JM/Picacg update stable chapter rows; Manwa retains its existing replacement behavior.
            val oldChapters = chapterRepository.getChapterByMangaId(insertedManga.id)
            val picacgSavedProgress = if (isPicacg) {
                oldChapters.associate { it.url to PicacgSavedChapterProgress(it.id, it.read, it.lastPageRead) }
            } else {
                emptyMap()
            }
            val jmExistingIdsByUrl = if (isJm) oldChapters.associate { it.url to it.id } else emptyMap()
            val jmSavedProgress = if (isJm) {
                oldChapters.mapNotNull { chapter ->
                    JmChapterProgressPolicy.chapterIdFromUrl(chapter.url)?.let { chapterId ->
                        chapterId to JmSavedChapterProgress(chapter.read, chapter.lastPageRead)
                    }
                }.toMap()
            } else {
                emptyMap()
            }
            if (!isJm && !isPicacg && oldChapters.isNotEmpty()) {
                chapterRepository.removeChaptersWithIds(oldChapters.map { it.id })
            }

            // 3. Existing Picacg row IDs preserve history references and local progress across launches.
            val domainChapters = mangaDto.chapterList.map { ch ->
                val picacgUrl = if (isPicacg) PicacgChapterProgressPolicy.chapterUrl(mangaDto.id, ch.sort) else null
                val picacgProgress = picacgUrl?.let { PicacgChapterProgressPolicy.preserve(it, picacgSavedProgress) }
                val savedProgress = jmSavedProgress[ch.id]
                val jmProgress = if (isJm) {
                    JmChapterProgressPolicy.preserveProgress(ch.readed == 1, savedProgress)
                } else {
                    null
                }
                DomainChapter.create().copy(
                    id = if (isJm) {
                        JmChapterProgressPolicy.existingChapterId("/chapter/${ch.id}", jmExistingIdsByUrl) ?: -1
                    } else {
                        picacgProgress?.id ?: -1
                    },
                    mangaId = insertedManga.id,
                    url = picacgUrl ?: "/chapter/${ch.id}",
                    name = ch.name,
                    dateUpload = if (isJm || isPicacg) 0L else ManwaSource.parseAddtime(ch.addtime),
                    sourceOrder = ch.sort.toLong(),
                    chapterNumber = ch.sort.toDouble(),
                    read = picacgProgress?.read ?: jmProgress?.read ?: (ch.readed == 1),
                    lastPageRead = picacgProgress?.lastPageRead ?: jmProgress?.lastPageRead ?: 0,
                )
            }
            if (isJm || isPicacg) {
                val updates = domainChapters.mapNotNull { chapter ->
                    chapter.takeIf { it.id >= 0 }?.let {
                        ChapterUpdate(
                            id = it.id,
                            url = it.url,
                            name = it.name,
                            dateUpload = it.dateUpload,
                            sourceOrder = it.sourceOrder,
                            chapterNumber = it.chapterNumber,
                            read = it.read,
                            lastPageRead = it.lastPageRead,
                        )
                    }
                }
                val newChapters = domainChapters.filter { it.id < 0 }
                chapterRepository.updateAll(updates)
                if (newChapters.isNotEmpty()) chapterRepository.addAll(newChapters)
            } else {
                chapterRepository.addAll(domainChapters)
            }

            // 4. 找到目标章节的数据库 id
            val targetUrl = if (isPicacg) PicacgChapterProgressPolicy.chapterUrl(mangaDto.id, picacgChapter!!.sort) else "/chapter/$chapterId"
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
