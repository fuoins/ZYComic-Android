package com.zycomic.app.reader

import com.zycomic.app.data.dto.Manga as MangaDto
import com.zycomic.app.data.repository.ReaderRepository
import com.zycomic.app.net.ManwaInterceptor
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.RouteManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * Manwa 原生图源：把 ZYComic 的 API 适配成 komikku [HttpSource] 接口。
 *
 * 设计原则：
 * - 网络复用 [NetworkModule.client]（已含 ManwaInterceptor 签名/响应解密 + ImageInterceptor 图片流式解密），
 *   不在此做任何手动解密。
 * - 章节内容 JSON 由 ManwaInterceptor 解密后交给 Retrofit；图片由 ImageInterceptor 流式解密。
 * - [baseUrl] 随线路切换动态读取。
 * - 页面 URL 列表经 [ReaderRepository] 的 LRU-5 内存缓存。
 */
object ManwaSource : HttpSource() {

    override val name: String = "Manwa"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = false

    /** baseUrl 随线路切换动态读取（不能缓存为 val）。 */
    override val baseUrl: String get() = RouteManager.baseUrl

    /** 复用全局 OkHttpClient（已包含解密/签名拦截器链）。 */
    override val client: OkHttpClient get() = NetworkModule.client

    override val headers: Headers = Headers.Builder()
        .add("User-Agent", ManwaInterceptor.UA)
        .build()

    // ==================== 页面列表 ====================

    /**
     * 从 chapter.url（"/chapter/123"）提取章节 id，拉取章节内容并构造图片 Page 列表。
     * 完整图片 URL = img_domains[0] + piclist[i]。
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.toChapterId()
            ?: throw IllegalArgumentException("无法从 url 解析章节 id: ${chapter.url}")
        // 走 ReaderRepository 的 LRU-5 缓存（命中则直接返回拼接好的 URL 列表）
        val urls = ReaderRepository.getChapterPages(chapterId)
        return urls.mapIndexed { index, url ->
            Page(index, url, url)
        }
    }

    /** 图片 URL 已在 getPageList 中直接赋值，这里原样返回。 */
    override suspend fun getImageUrl(page: Page): String = page.url!!

    /** 通过共享 client 下载图片（ImageInterceptor 自动处理加密图片流式解密）。 */
    override suspend fun getImage(page: Page): Response {
        return client.newCall(GET(page.imageUrl!!, headers)).awaitSuccess()
    }

    // ==================== 漫画详情 + 章节列表 ====================

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url.toMangaId()
            ?: throw IllegalArgumentException("无法从 url 解析漫画 id: ${manga.url}")
        val resp = NetworkModule.api.detail(mangaId)
        val dto = resp.data ?: throw IOException(resp.msg.ifEmpty { "漫画详情为空" })

        val smanga = SManga.create().apply {
            url = "/manga/${dto.id}"
            title = dto.name
            thumbnail_url = coverUrl(dto).ifBlank { null }
            author = dto.author
            description = dto.text
            genre = dto.tags.joinToString(", ") { it.name }
            status = if (dto.end.contains("完")) SManga.COMPLETED else SManga.ONGOING
            initialized = true
        }
        val schapters = dto.chapterList.map { ch ->
            SChapter.create().apply {
                url = "/chapter/${ch.id}"
                name = ch.name
                date_upload = parseAddtime(ch.addtime)
                chapter_number = -1f
            }
        }
        return SMangaUpdate(smanga, schapters)
    }

    // ==================== 工具 ====================

    /** 把封面字段拼成可加载 URL（与 UI 层 coverUrl 逻辑一致）。 */
    internal fun coverUrl(dto: MangaDto): String {
        val raw = dto.picx.ifBlank { dto.pic }
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val host = RouteManager.imgHost
        val sep = if (raw.startsWith("/")) "" else "/"
        return "https://$host$sep$raw"
    }

    /** addtime 字符串（秒级时间戳）转毫秒；无法解析返回 0。 */
    internal fun parseAddtime(raw: String): Long {
        val sec = raw.trim().toLongOrNull() ?: return 0L
        return if (sec < 10_000_000_000L) sec * 1000L else sec
    }

    /** "/chapter/123" -> "123" */
    private fun String.toChapterId(): String? =
        trim('/').substringAfterLast('/').substringBefore('?').ifBlank { null }

    /** "/manga/123" -> "123" */
    private fun String.toMangaId(): String? =
        trim('/').substringAfterLast('/').substringBefore('?').ifBlank { null }
}
