package com.zycomic.app.reader

import com.zycomic.app.data.dto.Chapter as ChapterDto
import com.zycomic.app.data.dto.Manga as MangaDto
import com.zycomic.app.net.JmApiInterceptor
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRouteManager
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URLEncoder

/** JM JSON API adapter. It deliberately does not share Manwa's network or repository state. */
object JmSource : HttpSource() {

    data class BrowseResult(val items: List<MangaDto>, val hasMore: Boolean)

    override val name: String = "禁漫天堂"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = false
    override val baseUrl: String get() = JmRouteManager.baseUrl
    override val client get() = JmNetworkModule.client
    override val headers: Headers = Headers.Builder()
        .add("User-Agent", JmApiInterceptor.USER_AGENT)
        .add("Origin", JmApiInterceptor.ORIGIN)
        .add("Referer", JmApiInterceptor.REFERER)
        .add("X-Requested-With", JmApiInterceptor.PACKAGE_NAME)
        .build()

    private class BrowseMode : Filter.Select<String>("JM 列表", arrayOf("推荐", "分类筛选", "周榜"), 0) {
        override fun toString(): String = values[state]
    }

    private class OrderCode : Filter.Text("排序代码（o，可留空）")
    private class CategoryCode : Filter.Text("分类值（c，可留空）")
    private class WeekId : Filter.Text("周榜 ID（可留空）")
    private class WeekType : Filter.Text("周榜类型（可留空）")

    override fun getFilterList(): FilterList = FilterList(
        BrowseMode(),
        Filter.Header("JM 接口筛选值按原样发送；本地没有预设未验证的枚举"),
        OrderCode(),
        CategoryCode(),
        WeekId(),
        WeekType(),
    )

