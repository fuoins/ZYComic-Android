package com.zycomic.app.reader

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
import com.zycomic.app.data.dto.Chapter as ChapterDto
import com.zycomic.app.data.dto.Manga as MangaDto

/** JM JSON API adapter. It deliberately does not share Manwa's network or repository state. */
object JmSource : HttpSource() {

    private val weekTypes = setOf("hanman", "another", "manga")

    data class BrowseResult(val items: List<MangaDto>, val hasMore: Boolean)
    data class WeekIssue(val id: String, val title: String)
    data class AlbumResult(val manga: MangaDto, val chapterWarning: String?)

    /** Display label and the exact API value; an empty category means latest A-comics. */
    val categoryOptions: List<Pair<String, String>> = listOf(
        "最新A漫" to "",
        "同人" to "doujin",
        "单本" to "single",
        "短篇" to "short",
        "其他类" to "another",
        "韩漫" to "hanman",
        "美漫" to "meiman",
        "Cosplay" to "another_cosplay",
        "3D" to "3D",
        "禁漫汉化组" to "禁漫漢化組",
    )

    /** Display label and the exact API value verified by the JM contract report. */
    val orderOptions: List<Pair<String, String>> = listOf(
        "最新" to "mr",
        "总排行" to "mv",
        "月排行" to "mv_m",
        "周排行" to "mv_w",
        "日排行" to "mv_t",
        "最多图片" to "mp",
        "最多喜欢" to "tf",
    )

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
    private class CategoryCode : Filter.Text("分类 slug（c，可留空）")
    private class WeekId : Filter.Text("周榜 ID（可留空）")
    private class WeekType : Filter.Text("周榜类型（可留空）")

    override fun getFilterList(): FilterList = FilterList(
        BrowseMode(),
        Filter.Header("分类必须使用 JM slug；分类和排序选项见 source adapter"),
        OrderCode(),
        CategoryCode(),
        WeekId(),
        WeekType(),
    )

