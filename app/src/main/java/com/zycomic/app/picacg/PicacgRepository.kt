package com.zycomic.app.picacg

import android.content.Context
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.net.PicacgRouteManager
import com.zycomic.app.picacg.filter.PicacgBlockKeywordStore
import com.zycomic.app.picacg.filter.PicacgKeywordFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Public Picacg data facade; no methods fall back to Manwa or JM. */
object PicacgRepository {
    @Volatile private var api: PicacgClient? = null
    private val pageCache = PicacgPageCache<PicacgImage>()
    private val browseSnapshotCache = PicacgBrowseSnapshotCache<PicacgPage<Manga>>()
    private val browseRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val browseRefreshLock = Any()
    private val browseRefreshes = mutableMapOf<PicacgBrowseCacheKey, CompletableDeferred<PicacgPage<Manga>>>()
    private val _browseCacheRevision = MutableStateFlow(0L)
    val browseCacheRevision: StateFlow<Long> = _browseCacheRevision.asStateFlow()

    @Volatile private var browseConfigGeneration = 0L
    private val _favouritesRevision = MutableStateFlow(0L)
    val favouritesRevision: StateFlow<Long> = _favouritesRevision.asStateFlow()

    /** Application startup hook; can be invoked repeatedly. */
    fun init(context: Context) {
        PicacgBlockKeywordStore.initialize(context)
        PicacgAuthStore.initialize(context)
        if (api == null) {
            synchronized(this) {
                if (api == null) {
                    api = PicacgClient(
                        baseUrl = { PicacgRouteManager.apiBaseUrl },
                        http = okhttp3.OkHttpClient.Builder()
                            .dns(PicacgRouteManager.dns())
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                            // A changed IP pin must not reuse a connection established for an older pin.
                            .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .build(),
                    )
                }
            }
        }
    }

    /** Exposed for deterministic offline tests and controlled client substitution. */
    internal fun installClientForTests(client: PicacgClient?) {
        synchronized(this) { api = client }
        synchronized(pageCache) { pageCache.clear() }
        browseConfigGeneration += 1L
        invalidateBrowseSnapshots()
    }

    private fun client(): PicacgClient = api ?: error("PicacgRepository.init(context) must be called first")

    suspend fun login(email: String, password: String): String {
        require(email.isNotBlank() && password.isNotBlank()) { "账号和密码不能为空。" }
        val startingRevision = PicacgAuthStore.sessionRevision.value
        val payload = JSONObject().put("email", email).put("password", password)
        val token = try {
            client().request("/auth/sign-in", "POST", payload, authenticated = false).data.optString("token")
        } catch (error: Exception) {
            throw loginFailure(error)
        }
        if (token.isBlank()) {
            val reason = PicacgLoginFailure.UNKNOWN
            throw PicacgLoginException(reason, PicacgLoginErrorPolicy.message(reason))
        }
        val saved = try {
            PicacgAuthStore.saveTokenIfRevision(token, startingRevision)
        } catch (error: Exception) {
            throw loginFailure(error)
        }
        if (!saved) {
            throw PicacgLoginException(PicacgLoginFailure.UNKNOWN, "登录状态已变化，请重新登录。")
        }
        synchronized(pageCache) { pageCache.clear() }
        invalidateBrowseSnapshots()
        return token
    }

