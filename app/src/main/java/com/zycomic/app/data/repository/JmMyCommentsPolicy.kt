package com.zycomic.app.data.repository

import org.json.JSONObject
import java.io.IOException

/** Pure, offline-testable contract for JM profile comment history and narrowly scoped deletion. */
internal object JmMyCommentsPolicy {
    const val PAGE_SIZE = 10

    data class Request(
        val method: String,
        val path: String,
        val queryParameters: Map<String, String>,
        val formParameters: Map<String, String> = emptyMap(),
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

    fun canDelete(commentId: String, aid: String, bid: String? = null, nid: String? = null): Boolean =
        validPositiveId(commentId) && validPositiveId(aid) && validOptionalId(bid) && validOptionalId(nid)

    fun deleteRequest(commentId: String, aid: String, bid: String? = null, nid: String? = null): Request {
        require(canDelete(commentId, aid, bid, nid)) { "JM 评论缺少有效 CID/AID/BID/NID，已禁止删除" }
        val formParameters = linkedMapOf("comment_id" to commentId, "aid" to aid)
        bid?.takeIf(String::isNotBlank)?.let { formParameters["bid"] = it }
        nid?.takeIf(String::isNotBlank)?.let { formParameters["nid"] = it }
        return Request(
            method = "POST",
            path = "/comment_delete",
            queryParameters = emptyMap(),
            formParameters = formParameters,
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

    fun parseDeleteResponse(body: String) {
        val root = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("JM 删除评论响应无法解析", it)
        }
        val result = root.optJSONObject("data") ?: root
        if (result.opt("status") != "ok") {
            val message = result.optString("msg").trim().take(256)
            throw IOException(message.ifBlank { "JM 删除评论未确认成功" })
        }
    }

    fun isCurrentSession(requestRevision: Long, currentRevision: Long): Boolean =
        requestRevision == currentRevision

    private fun validPositiveId(id: String): Boolean = id.matches(Regex("[0-9]+")) && id.any { it != '0' }

    private fun validOptionalId(id: String?): Boolean = id == null || id.isBlank() || validPositiveId(id)
}
