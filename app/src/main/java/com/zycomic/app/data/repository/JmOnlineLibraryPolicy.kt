package com.zycomic.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Pure request/response contract for JM account library endpoints. */
internal data class JmOnlineLibraryRequest(
    val method: String,
    val path: String,
    val queryParameters: Map<String, String> = emptyMap(),
    val formParameters: Map<String, String> = emptyMap(),
)

internal data class JmOnlineLibraryWriteResult(
    val status: String,
    val message: String,
    val type: String = "",
)

internal enum class JmOnlineLibraryContentState {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT,
}

internal object JmOnlineLibraryPolicy {
    const val PAGE_SIZE = 20

    fun favoritesRequest(page: Int, order: String = "mr", folderId: String = ""): JmOnlineLibraryRequest {
        require(page >= 1) { "页码必须从 1 开始" }
        require(order == "mr" || order == "mp") { "JM 收藏排序参数无效" }
        return JmOnlineLibraryRequest(
            method = "GET",
            path = "/favorite",
            // The API uses an empty folder ID for all favorites, including ungrouped albums.
            queryParameters = mapOf("folder_id" to folderId, "page" to page.toString(), "o" to order),
        )
    }

    fun favoriteRequest(aid: String): JmOnlineLibraryRequest {
        require(validIds(aid)) { "漫画 ID 无效" }
        return JmOnlineLibraryRequest(
            method = "POST",
            path = "/favorite",
            formParameters = mapOf("aid" to aid),
        )
    }

    fun albumRequest(aid: String): JmOnlineLibraryRequest {
        require(aid.matches(Regex("\\d+"))) { "漫画 ID 无效" }
        return JmOnlineLibraryRequest(
            method = "GET",
            path = "/album",
            queryParameters = mapOf("id" to aid),
        )
    }

    fun favoriteFolderAddRequest(name: String): JmOnlineLibraryRequest {
        require(name.isNotBlank()) { "收藏夹名称不能为空" }
        return JmOnlineLibraryRequest(
            method = "POST",
            path = "/favorite_folder",
            formParameters = mapOf("type" to "add", "folder_name" to name, "folder_id" to "", "aid" to ""),
        )
    }

    fun favoriteFolderEditRequest(folderId: String, name: String): JmOnlineLibraryRequest {
        require(folderId.isNotBlank()) { "收藏夹 ID 不能为空" }
        require(name.isNotBlank()) { "收藏夹名称不能为空" }
        return JmOnlineLibraryRequest(
            method = "POST",
            path = "/favorite_folder",
            formParameters = mapOf("type" to "edit", "folder_id" to folderId, "folder_name" to name),
        )
    }

    fun favoriteFolderMoveRequest(folderId: String, aid: String, folderName: String = ""): JmOnlineLibraryRequest {
        require(folderId.isNotBlank()) { "收藏夹 ID 不能为空" }
        require(validIds(aid)) { "漫画 ID 无效" }
        return JmOnlineLibraryRequest(
            method = "POST",
            path = "/favorite_folder",
            formParameters = mapOf(
                "type" to "move",
                "folder_id" to folderId,
                "aid" to aid,
                "folder_name" to folderName,
            ),
        )
    }

    fun favoriteFolderDeleteRequest(folderId: String): JmOnlineLibraryRequest {
        require(folderId.isNotBlank()) { "收藏夹 ID 不能为空" }
        return JmOnlineLibraryRequest(
            method = "POST",
            path = "/favorite_folder",
            formParameters = mapOf("type" to "del", "folder_id" to folderId),
        )
    }

    fun historyRequest(page: Int): JmOnlineLibraryRequest {
        require(page >= 1) { "页码必须从 1 开始" }
        return JmOnlineLibraryRequest(
            method = "GET",
            path = "/watch_list",
            queryParameters = mapOf("page" to page.toString()),
        )
    }

    fun deleteHistoryRequest(id: String): JmOnlineLibraryRequest {
        require(id.matches(Regex("\\d+"))) { "观看记录 ID 无效" }
        return JmOnlineLibraryRequest(
            method = "POST",
            path = "/watch_list",
            formParameters = mapOf("id" to id),
        )
    }

    fun parseFavoritesResponse(body: String): Pair<JmOnlineLibraryPage, List<JmFavoriteFolder>> {
        val data = parseObject(body, "JM 收藏列表")
        val rows = data.optJSONArray("list") ?: throw IOException("JM 收藏响应缺少 list")
        val total = readTotal(data)
        val folders = data.optJSONArray("folder_list") ?: throw IOException("JM 收藏响应缺少 folder_list")
        return parsePage(rows, total) to parseFolders(folders)
    }

    fun parseHistoryResponse(body: String): JmOnlineLibraryPage {
        val data = parseObject(body, "JM 观看记录")
        val rows = data.optJSONArray("list") ?: throw IOException("JM 观看记录响应缺少 list")
        return parsePage(rows, readTotal(data))
    }