    /** Register a Picacg account. The wire field named `email` is the username, not an email address. */
    suspend fun register(draft: PicacgRegistrationDraft) {
        val issue = PicacgRegistrationPolicy.validate(draft)
        require(issue == null) { "Picacg registration draft is invalid" }
        try {
            client().request(
                "/auth/register",
                "POST",
                PicacgRegistrationPolicy.toPayload(draft),
                authenticated = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw PicacgRegistrationException(PicacgRegistrationErrorPolicy.message(error))
        }
    }

    private fun loginFailure(error: Exception): PicacgLoginException {
        if (error is CancellationException) throw error
        val reason = PicacgLoginErrorPolicy.classify(error)
        return PicacgLoginException(reason, PicacgLoginErrorPolicy.message(reason))
    }

    fun logout() {
        PicacgAuthStore.clear()
        synchronized(pageCache) { pageCache.clear() }
        invalidateBrowseSnapshots()
        _favouritesRevision.update { it + 1L }
    }

    suspend fun categories(): List<PicacgCategory> {
        val data = client().request("/categories").data.optJSONArray("categories")
            ?: throw IOException("Picacg categories response has no categories array")
        return data.objects().mapNotNull { json ->
            val id = json.optString("_id")
            val title = json.optString("title").trim()
            val active = json.optBoolean("active", true)
            val isWeb = json.optBoolean("isWeb", false)
            if (title.isBlank() || !active || isWeb) null else PicacgCategory(id, title, json.optString("description"), active, isWeb)
        }
    }

    suspend fun browse(
        page: Int,
        category: String? = null,
        sort: String = "dd",
        tag: String? = null,
        author: String? = null,
        creatorId: String? = null,
        chineseTeam: String? = null,
        forceRefresh: Boolean = false,
    ): PicacgPage<Manga> {
        require(page >= 1) { "Picacg pages are one-based" }
        require(sort in SORT_VALUES) { "Unsupported Picacg sort value" }
        val keywords = PicacgBlockKeywordStore.keywords.value.toList()
        val key = browseCacheKey(
            operation = if (category.isNullOrBlank()) "browse" else "category",
            mode = if (category.isNullOrBlank()) "all" else "category",
            query = listOfNotNull(category, tag, author, creatorId, chineseTeam).joinToString("&"),
            sort = sort,
            filters = listOf(category.orEmpty(), tag.orEmpty(), author.orEmpty(), creatorId.orEmpty(), chineseTeam.orEmpty()),
            page = page,
            keywords = keywords,
        )
        val query = buildList {
            add("page=$page")
            addFilter("c", category)
            addFilter("t", tag)
            addFilter("a", author)
            addFilter("ca", creatorId)
            addFilter("ct", chineseTeam)
            add("s=${encode(sort)}")
        }.joinToString("&")
        return loadBrowseSnapshot(key, forceRefresh) {
            val data = client().request("/comics?$query", expectedSessionRevision = key.condition.sessionRevision).data
            comicList(data, page, applyKeywordFilter = true, blockedKeywords = keywords)
        }
    }

    suspend fun search(
        keyword: String,
        page: Int,
        sort: String = "ld",
        categories: List<String> = emptyList(),
        forceRefresh: Boolean = false,
    ): PicacgPage<Manga> {
        require(page >= 1) { "Picacg pages are one-based" }
        require(sort in SORT_VALUES) { "Unsupported Picacg sort value" }
        val selectedCategories = categories.toList()
        val keywords = PicacgBlockKeywordStore.keywords.value.toList()
        val key = browseCacheKey(
            operation = "search",
            mode = "advanced",
            query = keyword,
            sort = sort,
            filters = selectedCategories,
            page = page,
            keywords = keywords,
        )
        val body = JSONObject()
            .put("keyword", keyword)
            .put("sort", sort)
            .put("categories", JSONArray(selectedCategories))
        return loadBrowseSnapshot(key, forceRefresh) {
            val data = client().request(
                "/comics/advanced-search?page=$page",
                "POST",
                body,
                expectedSessionRevision = key.condition.sessionRevision,
            ).data
            comicList(data, page, applyKeywordFilter = true, blockedKeywords = keywords)
        }
    }

    suspend fun leaderboard(timeframe: String = "H24", forceRefresh: Boolean = false): List<Manga> {
        require(timeframe in LEADERBOARD_TIMEFRAMES) { "Unsupported Picacg leaderboard timeframe" }
        val keywords = PicacgBlockKeywordStore.keywords.value.toList()
        val key = browseCacheKey("leaderboard", timeframe, timeframe, "", listOf("ct=VC"), 1, keywords)
        return loadBrowseSnapshot(key, forceRefresh) {
            val data = client().request(
                "/comics/leaderboard?tt=$timeframe&ct=VC",
                expectedSessionRevision = key.condition.sessionRevision,
            ).data
            PicacgPage(filterDiscovery(data.comicArray("leaderboard"), keywords), false, 1)
        }.items
    }

    /** Random discovery is deliberately never cached. */
    suspend fun random(): List<Manga> {
        val keywords = PicacgBlockKeywordStore.keywords.value.toList()
        return filterDiscovery(client().request("/comics/random").data.comicArray("random"), keywords)
    }

    private fun browseCacheKey(
        operation: String,
        mode: String,
        query: String,
        sort: String,
        filters: List<String>,
        page: Int,
        keywords: List<String>,
    ): PicacgBrowseCacheKey {
        val condition = PicacgBrowseCacheCondition(
            sessionRevision = PicacgAuthStore.sessionRevision.value,
            keywordRevision = PicacgBlockKeywordStore.revision.value,
            routeConfigRevision = PicacgRouteManager.configRevision,
            configGeneration = browseConfigGeneration,
            blockedKeywords = keywords.toList(),
        )
        browseSnapshotCache.activate(condition)
        return PicacgBrowseCacheKey(condition, operation, mode, query, sort, filters.toList(), page)
    }

    private suspend fun loadBrowseSnapshot(
        key: PicacgBrowseCacheKey,
        forceRefresh: Boolean,
        load: suspend () -> PicacgPage<Manga>,
    ): PicacgPage<Manga> {
        ensureBrowseConditionCurrent(key)
        when (val cached = browseSnapshotCache.get(key, System.currentTimeMillis())) {
            is PicacgBrowseCacheLookup.Fresh -> {
                if (!forceRefresh) {
                    ensureBrowseConditionCurrent(key)
                    return cached.value
                }
                refreshBrowseSnapshot(key, load)
                ensureBrowseConditionCurrent(key)
                return cached.value
            }
            is PicacgBrowseCacheLookup.Stale -> {
                refreshBrowseSnapshot(key, load)
                ensureBrowseConditionCurrent(key)
                return cached.value
            }
            null -> return refreshBrowseSnapshot(key, load).await().also { ensureBrowseConditionCurrent(key) }
        }
    }

    private fun browseConditionIsCurrent(condition: PicacgBrowseCacheCondition): Boolean =
        condition.sessionRevision == PicacgAuthStore.sessionRevision.value &&
            condition.keywordRevision == PicacgBlockKeywordStore.revision.value &&
            condition.routeConfigRevision == PicacgRouteManager.configRevision &&
            condition.configGeneration == browseConfigGeneration &&
            condition.blockedKeywords == PicacgBlockKeywordStore.keywords.value

    private fun ensureBrowseConditionCurrent(key: PicacgBrowseCacheKey) {
        if (!browseConditionIsCurrent(key.condition)) {
            throw CancellationException("Picacg browse conditions changed while loading")
        }
    }

    private fun invalidateBrowseSnapshots() {
        browseSnapshotCache.clear()
        val pending = synchronized(browseRefreshLock) {
            browseRefreshes.values.toList().also { browseRefreshes.clear() }
        }
        pending.forEach { it.cancel(CancellationException("Picacg browse cache conditions changed")) }
    }

    /** At most one fetch runs per exact condition; failed refreshes never replace the last success. */
    private fun refreshBrowseSnapshot(
        key: PicacgBrowseCacheKey,
        load: suspend () -> PicacgPage<Manga>,
    ): CompletableDeferred<PicacgPage<Manga>> = synchronized(browseRefreshLock) {
        browseRefreshes[key]?.let { return@synchronized it }
        val result = CompletableDeferred<PicacgPage<Manga>>()
        val replacesExistingSnapshot = browseSnapshotCache.get(key, System.currentTimeMillis()) != null
        browseRefreshes[key] = result
        browseRefreshScope.launch {
            try {
                val value = load()
                if (!browseConditionIsCurrent(key.condition)) {
                    result.cancel(CancellationException("Picacg browse conditions changed while loading"))
                    return@launch
                }
                if (browseSnapshotCache.put(key, value, System.currentTimeMillis()) && replacesExistingSnapshot) {
                    _browseCacheRevision.update { it + 1L }
                }
                if (!browseConditionIsCurrent(key.condition)) {
                    result.cancel(CancellationException("Picacg browse conditions changed while loading"))
                    return@launch
                }
                result.complete(value)
            } catch (cancelled: CancellationException) {
                result.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                result.completeExceptionally(failure)
            } finally {
                synchronized(browseRefreshLock) {
                    if (browseRefreshes[key] === result) browseRefreshes.remove(key)
                }
            }
        }
        result
    }

    /** Returns the existing application Manga DTO, with chapter IDs and order from the confirmed API schema. */
    suspend fun detail(id: String): Manga {
        require(id.isNotBlank() && !id.contains('/') && !id.contains('?')) { "Invalid Picacg comic id" }
        val comic = client().request("/comics/${encodePathSegment(id)}").data.optJSONObject("comic")
            ?: throw IOException("Picacg detail response has no comic")
        val comicMap = comic.toMap()
        val episodes = allEpisodes(id)
        comicMap["chapterList"] = episodes.map { mapOf("_id" to it.id, "title" to it.title, "order" to it.order) }
        return comicMap.toPicacgManga().withResolvedCover(comic.optJSONObject("thumb"))
    }

    /** One authoritative detail read for flags/counters and classifier fields; it never infers state from POST results. */
    suspend fun comicState(id: String): PicacgComicState = parseComicState(loadComicJson(id))

    suspend fun detailWithState(id: String): PicacgComicDetail {
        val comic = loadComicJson(id)
        val comicMap = comic.toMap()
        val episodes = allEpisodes(id)
        comicMap["chapterList"] = episodes.map { mapOf("_id" to it.id, "title" to it.title, "order" to it.order) }
        val manga = comicMap.toPicacgManga().withResolvedCover(comic.optJSONObject("thumb"))
        val recommendations = recommendations(id)
        val creator = comic.optJSONObject("creator") ?: comic.optJSONObject("_creator")
        val state = parseComicState(comic)
        return PicacgComicDetail(
            manga = manga,
            isFavourite = state.isFavourite,
            isLiked = state.isLiked,
            likesCount = state.likesCount,
            commentsCount = state.commentsCount,
            allowComment = state.allowComment,
            author = state.author,
            chineseTeam = state.chineseTeam,
            creatorId = state.creatorId,
            categories = state.categories,
            tags = state.tags,
            uploader = creator?.optString("name").orEmpty(),
            updatedAt = comic.optString("updated_at", comic.optString("updatedAt")),
            recommendations = recommendations,
        )
    }

    suspend fun recommendations(id: String): List<Manga> = filterDiscovery(
        client().request("/comics/${encodePathSegment(id)}/recommendation").data.comicArray("recommendations"),
    )

    /** Toggle POST responses contain no action/state evidence; a same-session detail GET is authoritative. */
    suspend fun toggleFavourite(id: String): Boolean = toggleAndReadState(id, "favourite", "isFavourite", refreshFavourites = true)

    suspend fun toggleLike(id: String): Boolean = toggleAndReadState(id, "like", "isLiked")

    suspend fun comments(comicId: String, page: Int = 1, replyTo: String? = null): PicacgPage<PicacgComment> =
        loadComments(comicId, page, replyTo, null)

    private suspend fun loadComments(
        comicId: String,
        page: Int,
        replyTo: String?,
        expectedSessionRevision: Long?,
    ): PicacgPage<PicacgComment> {
        require(page >= 1) { "Picacg pages are one-based" }
        val path = if (replyTo.isNullOrBlank()) {
            "/comics/${encodePathSegment(comicId)}/comments?page=$page"
        } else {
            "/comments/${encodePathSegment(replyTo)}/childrens?page=$page"
        }
        val paged = client().request(path, expectedSessionRevision = expectedSessionRevision).data.optJSONObject("comments")
            ?: throw IOException("Picacg comments response has no comments object")
        val docs = paged.optJSONArray("docs") ?: throw IOException("Picacg comments response has no docs array")
        val pageCount = pageCount(paged, "comments")
        return PicacgPage(docs.objects().mapNotNull(::parseComment), page < pageCount, page)
    }

    suspend fun sendComment(comicId: String, content: String, replyTo: String? = null) {
        require(content.isNotBlank()) { "Comment content is required" }
        val path = if (replyTo.isNullOrBlank()) "/comics/${encodePathSegment(comicId)}/comments" else "/comments/${encodePathSegment(replyTo)}"
        safeWrite(path, JSONObject().put("content", content))
    }

    /** POST has no documented action response; callers should refresh their visible comments after this succeeds. */
    suspend fun toggleCommentLike(commentId: String) {
        safeWrite("/comments/${encodePathSegment(commentId)}/like", JSONObject())
    }

    suspend fun episodes(comicId: String, page: Int = 1): PicacgPage<PicacgEpisode> {
        require(page >= 1) { "Picacg pages are one-based" }
        val data = client().request("/comics/${encodePathSegment(comicId)}/eps?page=$page").data
        val paged = data.optJSONObject("eps") ?: throw IOException("Picacg episodes response has no eps object")
        val docsArray = paged.optJSONArray("docs") ?: throw IOException("Picacg episodes response has no docs array")
        val docs = docsArray.objects().mapNotNull { obj ->
            val id = obj.optString("_id")
            val title = obj.optString("title")
            val order = obj.optInt("order")
            if (id.isBlank() || title.isBlank() || order <= 0) null else PicacgEpisode(id, title, order, obj.optString("updatedAt"))
        }
        return PicacgPage(docs, page < pageCount(paged, "episodes"), page)
    }

    suspend fun allEpisodes(comicId: String): List<PicacgEpisode> {
        val result = mutableListOf<PicacgEpisode>()
        var page = 1
        while (page <= 100) {
            val response = episodes(comicId, page)
            result += response.items
            if (!response.hasMore) break
            page++
        }
        return result.sortedBy(PicacgEpisode::order)
    }

    /** Get one documented page of image entries; page is one-based. */
    suspend fun pages(comicId: String, order: Int, page: Int = 1): PicacgPage<PicacgImage> {
        require(comicId.isNotBlank() && order > 0 && page > 0) { "Invalid Picacg comic/chapter/page" }
        val imageHost = PicacgRouteManager.currentImageHost.orEmpty()
        val sessionRevision = PicacgAuthStore.sessionRevision.value
        val cacheKey = "$sessionRevision:$comicId:$order:$page:$imageHost"
        pageCache.get(cacheKey)?.let { return it }
        val data = client().request("/comics/${encodePathSegment(comicId)}/order/$order/pages?page=$page").data
        val paged = data.optJSONObject("pages") ?: throw IOException("Picacg pages response has no pages object")
        val host = imageHost
        val docsArray = paged.optJSONArray("docs") ?: throw IOException("Picacg pages response has no docs array")
        val entries = docsArray.objects().mapNotNull { obj ->
            val media = obj.optJSONObject("media") ?: return@mapNotNull null
            val path = media.optString("path")
            if (path.isBlank()) return@mapNotNull null
            // Keep the API-reported fileServer (real bucket: storage-b/storage1/sub_storage);
            // PicacgRouteManager mirrors that bucket and falls back across roots at load time.
            val fileServer = media.optString("fileServer").takeIf { it.isNotBlank() && it != "null" }
            val url = PicacgImageUrl.resolveWithFileServer(path, fileServer, host)
            PicacgImage(obj.optString("_id"), url, media.optString("originalName"))
        }
        val response = PicacgPage(entries, page < pageCount(paged, "pages"), page)
        check(PicacgAuthStore.sessionRevision.value == sessionRevision) { "Picacg session changed while loading pages" }
        entries.firstOrNull()?.let { PicacgRouteManager.setVerifiedImageSampleUrlFromApiResponse(it.url) }
        pageCache.put(cacheKey, response)
        return response
    }

    suspend fun allPages(comicId: String, order: Int): List<PicacgImage> {
        val result = mutableListOf<PicacgImage>()
        var page = 1
        while (page <= 1000) {
            val response = pages(comicId, order, page)
            result += response.items
            if (!response.hasMore) break
            page++
        }
        return result
    }

    suspend fun favourites(
        page: Int,
        sort: String = "dd",
        expectedSessionRevision: Long? = null,
    ): PicacgPage<Manga> {
        require(page >= 1 && sort in SORT_VALUES) { "Invalid Picacg favorites page/sort" }
        val data = client().request(
            "/users/favourite?page=$page&s=${encode(sort)}",
            expectedSessionRevision = expectedSessionRevision,
        ).data
        val paged = data.optJSONObject("comics") ?: data.optJSONObject("favourites") ?: data
        return comicList(paged, page)
    }

    suspend fun profile(): PicacgProfile = loadProfile(expectedSessionRevision = null)

    /** Load a profile only for the session that initiated the operation. */
    suspend fun profile(expectedSessionRevision: Long): PicacgProfile = loadProfile(expectedSessionRevision)

    private suspend fun loadProfile(expectedSessionRevision: Long?): PicacgProfile {
        val profile = client().request("/users/profile", expectedSessionRevision = expectedSessionRevision).data.optJSONObject("user")
            ?: throw IOException("Picacg profile response has no user")
        fun text(key: String, fallbackKey: String? = null): String {
            val value = profile.optString(key).takeIf { it.isNotBlank() && it != "null" }
            val fallback = fallbackKey?.let { profile.optString(it) }?.takeIf { it.isNotBlank() && it != "null" }
            return value ?: fallback.orEmpty()
        }
        val id = text("id", "_id")
        val name = text("name")
        val avatar = profile.optJSONObject("avatar")
        val avatarPath = avatar?.optString("path")?.takeIf { it.isNotBlank() && it != "null" }
        val avatarServer = avatar?.optString("fileServer")?.takeIf { it.isNotBlank() && it != "null" }
        val avatarUrl = avatarPath?.let {
            PicacgImageUrl.resolveWithFileServer(it, avatarServer, PicacgRouteManager.currentImageHost.orEmpty())
        }.orEmpty()
        val isPunched = when (val value = profile.opt("isPunched")) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            else -> null
        }
        val characters = profile.optJSONArray("characters")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return PicacgProfile(
            id = id,
            name = name,
            email = text("email"),
            avatarUrl = avatarUrl,
            slogan = text("slogan"),
            title = text("title"),
            gender = text("gender"),
            birthday = text("birthday"),
            level = profile.optInt("level", 0),
            exp = profile.optInt("exp", 0),
            characters = characters,
            createdAt = text("createdAt"),
            isPunched = isPunched,
            verified = profile.optBoolean("verified"),
        )
    }

