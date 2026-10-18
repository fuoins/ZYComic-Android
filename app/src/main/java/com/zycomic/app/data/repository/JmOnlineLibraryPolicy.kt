package com.zycomic.app.data.repository

/** Pure request contract for the read-only JM account lists. */
internal data class JmOnlineLibraryRequest(
    val method: String,
    val path: String,
    val queryParameters: Map<String, String>,
)

internal enum class JmOnlineLibraryContentState {
    LOADING,
    ERROR,
    EMPTY,
    CONTENT,
}

internal object JmOnlineLibraryPolicy {
    const val PAGE_SIZE = 20

    fun favoritesRequest(page: Int, order: String): JmOnlineLibraryRequest {
        require(page >= 1) { "页码必须从 1 开始" }
        require(order == "mr" || order == "mp") { "JM 收藏排序参数无效" }
        return JmOnlineLibraryRequest(
            method = "GET",
            path = "/favorite",
            // The API uses an empty folder ID for all favorites, including ungrouped albums.
            queryParameters = mapOf("folder_id" to "", "page" to page.toString(), "o" to order),
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

    fun listResponseError(isSuccessful: Boolean, httpCode: Int, apiErrorMessage: String?): String? = when {
        !isSuccessful -> "JM 列表请求失败（HTTP $httpCode）"
        !apiErrorMessage.isNullOrBlank() -> "JM 列表请求失败：${apiErrorMessage.take(256)}"
        else -> null
    }

    /** Prefer local reader metadata/order for matching albums; append remote-only records unchanged. */
    fun mergeHistory(
        localItems: List<JmOnlineLibraryItem>,
        remoteItems: List<JmOnlineLibraryItem>,
    ): List<JmOnlineLibraryItem> = buildList {
        val seenAlbumIds = mutableSetOf<String>()
        (localItems + remoteItems).forEach { item ->
            val albumId = item.id.trim()
            if (albumId.isNotEmpty() && seenAlbumIds.add(albumId)) add(item)
        }
    }

    /** Failed loading must remain visibly distinct from a valid empty response. */
    fun contentState(hasItems: Boolean, loading: Boolean, error: String?): JmOnlineLibraryContentState = when {
        hasItems -> JmOnlineLibraryContentState.CONTENT
        loading -> JmOnlineLibraryContentState.LOADING
        error != null -> JmOnlineLibraryContentState.ERROR
        else -> JmOnlineLibraryContentState.EMPTY
    }

    /** Stop when the API repeats a page (including an out-of-range final page). */
    fun hasMore(total: Int, existingItemIds: Collection<String>, nextPageIds: Collection<String>): Boolean {
        val existing = existingItemIds.toSet()
        val novelCount = nextPageIds.asSequence().filterNot { it in existing }.distinct().count()
        return novelCount > 0 && existing.size + novelCount < total
    }
}
