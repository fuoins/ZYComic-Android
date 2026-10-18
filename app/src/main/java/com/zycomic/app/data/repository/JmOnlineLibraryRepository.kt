package com.zycomic.app.data.repository

import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRequestSession
import com.zycomic.app.net.JmRouteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class JmOnlineLibraryItem(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val image: String,
)

data class JmFavoriteFolder(
    val id: String,
    val name: String,
)

data class JmOnlineLibraryPage(
    val items: List<JmOnlineLibraryItem>,
    val total: Int,
    val folders: List<JmFavoriteFolder> = emptyList(),
)

/** JM-only account endpoints; this repository never reads or mutates Manwa state. */
object JmOnlineLibraryRepository {
    const val PAGE_SIZE = JmOnlineLibraryPolicy.PAGE_SIZE

    suspend fun favorites(page: Int, order: String, folderId: String = ""): JmOnlineLibraryPage =
        getPage(JmOnlineLibraryPolicy.favoritesRequest(page, order, folderId), isFavorites = true, captureSession())

    suspend fun history(page: Int): JmOnlineLibraryPage =
        getPage(JmOnlineLibraryPolicy.historyRequest(page), isFavorites = false, captureSession())

    /** Ensure favorites and optionally move them under the same captured account revision. */
    suspend fun ensureFavorites(ids: Collection<String>, folder: JmFavoriteFolder? = null) = withContext(Dispatchers.IO) {
        val uniqueIds = validateIds(ids)
        val session = captureSession()
        var requestCount = 0
        uniqueIds.forEach { id ->
            if (requestCount > 0) delay(REQUEST_INTERVAL_MS)
            val album = getObject(JmOnlineLibraryPolicy.albumRequest(id), session)
            requestCount++
            val favorite = readFavoriteFlag(album)
            if (!favorite) {
                delay(REQUEST_INTERVAL_MS)
                toggleFavorites(uniqueIds = listOf(id), session = session)
                requestCount++
            }
        }
        if (folder != null) {
            delay(REQUEST_INTERVAL_MS)
            postForm(JmOnlineLibraryPolicy.favoriteFolderMoveRequest(folder.id, uniqueIds.joinToString(","), folder.name), session)
                .let(JmOnlineLibraryPolicy::parseFavoriteFolderWriteResponse)
        }
    }

    /** Toggle one or more favorites; JM returns add/edit/move/remove in response type. */
    suspend fun toggleFavorites(ids: Collection<String>): String = withContext(Dispatchers.IO) {
        toggleFavorites(validateIds(ids), captureSession())
    }

    private suspend fun toggleFavorites(uniqueIds: List<String>, session: JmRequestSession): String {
        val aid = uniqueIds.joinToString(",")
        val result = postForm(JmOnlineLibraryPolicy.favoriteRequest(aid), session)
        return JmOnlineLibraryPolicy.parseFavoriteWriteResponse(result).type.takeIf(String::isNotBlank)
            ?: throw IOException("JM 收藏响应缺少 type")
    }

    suspend fun addFolder(name: String) {
        postForm(JmOnlineLibraryPolicy.favoriteFolderAddRequest(name), captureSession())
            .let(JmOnlineLibraryPolicy::parseFavoriteFolderWriteResponse)
    }

    suspend fun renameFolder(folder: JmFavoriteFolder, name: String) {
        postForm(JmOnlineLibraryPolicy.favoriteFolderEditRequest(folder.id, name), captureSession())
            .let(JmOnlineLibraryPolicy::parseFavoriteFolderWriteResponse)
    }

    suspend fun moveFavorites(ids: Collection<String>, folder: JmFavoriteFolder) {
        val aid = validateIds(ids).joinToString(",")
        postForm(JmOnlineLibraryPolicy.favoriteFolderMoveRequest(folder.id, aid, folder.name), captureSession())
            .let(JmOnlineLibraryPolicy::parseFavoriteFolderWriteResponse)
    }

    suspend fun deleteFolder(folder: JmFavoriteFolder) {
        postForm(JmOnlineLibraryPolicy.favoriteFolderDeleteRequest(folder.id), captureSession())
            .let(JmOnlineLibraryPolicy::parseFavoriteFolderWriteResponse)
    }

    suspend fun deleteHistory(id: String) {
        deleteHistory(listOf(id))
    }