    /** The write response does not carry a reliable profile flag; read back in the same session. */
    suspend fun punchIn(expectedSessionRevision: Long? = null): PicacgProfile {
        val requestSessionRevision = expectedSessionRevision ?: PicacgAuthStore.sessionRevision.value
        client().request("/users/punch-in", "POST", JSONObject(), expectedSessionRevision = requestSessionRevision)
        return loadProfile(requestSessionRevision)
    }

    private fun comicList(
        data: JSONObject,
        page: Int = 1,
        applyKeywordFilter: Boolean = false,
        blockedKeywords: List<String> = PicacgBlockKeywordStore.keywords.value,
    ): PicacgPage<Manga> {
        val container = data.optJSONObject("comics") ?: data.optJSONObject("favourite") ?: data
        val docs = container.optJSONArray("docs") ?: container.optJSONArray("comics")
            ?: throw IOException("Picacg comic list response has no docs array")
        val items = docs.objects().map { it.toMap().toPicacgManga().withResolvedCover(it.optJSONObject("thumb")) }
        val pages = pageCount(container, "comic list")
        return PicacgPage(if (applyKeywordFilter) filterDiscovery(items, blockedKeywords) else items, page < pages, page)
    }

    /** Applies the current local policy at call time; pagination metadata deliberately remains server-derived. */
    private fun filterDiscovery(items: List<Manga>, keywords: List<String> = PicacgBlockKeywordStore.keywords.value): List<Manga> =
        PicacgKeywordFilter.filterManga(items, keywords)