    /** Browse UI adapter. Search has no verified page parameter, so it is one page only. */
    suspend fun browse(
        mode: Int,
        page: Int,
        query: String = "",
        order: String = "",
        category: String = "",
        weekId: String = "",
        weekType: String = "",
    ): BrowseResult {
        val zeroBasedPage = (page - 1).coerceAtLeast(0).toString()
        val path: String
        val params: Map<String, String>
        val pageable: Boolean
        if (query.isNotBlank()) {
            path = "/search"
            params = mapOf("search_query" to query, "o" to order)
            pageable = false
        } else {
            when (mode) {
                1 -> {
                    path = "/categories/filter"
                    params = mapOf("o" to order, "c" to category, "page" to zeroBasedPage)
                    pageable = true
                }
                2 -> {
                    if (weekId.isBlank() && weekType.isBlank()) {
                        path = "/week"
                        params = emptyMap()
                        pageable = false
                    } else {
                        path = "/week/filter"
                        params = mapOf("id" to weekId, "type" to weekType, "page" to zeroBasedPage)
                        pageable = true
                    }
                }
                else -> {
                    path = "/promote"
                    params = mapOf("page" to zeroBasedPage)
                    pageable = true
                }
            }
        }

        val payload = getJson(path, params)
        val items = if (path == "/promote") parsePromote(payload) else parseKnownItems(payload, path)
        return BrowseResult(items, pageable && items.isNotEmpty())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val result = browse(mode = 0, page = page)
        return MangasPage(result.items.map(::toSourceManga), result.hasMore)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val mode = filters.filterIsInstance<BrowseMode>().firstOrNull()?.state ?: 0
        val order = filters.filterIsInstance<OrderCode>().firstOrNull()?.state.orEmpty()
        val category = filters.filterIsInstance<CategoryCode>().firstOrNull()?.state.orEmpty()
        val weekId = filters.filterIsInstance<WeekId>().firstOrNull()?.state.orEmpty()
        val weekType = filters.filterIsInstance<WeekType>().firstOrNull()?.state.orEmpty()
        val result = browse(mode, page, query, order, category, weekId, weekType)
        return MangasPage(result.items.map(::toSourceManga), result.hasMore)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        throw UnsupportedOperationException("JM 报告未提供可验证的最新更新 JSON 接口")

    /** Fetch only report-confirmed album fields; missing series is an explicit schema error. */
    suspend fun getAlbum(albumId: String): MangaDto = withContext(Dispatchers.IO) {
        if (!albumId.matches(Regex("\\d+"))) throw IOException("JM 专辑 ID 无效")
        val album = getJson("/album", mapOf("id" to albumId)).asObject("/album")
        val title = album.optString("name").trim()
        if (title.isBlank()) throw IOException("JM /album 缺少已确认字段 name；未将不完整详情标记为可用")
        val series = parseDtoChapters(album)
        MangaDto(
            id = albumId,
            name = title,
            picx = coverUrl(albumId),
            chapterList = series,
        )
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val id = mangaId(manga.url) ?: throw IOException("无法从 JM 漫画 URL 解析专辑 ID")
        val album = getJson("/album", mapOf("id" to id)).asObject("/album")
        val updatedManga = if (fetchDetails) mangaFromAlbum(album, id, manga) else manga
        val updatedChapters = if (fetchChapters) {
            try {
                parseChapters(album).takeIf { it.isNotEmpty() || chapters.isEmpty() } ?: chapters
            } catch (error: IOException) {
                if (chapters.isEmpty()) throw error
                JmRouteManager.record("schema", "JM", "章节响应未通过字段校验；保留已有章节")
                chapters
            }
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapterId(chapter.url) ?: throw IOException("无法从 JM 章节 URL 解析章节 ID")
        val payload = getJson("/chapter", mapOf("id" to chapterId)).asObject("/chapter")
        val imageNames = payload.optJSONArray("images")
            ?: throw IOException("JM /chapter 响应缺少已确认字段 images[]")
        if (imageNames.length() == 0) throw IOException("JM /chapter images[] 为空；需要该章节的原始响应样本")
        val host = JmRouteManager.activeImageDomain
        if (host.isBlank()) throw IOException("JM 图源列表为空")
        val scrambleId = findScrambleId(payload)
        return (0 until imageNames.length()).map { index ->
            val fileName = imageNames.optString(index).trim()
            if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\')) {
                throw IOException("JM /chapter images[$index] 不是已确认的单个文件名")
            }
            val builder = "https://$host/media/photos/$chapterId/${encodePathSegment(fileName)}".toHttpUrl().newBuilder()
            scrambleId?.let { builder.addQueryParameter("_jm_scramble", it.toString()) }
            val url = builder.build().toString()
            Page(index, url, url)
        }
    }

    override suspend fun getImageUrl(page: Page): String = page.url
        ?: throw IOException("JM 阅读页缺少图片 URL")

    override suspend fun getImage(page: Page): Response {
        val imageUrl = page.imageUrl ?: throw IOException("JM 阅读页缺少图片 URL")
        return client.newCall(Request.Builder().url(imageUrl).headers(headers).build()).awaitSuccess()
    }

    private suspend fun getJson(path: String, params: Map<String, String>): Any = withContext(Dispatchers.IO) {
        val currentBase = JmRouteManager.baseUrl
        if (currentBase.isBlank()) throw IOException("JM 没有可用线路")
        JmNetworkModule.ensureAgeCookie()
        val urlBuilder = currentBase.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/'))
        params.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val request = Request.Builder().url(urlBuilder.build()).get().headers(headers).build()
        client.newCall(request).awaitSuccess().use { response ->
            val body = response.body?.string().orEmpty()
            try {
                JSONTokener(body).nextValue()
            } catch (error: Exception) {
                throw IOException("JM ${path} 返回内容不是 JSON", error)
            }
        }
    }

    private fun parsePromote(payload: Any): List<MangaDto> {
        val root = payload as? JSONArray ?: throw IOException("JM /promote 响应不是报告中的 JSON 数组")
        val result = mutableListOf<MangaDto>()
        for (blockIndex in 0 until root.length()) {
            val block = root.optJSONObject(blockIndex) ?: continue
            val content = block.optJSONArray("content") ?: continue
            for (itemIndex in 0 until content.length()) {
                content.optJSONObject(itemIndex)?.let { item -> mangaFromKnownListItem(item)?.let(result::add) }
            }
        }
        if (result.isEmpty()) throw IOException("JM /promote 未返回已确认的 content[].id/name 项")
        return result.distinctBy { it.id }
    }

    /** Search/category/week samples are incomplete; accept only documented numeric id + name pairs. */
    private fun parseKnownItems(payload: Any, endpoint: String): List<MangaDto> {
        val result = mutableListOf<MangaDto>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    val manga = mangaFromKnownListItem(value)
                    if (manga != null) result += manga
                    else {
                        val keys = value.keys()
                        while (keys.hasNext()) visit(value.opt(keys.next()))
                    }
                }
            }
        }
        visit(payload)
        if (result.isEmpty()) throw IOException("JM $endpoint 响应未包含可验证的数字 id/name 项；缺少该端点的完整 JSON 样本")
        return result.distinctBy { it.id }
    }

    private fun mangaFromKnownListItem(item: JSONObject): MangaDto? {
        val id = item.optString("id").trim()
        val title = item.optString("name").trim()
        if (!id.matches(Regex("\\d+")) || title.isBlank()) return null
        return MangaDto(
            id = id,
            name = title,
            author = item.optString("author").takeIf { it.isNotBlank() }.orEmpty(),
            picx = coverUrl(id),
        )
    }

    private fun parseDtoChapters(album: JSONObject): List<ChapterDto> {
        val series = album.optJSONArray("series")
            ?: throw IOException("JM /album 响应缺少已确认字段 series[]")
        return (0 until series.length()).map { index ->
            val item = series.optJSONObject(index)
                ?: throw IOException("JM /album series[$index] 不是对象")
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            val sort = item.optString("sort").toIntOrNull()
            if (!id.matches(Regex("\\d+")) || name.isBlank() || sort == null) {
                throw IOException("JM /album series[$index] 缺少已确认的 id/name/sort 字段")
            }
            ChapterDto(id = id, name = name, sort = sort.toInt())
        }
    }

    private fun parseChapters(album: JSONObject): List<SChapter> {
        val series = album.optJSONArray("series")
            ?: throw IOException("JM /album 响应缺少已确认字段 series[]")
        return (0 until series.length()).map { index ->
            val item = series.optJSONObject(index)
                ?: throw IOException("JM /album series[$index] 不是对象")
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            val sortValue = item.optString("sort").toIntOrNull()
            if (!id.matches(Regex("\\d+")) || name.isBlank() || sortValue == null) {
                throw IOException("JM /album series[$index] 缺少已确认的 id/name/sort 字段")
            }
            SChapter.create().apply {
                url = "/chapter/$id"
                this.name = name
                chapter_number = sortValue.toFloat()
                scanlator = null
                date_upload = 0L
            }
        }
    }

    private fun mangaFromAlbum(album: JSONObject, id: String, original: SManga): SManga = SManga.create().apply {
        url = "/album/$id"
        title = album.optString("name").takeIf { it.isNotBlank() } ?: original.title
        thumbnail_url = coverUrl(id)
        author = original.author
        description = original.description
        genre = original.genre
        status = original.status
        initialized = original.initialized
    }

    private fun toSourceManga(dto: MangaDto): SManga = SManga.create().apply {
        url = "/album/${dto.id}"
        title = dto.name
        author = dto.author.takeIf { it.isNotBlank() }
        thumbnail_url = dto.picx.takeIf { it.isNotBlank() }
        status = SManga.UNKNOWN
        initialized = false
    }

    private fun findScrambleId(value: Any?): Int? = when (value) {
        is JSONObject -> {
            val keys = value.keys()
            var found: Int? = null
            while (keys.hasNext() && found == null) {
                val key = keys.next()
                val child = value.opt(key)
                if (key == "scramble_id") {
                    found = child.toString().toIntOrNull()
                        ?: throw IOException("JM /chapter scramble_id 不是已确认的整数；需要一份原始 JSON 样本")
                } else {
                    found = findScrambleId(child)
                }
            }
            found
        }
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { findScrambleId(value.opt(it)) }
        else -> null
    }

    private fun Any.asObject(endpoint: String): JSONObject = this as? JSONObject
        ?: throw IOException("JM $endpoint 响应不是已确认的 JSON 对象")

    private fun coverUrl(albumId: String): String =
        "https://${JmRouteManager.activeImageDomain}/media/albums/${albumId}_3x4.jpg"

    private fun mangaId(url: String): String? = Regex("\\d+").find(url)?.value
    private fun chapterId(url: String): String? = Regex("\\d+").find(url)?.value

    private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