    /** Delete a group under one captured account revision, aborting if the session changes. */
    suspend fun deleteHistory(ids: Collection<String>) = withContext(Dispatchers.IO) {
        val session = captureSession()
        val uniqueIds = ids.map(String::trim).distinct()
        require(uniqueIds.isNotEmpty()) { "观看记录 ID 不能为空" }
        require(uniqueIds.all { it.matches(Regex("\\d+")) }) { "观看记录 ID 无效" }
        uniqueIds.forEachIndexed { index, id ->
            if (index > 0) delay(REQUEST_INTERVAL_MS)
            val result = postForm(JmOnlineLibraryPolicy.deleteHistoryRequest(id), session)
            JmOnlineLibraryPolicy.parseDeleteHistoryResponse(result)
        }
    }

    private suspend fun getPage(
        requestSpec: JmOnlineLibraryRequest,
        isFavorites: Boolean,
        session: JmRequestSession,
    ): JmOnlineLibraryPage = withContext(Dispatchers.IO) {
        val body = execute(requestSpec, session = session)
        if (isFavorites) {
            val (page, folders) = JmOnlineLibraryPolicy.parseFavoritesResponse(body)
            page.copy(folders = folders)
        } else {
            JmOnlineLibraryPolicy.parseHistoryResponse(body)
        }
    }

    private suspend fun getObject(requestSpec: JmOnlineLibraryRequest, session: JmRequestSession): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(execute(requestSpec, session = session))
        }

    private suspend fun postForm(requestSpec: JmOnlineLibraryRequest, session: JmRequestSession): String =
        withContext(Dispatchers.IO) {
            require(requestSpec.method == "POST") { "JM 写操作请求方法无效" }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                requestSpec.formParameters.forEach { (key, value) -> addFormDataPart(key, value) }
            }.build()
            execute(requestSpec, body, session)
        }

    private suspend fun execute(
        requestSpec: JmOnlineLibraryRequest,
        requestBody: okhttp3.RequestBody? = null,
        session: JmRequestSession,
    ): String = withContext(Dispatchers.IO) {
        val baseUrl = JmRouteManager.baseUrl
        if (baseUrl.isBlank()) throw IOException("JM 没有可用线路")
        JmNetworkModule.ensureAgeCookie()
        val urlBuilder = baseUrl.toHttpUrl().newBuilder().addPathSegments(requestSpec.path.trimStart('/'))
        requestSpec.queryParameters.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val url = urlBuilder.build()
        val builder = Request.Builder().url(url).method(requestSpec.method, requestBody)
            .header("User-Agent", com.zycomic.app.net.JmApiInterceptor.USER_AGENT)
            .header("Origin", com.zycomic.app.net.JmApiInterceptor.ORIGIN)
            .header("Referer", com.zycomic.app.net.JmApiInterceptor.REFERER)
            .header("X-Requested-With", com.zycomic.app.net.JmApiInterceptor.PACKAGE_NAME)
            .tag(JmRequestSession::class.java, session)
        if (requestSpec.method == "GET") builder.get()
        val request = builder.build()
        val sessionCookieJar = session.snapshotCookieJar(JmNetworkModule.client.cookieJar, url)
        val requestClient = JmNetworkModule.client.newBuilder()
            .cookieJar(sessionCookieJar)
            .build()
        requestClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            JmRequestSession.requireCurrent(session)
            val error = if (requestSpec.method == "GET") {
                JmOnlineLibraryPolicy.listResponseError(
                    isSuccessful = response.isSuccessful,
                    httpCode = response.code,
                    apiErrorMessage = response.header(JM_ERROR_MESSAGE_HEADER),
                )
            } else {
                JmOnlineLibraryPolicy.writeResponseError(
                    isSuccessful = response.isSuccessful,
                    httpCode = response.code,
                    apiErrorMessage = response.header(JM_ERROR_MESSAGE_HEADER),
                )
            }
            error?.let { throw IOException(it) }
            body
        }
    }

    private fun captureSession(): JmRequestSession = JmRequestSession.capture()
        ?: throw NotLoggedInException("请先登录 JM 账号")

    private fun validateIds(ids: Collection<String>): List<String> {
        require(ids.isNotEmpty()) { "漫画 ID 不能为空" }
        val validated = ids.map(String::trim).distinct()
        require(validated.all { it.matches(Regex("\\d+")) }) { "漫画 ID 无效" }
        return validated
    }

    private fun readFavoriteFlag(album: JSONObject): Boolean {
        val data = album.optJSONObject("data") ?: album
        val value = data.opt("is_favorite")
        return when (value) {
            true, 1, "1", "true" -> true
            false, 0, "0", "false" -> false
            else -> throw IOException("JM /album 响应缺少有效 is_favorite")
        }
    }

    private const val JM_ERROR_MESSAGE_HEADER = "X-JM-Error-Msg"
    private const val REQUEST_INTERVAL_MS = 1_500L
}
