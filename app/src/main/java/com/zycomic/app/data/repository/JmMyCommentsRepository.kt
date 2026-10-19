package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.JmComment
import com.zycomic.app.net.JmApiInterceptor
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRequestSession
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.reader.JmResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
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
        val body = execute(spec, session)
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

    /** Delete only the selected comment under the identity that initiated the confirmation. */
    suspend fun deleteComment(comment: JmComment, expectedUid: String, expectedRevision: Long) = withContext(Dispatchers.IO) {
        if (JmAuthStore.sessionRevision != expectedRevision || JmAuthStore.uid() != expectedUid) {
            throw IOException("JM 登录账号已切换，请重新加载后再操作")
        }
        val session = JmRequestSession.capture() ?: throw NotLoggedInException("请先登录 JM 账号")
        JmRequestSession.requireCurrent(session)
        if (session.revision != expectedRevision || JmAuthStore.uid() != expectedUid) {
            throw IOException("JM 登录账号已切换，请重新加载后再操作")
        }
        val response = execute(JmMyCommentsPolicy.deleteRequest(comment.id, comment.aid, comment.bid, comment.nid), session)
        JmMyCommentsPolicy.parseDeleteResponse(response)
        JmRequestSession.requireCurrent(session)
        if (JmAuthStore.uid() != expectedUid) throw IOException("JM 登录账号已切换，请重新加载后再操作")
    }

    private suspend fun execute(spec: JmMyCommentsPolicy.Request, session: JmRequestSession): String = withContext(Dispatchers.IO) {
        JmRequestSession.requireCurrent(session)
        val baseUrl = JmRouteManager.baseUrl
        if (baseUrl.isBlank()) throw IOException("JM 没有可用线路")
        JmNetworkModule.ensureAgeCookie()
        val urlBuilder = baseUrl.toHttpUrl().newBuilder().addPathSegments(spec.path.trimStart('/'))
        spec.queryParameters.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val url = urlBuilder.build()
        val requestBody = if (spec.method == "POST") {
            MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                spec.formParameters.forEach { (key, value) -> addFormDataPart(key, value) }
            }.build()
        } else {
            null
        }
        val request = Request.Builder()
            .url(url)
            .method(spec.method, requestBody)
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
            JmRequestSession.requireCurrent(session)
            body
        }
    }
}