    private suspend fun loadComicJson(id: String, expectedSessionRevision: Long? = null): JSONObject {
        require(id.isNotBlank() && !id.contains('/') && !id.contains('?')) { "Invalid Picacg comic id" }
        return client().request("/comics/${encodePathSegment(id)}", expectedSessionRevision = expectedSessionRevision)
            .data.optJSONObject("comic") ?: throw IOException("Picacg detail response has no comic")
    }

    private fun parseComicState(comic: JSONObject): PicacgComicState {
        val creator = comic.optJSONObject("creator") ?: comic.optJSONObject("_creator")
        return PicacgComicState(
            isFavourite = requiredBoolean(comic, "isFavourite"),
            isLiked = requiredBoolean(comic, "isLiked"),
            likesCount = comic.optInt("likesCount", comic.optInt("totalLikes", -1)).also { if (it < 0) throw IOException("Picacg detail response has no likes count") },
            commentsCount = comic.optInt("commentsCount", comic.optInt("totalComments", -1)).also { if (it < 0) throw IOException("Picacg detail response has no comments count") },
            allowComment = requiredBoolean(comic, "allowComment"),
            author = comic.optString("author"),
            chineseTeam = comic.optString("chineseTeam"),
            creatorId = creator?.optString("_id").orEmpty(),
            categories = comic.stringArray("categories"),
            tags = comic.stringArray("tags"),
        )
    }