    /**
     * Browse UI adapter. Page numbers here are one-based except the fixed, non-paged week list.
     * [previousFirstId] is scoped by the caller to its own tab/query. The actual adjacent page is
     * also checked for list endpoints, so callers that pass an accumulated-list ID cannot leak state.
     */
    suspend fun browse(
        mode: Int,
        page: Int,
        query: String = "",
        order: String = "",
        category: String = "",
        weekId: String = "",
        weekType: String = "",
        previousFirstId: String? = null,
    ): BrowseResult {
        val requestedPage = page.coerceAtLeast(1)
        val path: String
        val params: Map<String, String>
        var hasMore: Boolean
        if (query.isNotBlank()) {
            path = "/search"
            validateOrder(order)
            params = buildMap {
                put("search_query", query)
                put("page", requestedPage.toString())
                order.takeIf(String::isNotBlank)?.let { put("o", it) }
            }
            val payload = getJson(path, params)
            val (items, total) = parseList(payload, path, "content", requireTotal = true)
            hasMore = JmPagination.searchHasMore(requestedPage, total)
            return BrowseResult(items, hasMore)
        } else {
            when (mode) {
                1 -> {
                    validateOrder(order)
                    val normalizedCategory = category.trim()
                    if (normalizedCategory.isNotEmpty() && categoryOptions.none { it.second == normalizedCategory && it.second.isNotEmpty() }) {
                        throw IOException("JM 分类必须是已确认的 slug；不接受数字分类 ID 或未知值: $normalizedCategory")
                    }
                    path = "/categories/filter"
                    params = buildMap {
                        put("page", requestedPage.toString())
                        order.takeIf(String::isNotBlank)?.let { put("o", it) }
                        normalizedCategory.takeIf(String::isNotEmpty)?.let { put("c", it) }
                    }
                }
                2 -> {
                    if (weekId.isBlank() && weekType.isBlank()) {
                        return BrowseResult(emptyList(), false)
                    } else {
                        validateWeekSelection(weekId, weekType)
                        path = "/week/filter"
                        params = mapOf("id" to weekId, "type" to weekType, "page" to "0")
                    }
                }
                else -> {
                    path = "/promote"
                    // Keep the existing promote endpoint's zero-based page convention.
                    params = mapOf("page" to (requestedPage - 1).toString())
                }
            }
        }

        val payload = getJson(path, params)
        if (path == "/week/filter") {
            val items = parseList(payload, path, "list").first
            return BrowseResult(items, false)
        }
        if (path == "/promote") {
            val items = parsePromote(payload)
            val adjacentFirstId = previousFirstId ?: if (requestedPage > 1) {
                parsePromote(getJson(path, mapOf("page" to (requestedPage - 2).toString()))).firstOrNull()?.id
            } else {
                null
            }
            val firstId = items.firstOrNull()?.id
            val repeated = firstId != null && (firstId == previousFirstId || firstId == adjacentFirstId)
            if (repeated) return BrowseResult(emptyList(), false)
            hasMore = JmPagination.cappedListHasMore(requestedPage, items.size, firstId, if (repeated) firstId else null)
            return BrowseResult(items, hasMore)
        }

        val (items, total) = parseList(payload, path, "content", requireTotal = true)
        val adjacentFirstId = previousFirstId ?: if (requestedPage > 1) {
            parseList(
                getJson(path, params + ("page" to (requestedPage - 1).toString())),
                path,
                "content",
                requireTotal = true,
            ).first.firstOrNull()?.id
        } else {
            null
        }
        val firstId = items.firstOrNull()?.id
        val repeated = firstId != null && (firstId == previousFirstId || firstId == adjacentFirstId)
        if (repeated) return BrowseResult(emptyList(), false)
        hasMore = JmPagination.categoryHasMore(requestedPage, total, firstId, if (repeated) firstId else null)
        return BrowseResult(items, hasMore)
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
    suspend fun getAlbum(albumId: String): AlbumResult = withContext(Dispatchers.IO) {
        if (!albumId.matches(Regex("\\d+"))) throw IOException("JM 专辑 ID 无效")
        val album = getJson("/album", mapOf("id" to albumId)).asObject("/album")
        val title = album.optString("name").trim()
        if (title.isBlank()) throw IOException("JM /album 缺少已确认字段 name；未将不完整详情标记为可用")
        val series = JmResponseParser.chapters(album)
        val details = JmResponseParser.albumDetails(album)
        AlbumResult(
            manga = MangaDto(
                id = albumId,
                name = title,
                author = details.author,
                text = details.description,
                picx = coverUrl(albumId),
                tags = details.tags,
                addtime = details.addtime,
                hits = details.hits,
                fav = details.favorite,
                chapterList = series.items.map { ChapterDto(id = it.id, name = it.name, sort = it.sort) },
                loveList = details.related.map { item ->
                    MangaDto(
                        id = item.id,
                        name = item.name,
                        author = item.author,
                        text = item.description,
                        picx = coverUrl(item.id),
                    )
                },
            ),
            chapterWarning = series.warning,
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
            parseChapters(album)
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapterId(chapter.url) ?: throw IOException("无法从 JM 章节 URL 解析章节 ID")
        val payload = getJson("/comic_read", mapOf("id" to chapterId)).asObject("/comic_read")
        val images = payload.optJSONArray("images")
            ?: throw IOException("JM /comic_read 响应缺少已确认字段 images[]")
        if (images.length() == 0) throw IOException("JM /comic_read images[] 为空")
        val host = JmRouteManager.activeImageDomain
        if (host.isBlank()) throw IOException("JM 图源列表为空")
        val scrambleThreshold = payload.optString("scramble_id").toIntOrNull()
            ?: throw IOException("JM /comic_read 响应缺少有效整数字段 scramble_id")
        return (0 until images.length()).map { index ->
            val imageObject = images.optJSONObject(index)
                ?: throw IOException("JM /comic_read images[$index] 不是对象")
            val imageValue = imageObject.optString("image").trim()
            if (imageValue.isBlank()) throw IOException("JM /comic_read images[$index] 缺少字段 image")
            val originalUrl = try {
                imageValue.toHttpUrl()
            } catch (error: IllegalArgumentException) {
                throw IOException("JM /comic_read images[$index].image 不是绝对 HTTP URL", error)
            }
            if (originalUrl.scheme != "http" && originalUrl.scheme != "https") {
                throw IOException("JM /comic_read images[$index].image 必须使用 HTTP(S)")
            }
            // Keep the API's rotated path and query intact; only swap the static image host.
            val url = originalUrl.newBuilder()
                .host(host)
                .removeAllQueryParameters("_jm_scramble")
                .addQueryParameter("_jm_scramble", scrambleThreshold.toString())
                .build()
                .toString()
            Page(index, url, url)
        }
    }

    /** Lists the available weekly issue selectors from /week.categories. */
    suspend fun getWeekIssues(): List<WeekIssue> {
        val payload = getJson("/week", emptyMap()).asObject("/week")
        return JmResponseParser.weekIssues(payload).map { WeekIssue(it.id, it.title) }
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
                throw IOException("JM $path 返回内容不是 JSON", error)
            }
        }
    }

