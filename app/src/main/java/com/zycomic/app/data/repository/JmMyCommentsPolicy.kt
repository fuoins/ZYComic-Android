package com.zycomic.app.data.repository

/** Pure, offline-testable contract for the read-only JM profile comment history endpoint. */
internal object JmMyCommentsPolicy {
    const val PAGE_SIZE = 10

    data class Request(
        val method: String,
        val path: String,
        val queryParameters: Map<String, String>,
    )

    fun request(uid: String, page: Int): Request {
        require(uid.matches(Regex("[0-9]+")) && uid.any { it != '0' }) { "JM 登录资料缺少有效用户ID，请退出后重新登录" }
        require(page > 0) { "JM 留言记录页码无效" }
        return Request(
            method = "GET",
            path = "/forum",
            queryParameters = mapOf("uid" to uid, "page" to page.toString()),
        )
    }

    fun hasMore(total: Int?, itemCount: Int, page: Int): Boolean = when {
        total != null -> page * PAGE_SIZE < total
        else -> itemCount >= PAGE_SIZE
    }

    fun responseError(isSuccessful: Boolean, httpCode: Int, apiErrorMessage: String?): String? = when {
        httpCode == 401 -> "JM 登录已失效，请重新登录"
        !isSuccessful -> "JM 留言记录请求失败（HTTP $httpCode）"
        !apiErrorMessage.isNullOrBlank() -> "JM 留言记录请求失败：${apiErrorMessage.take(256)}"
        else -> null
    }

    fun isCurrentSession(requestRevision: Long, currentRevision: Long): Boolean =
        requestRevision == currentRevision
}