    private fun parseComment(comment: JSONObject): PicacgComment? {
        val id = comment.optString("_id")
        val user = comment.optJSONObject("_user") ?: comment.optJSONObject("user")
        val name = user?.optString("name").orEmpty()
        val avatar = user?.optJSONObject("avatar")
        val avatarPath = avatar?.optString("path").orEmpty()
        val avatarServer = avatar?.optString("fileServer")?.takeIf { it.isNotBlank() && it != "null" }.orEmpty().trimEnd('/')
        val avatarUrl = when {
            avatarPath.isBlank() -> ""
            avatarServer.isNotBlank() -> "$avatarServer/static/${avatarPath.trimStart('/').removePrefix("static/")}"
            else -> PicacgImageUrl.resolve(avatarPath, PicacgRouteManager.currentImageHost.orEmpty())
        }
        if (id.isBlank()) return null
        if (!comment.has("isLiked")) throw IOException("Picacg comment response is missing like state")
        return PicacgComment(
            id = id,
            userName = name,
            avatarUrl = avatarUrl,
            content = comment.optString("content"),
            isLiked = comment.optBoolean("isLiked"),
            likesCount = comment.optInt("likesCount", comment.optInt("score", 0)).coerceAtLeast(0),
            replyCount = comment.optInt("commentsCount", 0),
            createdAt = comment.optString("created_at", comment.optString("createdAt")),
        )
    }