    fun parseFavoriteWriteResponse(body: String): JmOnlineLibraryWriteResult = parseStringStatus(body, "JM 收藏操作")

    fun parseFavoriteFolderWriteResponse(body: String): JmOnlineLibraryWriteResult =
        parseStringStatus(body, "JM 收藏夹操作")

    fun parseDeleteHistoryResponse(body: String): JmOnlineLibraryWriteResult {
        val data = parseObject(body, "JM 删除观看记录")
        val status = data.opt("status")
        if (status != 1 && status != "1") throw writeError(data, "JM 删除观看记录失败")
        return JmOnlineLibraryWriteResult("1", data.optString("msg").trim())
    }

    fun listResponseError(isSuccessful: Boolean, httpCode: Int, apiErrorMessage: String?): String? = when {
        !isSuccessful -> "JM 列表请求失败（HTTP $httpCode）"
        !apiErrorMessage.isNullOrBlank() -> "JM 列表请求失败：${apiErrorMessage.take(256)}"
        else -> null
    }

    fun writeResponseError(isSuccessful: Boolean, httpCode: Int, apiErrorMessage: String?): String? = when {
        !isSuccessful -> "JM 写操作失败（HTTP $httpCode）"
        !apiErrorMessage.isNullOrBlank() -> "JM 写操作失败：${apiErrorMessage.take(256)}"
        else -> null
    }

    fun favoritePageCount(total: Int): Int = if (total <= 0) 0 else (total + PAGE_SIZE - 1) / PAGE_SIZE

    fun clampFavoritePage(page: Int, total: Int): Int {
        require(page >= 1) { "页码必须从 1 开始" }
        return page.coerceAtMost(favoritePageCount(total).coerceAtLeast(1))
    }

    /** Failed loading must remain visibly distinct from a valid empty response. */
    fun contentState(hasItems: Boolean, loading: Boolean, error: String?): JmOnlineLibraryContentState = when {
        hasItems -> JmOnlineLibraryContentState.CONTENT
        loading -> JmOnlineLibraryContentState.LOADING
        error != null -> JmOnlineLibraryContentState.ERROR
        else -> JmOnlineLibraryContentState.EMPTY
    }

    /** Page requests must not wait for a lazy list: the first response makes that list available to compose. */
    fun shouldRequestPage(loggedIn: Boolean, hasMore: Boolean, generationIsCurrent: Boolean): Boolean =
        loggedIn && hasMore && generationIsCurrent

    /** Scroll only after successful content has produced a real lazy-list layout. */
    fun shouldScrollToTop(hasVisibleItems: Boolean, loading: Boolean, hasError: Boolean, listLaidOut: Boolean): Boolean =
        hasVisibleItems && !loading && !hasError && listLaidOut

    /** Stop when the API repeats a page (watch_list repeats page one when out of range). */
    fun hasMore(total: Int, existingItemIds: Collection<String>, nextPageIds: Collection<String>): Boolean {
        val existing = existingItemIds.toSet()
        val novelCount = nextPageIds.asSequence().filterNot { it in existing }.distinct().count()
        return novelCount > 0 && existing.size + novelCount < total
    }

    private fun parseObject(body: String, label: String): JSONObject {
        val root = runCatching { JSONObject(body) }.getOrElse { throw IOException("$label 响应不是有效 JSON", it) }
        val data = root.optJSONObject("data") ?: root
        val status = data.opt("status")
        if (status == "fail" || status == "error" || status == 0 || status == "0") throw writeError(data, "$label 请求失败")
        return data
    }

    private fun parseStringStatus(body: String, label: String): JmOnlineLibraryWriteResult {
        val data = parseObject(body, label)
        val status = data.opt("status")
        if (status != "ok") throw writeError(data, "$label 失败")
        return JmOnlineLibraryWriteResult("ok", data.optString("msg").trim(), data.optString("type").trim())
    }

    private fun writeError(data: JSONObject, fallback: String): IOException {
        val message = data.optString("msg").trim().takeIf(String::isNotBlank)
        return IOException(message?.take(256) ?: fallback)
    }

    private fun readTotal(data: JSONObject): Int = data.optInt("total", -1).takeIf { it >= 0 }
        ?: throw IOException("JM 列表响应缺少有效 total")

    private fun parsePage(rows: JSONArray, total: Int): JmOnlineLibraryPage {
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
        return JmOnlineLibraryPage(items, total)
    }

    private fun parseFolders(rows: JSONArray): List<JmFavoriteFolder> = (0 until rows.length()).map { index ->
        val row = rows.optJSONObject(index) ?: throw IOException("JM 资料夹第 ${index + 1} 项格式无效")
        val id = row.optString("FID").trim()
        val name = row.optString("name").trim()
        if (id.isBlank() || name.isBlank()) throw IOException("JM 资料夹项缺少有效 FID/name")
        JmFavoriteFolder(id, name)
    }.distinctBy { it.id }

    private fun validIds(ids: String): Boolean = ids.split(',').all { it.trim().matches(Regex("\\d+")) }
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
