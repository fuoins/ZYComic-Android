package com.zycomic.app.data.repository

import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRouteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
            if (!response.isSuccessful) throw IOException("JM 列表请求失败（HTTP ${response.code}）")
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