    private suspend fun toggleAndReadState(id: String, action: String, stateKey: String, refreshFavourites: Boolean = false): Boolean {
        require(id.isNotBlank() && !id.contains('/') && !id.contains('?')) { "Invalid Picacg comic id" }
        val revision = PicacgAuthStore.sessionRevision.value
        try {
            safeWrite("/comics/${encodePathSegment(id)}/$action", JSONObject(), revision)
        } finally {
            if (refreshFavourites) _favouritesRevision.update { it + 1L }
        }
        return try {
            val comic = loadComicJson(id, revision)
            requiredBoolean(comic, stateKey)
        } catch (error: Exception) {
            throw postWriteRefreshFailure("comic $action", error)
        }
    }

    private suspend fun safeWrite(path: String, body: JSONObject, expectedRevision: Long = PicacgAuthStore.sessionRevision.value) {
        try {
            client().request(path, "POST", body, expectedSessionRevision = expectedRevision)
        } catch (error: Exception) {
            throw IOException("Picacg POST result could not be confirmed; it may have taken effect. Refresh before deciding whether to retry.", error)
        }
    }

    private fun postWriteRefreshFailure(action: String, cause: Exception) = IOException(
        "Picacg $action POST succeeded, but the resulting state could not be read back. The operation may have taken effect; refresh before retrying to avoid toggling twice.",
        cause,
    )