    private fun parsePromote(payload: Any): List<MangaDto> {
        val root = payload as? JSONArray ?: throw IOException("JM /promote 响应不是报告中的 JSON 数组")
        val result = mutableListOf<MangaDto>()
        var foundContent = false
        for (blockIndex in 0 until root.length()) {
            val block = root.optJSONObject(blockIndex)
                ?: throw IOException("JM /promote[$blockIndex] 不是对象")
            val content = block.optJSONArray("content")
                ?: throw IOException("JM /promote[$blockIndex] 缺少字段 content[]")
            foundContent = true
            for (itemIndex in 0 until content.length()) {
                val item = content.optJSONObject(itemIndex)
                    ?: throw IOException("JM /promote[$blockIndex].content[$itemIndex] 不是对象")
                result += mangaFromKnownListItem(item, "/promote[$blockIndex].content[$itemIndex]")
            }
        }
        if (!foundContent) throw IOException("JM /promote 响应缺少已确认字段 content[]")
        return result.distinctBy { it.id }
    }

    /** Parse only the documented endpoint list field; never recurse into unrelated IDs. */
    private fun parseList(
        payload: Any,
        endpoint: String,
        listField: String,
        requireTotal: Boolean = false,
    ): Pair<List<MangaDto>, Int> {
        val root = payload.asObject(endpoint)
        val list = root.optJSONArray(listField)
            ?: throw IOException("JM $endpoint 响应缺少已确认字段 $listField[]")
        val items = (0 until list.length()).map { index ->
            val item = list.optJSONObject(index)
                ?: throw IOException("JM $endpoint $listField[$index] 不是对象")
            mangaFromKnownListItem(item, "$endpoint.$listField[$index]")
        }.distinctBy { it.id }
        val total = if (requireTotal) {
            root.optString("total").toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IOException("JM $endpoint 响应缺少有效非负整数字段 total")
        } else {
            0
        }
        return items to total
    }

    private fun mangaFromKnownListItem(item: JSONObject, location: String): MangaDto {
        val id = item.optString("id").trim()
        val title = item.optString("name").trim()
        if (!id.matches(Regex("\\d+")) || title.isBlank()) {
            throw IOException("JM $location 缺少有效字段 id/name")
        }
        return MangaDto(
            id = id,
            name = title,
            author = item.optString("author").takeIf { it.isNotBlank() }.orEmpty(),
            picx = coverUrl(id),
        )
    }

    private fun parseChapters(album: JSONObject): List<SChapter> {
        val series = JmResponseParser.chapters(album)
        series.warning?.let { JmRouteManager.record("schema", "JM", it) }
        return series.items.map { item ->
            SChapter.create().apply {
                url = "/chapter/${item.id}"
                name = item.name
                chapter_number = item.sort.toFloat()
                scanlator = null
                date_upload = 0L
            }
        }
    }

    private fun mangaFromAlbum(album: JSONObject, id: String, original: SManga): SManga {
        val details = JmResponseParser.albumDetails(album)
        return SManga.create().apply {
            url = "/album/$id"
            title = album.optString("name").takeIf { it.isNotBlank() } ?: original.title
            thumbnail_url = coverUrl(id)
            author = details.author.ifBlank { original.author }
            description = details.description.ifBlank { original.description }
            genre = details.tags.map { it.name }.filter(String::isNotBlank).joinToString(", ").ifBlank { original.genre }
            status = original.status
            initialized = original.initialized
        }
    }

    private fun toSourceManga(dto: MangaDto): SManga = SManga.create().apply {
        url = "/album/${dto.id}"
        title = dto.name
        author = dto.author.takeIf { it.isNotBlank() }
        thumbnail_url = dto.picx.takeIf { it.isNotBlank() }
        status = SManga.UNKNOWN
        initialized = false
    }

    private fun Any.asObject(endpoint: String): JSONObject = this as? JSONObject
        ?: throw IOException("JM $endpoint 响应不是已确认的 JSON 对象")

    private fun coverUrl(albumId: String): String =
        "https://${JmRouteManager.activeImageDomain}/media/albums/${albumId}_3x4.jpg"

    private fun mangaId(url: String): String? = Regex("(?:^|/)album/(\\d+)(?:/|$)").find(url)?.groupValues?.get(1)
    private fun chapterId(url: String): String? = Regex("(?:^|/)chapter/(\\d+)(?:/|$)").find(url)?.groupValues?.get(1)

    private fun validateOrder(order: String) {
        if (order.isNotBlank() && orderOptions.none { it.second == order }) {
            throw IOException("JM 排序代码无效: $order")
        }
    }

    private fun validateWeekSelection(id: String, type: String) {
        if (!id.matches(Regex("\\d+"))) throw IOException("JM 周榜必须提供有效期号 id")
        if (type !in weekTypes) throw IOException("JM 周榜 type 必须是 hanman、another 或 manga")
    }
}
