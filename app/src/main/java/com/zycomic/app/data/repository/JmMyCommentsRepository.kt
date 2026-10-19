package com.zycomic.app.data.repository

import com.zycomic.app.net.JmApiInterceptor
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRequestSession
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.reader.JmResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Read-only JM account comments. Requests and returned data stay scoped to one captured JM session. */
object JmMyCommentsRepository {
    data class Page(
        val uid: String,
        val page: Int,
        val total: Int?,
        val comments: List<com.zycomic.app.data.dto.JmComment>,
        val hasMore: Boolean,
    )

    suspend fun getPage(page: Int): Page = withContext(Dispatchers.IO) {
        val session = JmRequestSession.capture() ?: throw NotLoggedInException("请先登录 JM 账号")
        val uid = JmAuthStore.uid().orEmpty()
        val spec = JmMyCommentsPolicy.request(uid, page)
        JmRequestSession.requireCurrent(session)
        val baseUrl = JmRouteManager.baseUrl
        if (baseUrl.isBlank()) throw IOException("JM 没有可用线路")
        JmNetworkModule.ensureAgeCookie()
        val urlBuilder = baseUrl.toHttpUrl().newBuilder().addPathSegments(spec.path.trimStart('/'))
        spec.queryParameters.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val url = urlBuilder.build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", JmApiInterceptor.USER_AGENT)
            .header("Origin", JmApiInterceptor.ORIGIN)
            .header("Referer", JmApiInterceptor.REFERER)
            .header("X-Requested-With", JmApiInterceptor.PACKAGE_NAME)
            .tag(JmRequestSession::class.java, session)
            .build()
        val sessionCookieJar = session.snapshotCookieJar(JmNetworkModule.client.cookieJar, url)
        val client = JmNetworkModule.client.newBuilder().cookieJar(sessionCookieJar).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            JmRequestSession.requireCurrent(session)
            JmMyCommentsPolicy.responseError(
                isSuccessful = response.isSuccessful,
                httpCode = response.code,
                apiErrorMessage = response.header("X-JM-Error-Msg"),
            )?.let { throw IOException(it) }
            val forumPage = JmResponseParser.forumPage(
                runCatching { JSONObject(body) }.getOrElse {
                    throw IOException("JM /forum 响应无法解析", it)
                },
            )
            JmRequestSession.requireCurrent(session)
            Page(
                uid = uid,
                page = page,
                total = forumPage.total,
                comments = forumPage.comments,
                hasMore = JmMyCommentsPolicy.hasMore(forumPage.total, forumPage.comments.size, page),
            )
        }
    }
}