    private fun JSONObject.comicArray(kind: String): List<Manga> {
        val comics = optJSONArray("comics") ?: throw IOException("Picacg $kind response has no comics array")
        return comics.objects().map { it.toPicacgMap().toPicacgManga().withResolvedCover(it.optJSONObject("thumb")) }
    }

    private fun JSONObject.stringArray(key: String): List<String> = optJSONArray(key)?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
    }.orEmpty()

    private fun requiredBoolean(json: JSONObject, key: String): Boolean {
        if (!json.has(key) || json.isNull(key)) throw IOException("Picacg detail response has no $key state")
        return json.optBoolean(key)
    }

    private fun MutableList<String>.addFilter(key: String, value: String?) {
        value?.takeIf(String::isNotBlank)?.let { add("$key=${encode(it)}") }
    }

    private fun pageCount(container: JSONObject, kind: String): Int {
        if (!container.has("pages") || container.isNull("pages")) throw IOException("Picacg $kind response has no pages count")
        val pages = container.optInt("pages", -1)
        if (pages < 0) throw IOException("Picacg $kind response has invalid pages count")
        if (pages == 0 && (container.optJSONArray("docs")?.length() ?: 0) > 0) {
            throw IOException("Picacg $kind response has records but no pages")
        }
        return pages
    }

    private fun Manga.withResolvedCover(thumb: JSONObject?): Manga {
        val apiPath = thumb?.optString("path").orEmpty()
        val path = apiPath.ifBlank { picx }
        // Keep the API's real fileServer (storage1/storage-b); RouteManager mirrors the true bucket.
        val fileServer = thumb?.optString("fileServer")?.takeIf { it.isNotBlank() && it != "null" }
        val resolved = if (path.isBlank()) {
            ""
        } else {
            PicacgImageUrl.resolveWithFileServer(path, fileServer, PicacgRouteManager.currentImageHost.orEmpty())
        }
        if (apiPath.isNotBlank() && resolved.isNotBlank()) PicacgRouteManager.setVerifiedImageSampleUrlFromApiResponse(resolved)
        return copy(picx = resolved)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.toMap(): MutableMap<String, Any?> = keys().asSequence().associateWith { key ->
        when (val value = opt(key)) {
            JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
    }.toMutableMap()

    private fun JSONArray.toList(): List<Any?> = (0 until length()).map { index ->
        when (val value = opt(index)) {
            JSONObject.NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun encodePathSegment(value: String) = encode(value).replace("+", "%20")

    private val SORT_VALUES = setOf("dd", "da", "ld", "vd")
    private val LEADERBOARD_TIMEFRAMES = setOf("H24", "D7", "D30")
}
