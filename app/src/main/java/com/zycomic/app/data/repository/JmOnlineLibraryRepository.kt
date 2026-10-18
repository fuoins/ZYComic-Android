package com.zycomic.app.data.repository

import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.reader.JmSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.history.interactor.GetHistory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import tachiyomi.domain.manga.repository.MangaRepository as LocalMangaRepository

data class JmOnlineLibraryItem(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val image: String,
)

data class JmOnlineLibraryPage(
    val items: List<JmOnlineLibraryItem>,
    val total: Int,
)

/** JM-only account endpoints; this repository never reads or mutates Manwa state. */
object JmOnlineLibraryRepository {
    const val PAGE_SIZE = JmOnlineLibraryPolicy.PAGE_SIZE

    suspend fun favorites(page: Int, order: String): JmOnlineLibraryPage {
        return getPage(JmOnlineLibraryPolicy.favoritesRequest(page, order))
    }

    suspend fun history(page: Int): JmOnlineLibraryPage = getPage(JmOnlineLibraryPolicy.historyRequest(page))

    /** Read existing JM reader progress from the local SQLDelight history; it carries no synthetic remote timestamp. */
    suspend fun localHistory(): List<JmOnlineLibraryItem> = withContext(Dispatchers.IO) {
        val localMangas = Injekt.get<LocalMangaRepository>().getMangaBySourceId(JmSource.id).associateBy { it.id }
        val history = Injekt.get<GetHistory>().subscribe(
            query = "",
            unfinishedManga = null,
            unfinishedChapter = null,
            nonLibraryEntries = null,
        ).first()

        history.mapNotNull { entry ->
            val manga = localMangas[entry.mangaId] ?: return@mapNotNull null
            val albumId = JM_ALBUM_URL.matchEntire(manga.url)?.groupValues?.get(1) ?: return@mapNotNull null
            JmOnlineLibraryItem(
                id = albumId,
                name = entry.title.ifBlank { manga.title },
                author = manga.author.orEmpty(),
                description = manga.description.orEmpty(),
                image = manga.thumbnailUrl.orEmpty(),
            )
        }.distinctBy { it.id }
    }

    private suspend fun getPage(requestSpec: JmOnlineLibraryRequest): JmOnlineLibraryPage = withContext(Dispatchers.IO) {
        if (!JmAuthStore.isLoggedIn) throw NotLoggedInException("请先登录 JM 账号")
        val baseUrl = JmRouteManager.baseUrl
        if (baseUrl.isBlank()) throw IOException("JM 没有可用线路")
        JmNetworkModule.ensureAgeCookie()
        val urlBuilder = baseUrl.toHttpUrl().newBuilder().addPathSegments(requestSpec.path.trimStart('/'))
        requestSpec.queryParameters.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val request = Request.Builder().url(urlBuilder.build()).method(requestSpec.method, null)
            .header("User-Agent", com.zycomic.app.net.JmApiInterceptor.USER_AGENT)
            .header("Origin", com.zycomic.app.net.JmApiInterceptor.ORIGIN)
            .header("Referer", com.zycomic.app.net.JmApiInterceptor.REFERER)
            .header("X-Requested-With", com.zycomic.app.net.JmApiInterceptor.PACKAGE_NAME)
            .build()
        JmNetworkModule.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            JmOnlineLibraryPolicy.listResponseError(
                isSuccessful = response.isSuccessful,
                httpCode = response.code,
                apiErrorMessage = response.header(JM_ERROR_MESSAGE_HEADER),
            )?.let { throw IOException(it) }
            val root = runCatching { JSONObject(body) }.getOrElse { throw IOException("JM 列表响应不是有效 JSON", it) }
            val data = root.optJSONObject("data") ?: root
            val rows = data.optJSONArray("list") ?: throw IOException("JM 列表响应缺少 list")
            val total = data.optInt("total", -1).takeIf { it >= 0 }
                ?: throw IOException("JM 列表响应缺少有效 total")
            val items = (0 until rows.length()).map { index ->
                val row = rows.optJSONObject(index) ?: throw IOException("JM 列表第 ${index + 1} 项格式无效")
                val id = row.optString("id").trim()
                val name = row.optString("name").trim()
                if (!id.matches(Regex("\\d+")) || name.isBlank()) throw IOException("JM 列表项缺少有效 id/name")
                JmOnlineLibraryItem(
                    id = id,
                    name = name,
                    author = row.readDisplayString("author"),
                    description = row.optString("description").trim(),
                    image = row.optString("image").trim(),
                )
            }.distinctBy { it.id }
            JmOnlineLibraryPage(items, total)
        }
    }

    private val JM_ALBUM_URL = Regex("/album/([0-9]+)")
    private const val JM_ERROR_MESSAGE_HEADER = "X-JM-Error-Msg"
}

private fun JSONObject.readDisplayString(key: String): String = when (val value = opt(key)) {
    is JSONArray -> (0 until value.length()).mapNotNull { index ->
        when (val entry = value.opt(index)) {
            null, JSONObject.NULL -> null
            is JSONObject -> entry.optString("name").trim().takeIf(String::isNotBlank)
            else -> entry.toString().trim().takeIf(String::isNotBlank)
        }
    }.distinct().joinToString(", ")
    is JSONObject -> value.optString("name").trim()
    null, JSONObject.NULL -> ""
    else -> value.toString().trim()
}
