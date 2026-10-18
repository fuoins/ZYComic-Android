package com.zycomic.app.reader

import com.zycomic.app.data.dto.JmComment
import com.zycomic.app.net.JmApiInterceptor
import com.zycomic.app.net.JmAuthStore
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
import kotlinx.coroutines.CancellationException
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
import java.util.Calendar
import com.zycomic.app.data.dto.Chapter as ChapterDto
import com.zycomic.app.data.dto.Manga as MangaDto

/** JM JSON API adapter. It deliberately does not share Manwa's network or repository state. */
object JmSource : HttpSource() {

    private val relatedImageHostCache = JmRelatedMangaPolicy.ImageHostCache()
    private val weekTypes = setOf("hanman", "another", "manga")
    private val serializationTypes = setOf("all", "manga", "hanman")

    data class BrowseResult(val items: List<MangaDto>, val hasMore: Boolean)
    data class WeekIssue(val id: String, val title: String)
    data class PromoteSection(
        val id: String,
        val title: String,
        val type: String,
        val slug: String,
        val filterValue: String,
        val items: List<MangaDto>,
    )
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
        "禁漫漢化組" to "禁漫漢化組",
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

    /** /promote is a section catalogue, not a flat comic list. */
    suspend fun getPromoteSections(): List<PromoteSection> = parsePromoteSections(getJson("/promote", emptyMap()))

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
        weekType: String = "manga",
        previousFirstId: String? = null,
        serializationType: String = "all",
        serializationDate: Int = todayIsoWeekday(),
        promoteId: String = "",
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
                    val normalizedCategory = category
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
                    if (weekId.isBlank()) {
                        return BrowseResult(emptyList(), false)
                    } else {
                        val selectedWeekType = weekType.ifBlank { "manga" }
                        validateWeekSelection(weekId, selectedWeekType)
                        path = "/week/filter"
                        params = JmBrowseParameters.weekFilter(weekId, selectedWeekType, requestedPage)
                    }
                }
                4 -> {
                    val selectedSerializationType = serializationType.ifBlank { "all" }
                    validateSerializationSelection(selectedSerializationType, serializationDate)
                    path = "/serialization"
                    params = JmBrowseParameters.serialization(selectedSerializationType, serializationDate, requestedPage)
                }
                5 -> {
                    if (!promoteId.matches(Regex("\\d+")) || promoteId == "26") {
                        throw IOException("JM /promote_list 必须使用有效的非周更板块 id")
                    }
                    path = "/promote_list"
                    params = JmBrowseParameters.promoteList(promoteId, requestedPage - 1)
                }
                6 -> return BrowseResult(emptyList(), false)
                else -> {
                    path = "/promote"
                    // Keep the existing promote endpoint's zero-based page convention.
                    params = mapOf("page" to (requestedPage - 1).toString())
                }
            }
        }

        val payload = getJson(path, params)
        if (path == "/week/filter" || path == "/serialization") {
            val parsed = JmResponseParser.listPage(payload.asObject(path), path)
            val items = parsed.items.map { MangaDto(id = it.id, name = it.name, author = it.author, picx = coverUrl(it.id)) }
            val pageSize = if (path == "/week/filter") JmPagination.WEEK_ITEMS_PER_PAGE else JmPagination.SERIALIZATION_ITEMS_PER_PAGE
            return BrowseResult(items, JmPagination.totalHasMore(requestedPage, parsed.total, pageSize))
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

        val listField = if (path == "/promote_list") "list" else "content"
        val (items, total) = parseList(payload, path, listField, requireTotal = true)
        if (path == "/promote_list") {
            return BrowseResult(items, JmPagination.totalHasMore(requestedPage, total, JmBrowseParameters.PROMOTE_LIST_PAGE_SIZE))
        }
        val adjacentFirstId = previousFirstId ?: if (requestedPage > 1) {
            parseList(
                getJson(path, params + ("page" to (requestedPage - 1).toString())),
                path,
                listField,
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
        val weekType = filters.filterIsInstance<WeekType>().firstOrNull()?.state.orEmpty().ifBlank { "manga" }
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
        val series = JmResponseParser.chapters(album, singleChapterAlbumId = albumId)
        val details = JmResponseParser.albumDetails(album)
        val relatedImgHost = if (details.related.isNotEmpty()) getRelatedImageHost() else null
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
                jmAuthors = details.authors,
                jmActors = details.actors,
                jmWorks = details.works,
                jmLikes = details.likes,
                jmLiked = details.liked,
                jmViews = details.views,
                jmUpdateTime = details.updateTime,
                chapterList = series.items.map { ChapterDto(id = it.id, name = it.name, sort = it.sort) },
                loveList = details.related.mapNotNull { item ->
                    val imageUrl = relatedImgHost?.let { host ->
                        JmRelatedMangaPolicy.coverUrl(host, item.id, details.addtime)
                    } ?: return@mapNotNull null
                    MangaDto(
                        id = item.id,
                        name = item.name,
                        author = item.author,
                        text = item.description,
                        picx = imageUrl,
                    )
                },
            ),
            chapterWarning = series.warning,
        )
    }

    data class JmCommentPage(val total: Int?, val comments: List<JmComment>, val hasMore: Boolean)

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val response = postJson("/login", mapOf("username" to username, "password" to password))
        val token = response.optString("jwttoken").takeIf(String::isNotBlank)
            ?: throw IOException(response.optString("errorMsg").ifBlank { response.optString("msg") }.ifBlank { "JM 登录失败：响应缺少 JWT" })
        JmAuthStore.save(token, username)
        username
    }

    fun logout() {
        JmAuthStore.clear()
        JmNetworkModule.clearCookies()
    }

    suspend fun toggleFavorite(albumId: String): Boolean {
        val response = postJson("/favorite", mapOf("aid" to albumId))
        if (response.optString("status") != "ok") throw IOException(response.optString("msg").ifBlank { "JM 收藏操作失败" })
        return response.optString("type") == "add"
    }

    suspend fun toggleLike(albumId: String) {
        val response = postJson("/like", mapOf("id" to albumId))
        if (response.optString("status") != "success" && response.optString("status") != "ok") {
            throw IOException(response.optString("msg").ifBlank { "JM 点赞操作失败" })
        }
    }

    suspend fun getComments(albumId: String, page: Int): JmCommentPage {
        if (!albumId.matches(Regex("\\d+"))) throw IOException("JM 专辑 ID 无效")
        val payload = getJson("/forum", mapOf("mode" to "manhua", "aid" to albumId, "page" to page.coerceAtLeast(1).toString())).asObject("/forum")
        val result = JmResponseParser.forumPage(payload)
        val count = result.total
        val pageCount = count?.let { (it + JM_COMMENT_PAGE_SIZE - 1) / JM_COMMENT_PAGE_SIZE }
        val imageUrl = "https://${JmRouteManager.activeImageDomain}"
        val comments = result.comments.distinctBy { it.id }.map { comment ->
            comment.copy(avatarUrl = JmResponseParser.userAvatarUrl(imageUrl, comment.avatarUrl))
        }
        return JmCommentPage(count, comments, pageCount == null || page < pageCount)
    }

    suspend fun postComment(albumId: String, content: String) {
        val response = postJson("/comment", mapOf("aid" to albumId, "comment" to content, "status" to "undefined"))
        if (response.optString("status") == "fail") throw IOException(response.optString("msg").ifBlank { "JM 评论发送失败" })
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
            parseChapters(album, id)
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapterId(chapter.url) ?: throw IOException("无法从 JM 章节 URL 解析章节 ID")
        val payload = getJson("/chapter", mapOf("id" to chapterId)).asObject("/chapter")
        val images = JmResponseParser.chapterImages(payload)
        val host = JmRouteManager.activeImageDomain
        if (host.isBlank()) throw IOException("JM 图源列表为空")
        return images.mapIndexed { index, imageName ->
            val url = "https://$host".toHttpUrl().newBuilder()
                .addPathSegment("media")
                .addPathSegment("photos")
                .addPathSegment(chapterId)
                .addPathSegment(imageName.trim())
                .apply {
                    payload.optString("scramble_id").toIntOrNull()?.let { threshold ->
                        addQueryParameter("_jm_scramble", threshold.toString())
                    }
                }
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
        return JmNetworkModule.imageClient.newCall(Request.Builder().url(imageUrl).headers(headers).build()).awaitSuccess()
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

    private suspend fun getRelatedImageHost(): String? {
        val key = relatedSettingsCacheKey()
        relatedImageHostCache.get(key)?.let { return it }
        return try {
            val payload = getJson(
                "/setting",
                mapOf("app_img_shunt" to "1", "express" to "", "lang" to "TW"),
            )
            val host = JmRelatedMangaPolicy.settingImageHost(payload)
            if (host == null) {
                JmRouteManager.record("related_setting", JmRouteManager.lineHost, "JM /setting 未返回有效 HTTPS img_host；已隐藏详情相关推荐")
                return null
            }
            if (relatedSettingsCacheKey() != key) {
                JmRouteManager.record("related_setting", JmRouteManager.lineHost, "JM 线路或配置在 /setting 请求期间切换；本次隐藏详情相关推荐")
                return null
            }
            relatedImageHostCache.put(key, host)
            host
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            JmRouteManager.record(
                "related_setting",
                JmRouteManager.lineHost,
                "JM /setting 请求失败；已隐藏详情相关推荐: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
    }

    private fun relatedSettingsCacheKey(): JmRelatedMangaPolicy.SettingsCacheKey {
        val state = JmRouteManager.state.value
        val routeConfiguration = buildString {
            append(state.remoteVersion ?: "unknown").append('|')
            append(state.lineIndex).append('|').append(state.imageDomain.lowercase(java.util.Locale.ROOT)).append('|')
            append(state.lines.joinToString(",") { it.lowercase(java.util.Locale.ROOT) }).append('|')
            append(state.imageDomains.joinToString(",") { it.lowercase(java.util.Locale.ROOT) })
        }
        return JmRelatedMangaPolicy.cacheKey(JmRouteManager.baseUrl, routeConfiguration)
    }

    private suspend fun postJson(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val currentBase = JmRouteManager.baseUrl
        if (currentBase.isBlank()) throw IOException("JM 没有可用线路")
        JmNetworkModule.ensureAgeCookie()
        val body = okhttp3.FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build()
        val url = currentBase.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).build()
        val request = Request.Builder().url(url).post(body).headers(headers).build()
        client.newCall(request).awaitSuccess().use { response ->
            try {
                JSONObject(response.body?.string().orEmpty())
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
            if (!JmBrowseParameters.hasComicPreview(block.optString("type"))) continue
            for (itemIndex in 0 until content.length()) {
                val item = content.optJSONObject(itemIndex)
                    ?: throw IOException("JM /promote[$blockIndex].content[$itemIndex] 不是对象")
                result += mangaFromKnownListItem(item, "/promote[$blockIndex].content[$itemIndex]")
            }
        }
        if (!foundContent) throw IOException("JM /promote 响应缺少已确认字段 content[]")
        return result.distinctBy { it.id }
    }

    private fun parsePromoteSections(payload: Any): List<PromoteSection> {
        val root = payload as? JSONArray ?: throw IOException("JM /promote 响应不是报告中的 JSON 数组")
        return (0 until root.length()).map { blockIndex ->
            val block = root.optJSONObject(blockIndex)
                ?: throw IOException("JM /promote[$blockIndex] 不是对象")
            val content = block.optJSONArray("content")
                ?: throw IOException("JM /promote[$blockIndex] 缺少字段 content[]")
            val id = block.opt("id")?.toString()?.takeIf { it.matches(Regex("\\d+")) }
                ?: throw IOException("JM /promote[$blockIndex] 缺少有效 id")
            val type = block.optString("type")
            // Creator and novel previews have their own schemas; they are not manga records.
            val comicCount = if (JmBrowseParameters.hasComicPreview(type)) content.length() else 0
            val items = (0 until comicCount).map { itemIndex ->
                val item = content.optJSONObject(itemIndex)
                    ?: throw IOException("JM /promote[$blockIndex].content[$itemIndex] 不是对象")
                mangaFromKnownListItem(item, "/promote[$blockIndex].content[$itemIndex]")
            }
            PromoteSection(
                id = id,
                title = block.optString("title").ifBlank { "板块 $id" },
                type = type,
                slug = block.optString("slug"),
                filterValue = block.optString("filter_val"),
                items = items,
            )
        }
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

    private fun parseChapters(album: JSONObject, albumId: String): List<SChapter> {
        val series = JmResponseParser.chapters(album, singleChapterAlbumId = albumId)
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

    private fun validateSerializationSelection(type: String, date: Int) {
        if (type !in serializationTypes) throw IOException("JM serialization type 必须是 all、manga 或 hanman")
        if (date !in 0..7) throw IOException("JM serialization date 必须是 0 到 7")
    }

    private const val JM_COMMENT_PAGE_SIZE = 6

    private fun todayIsoWeekday(): Int {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (day == Calendar.SUNDAY) 7 else day - 1
    }
}
