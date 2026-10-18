package com.zycomic.app.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmOnlineLibraryPolicyTest {
    @Test
    fun `all favorites are read-only GET requests with empty folder including ungrouped albums`() {
        val byFavorites = JmOnlineLibraryPolicy.favoritesRequest(page = 1, order = "mr")
        assertEquals("GET", byFavorites.method)
        assertEquals("/favorite", byFavorites.path)
        assertEquals(mapOf("folder_id" to "", "page" to "1", "o" to "mr"), byFavorites.queryParameters)

        val byImages = JmOnlineLibraryPolicy.favoritesRequest(page = 3, order = "mp")
        assertEquals("GET", byImages.method)
        assertEquals(mapOf("folder_id" to "", "page" to "3", "o" to "mp"), byImages.queryParameters)
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.favoritesRequest(1, "unknown") }
    }

    @Test
    fun `watch list is album-level GET without time or mutation parameters`() {
        val request = JmOnlineLibraryPolicy.historyRequest(page = 1)
        assertEquals("GET", request.method)
        assertEquals("/watch_list", request.path)
        assertEquals(mapOf("page" to "1"), request.queryParameters)
        assertEquals(20, JmOnlineLibraryPolicy.PAGE_SIZE)
    }

    @Test
    fun `list response failures are non-empty including code 200 API error messages`() {
        val apiError = JmOnlineLibraryPolicy.listResponseError(
            isSuccessful = true,
            httpCode = 200,
            apiErrorMessage = "账号需要重新登录",
        )
        assertEquals("JM 列表请求失败：账号需要重新登录", apiError)
        assertTrue(apiError!!.isNotBlank())

        val httpError = JmOnlineLibraryPolicy.listResponseError(
            isSuccessful = false,
            httpCode = 503,
            apiErrorMessage = null,
        )
        assertEquals("JM 列表请求失败（HTTP 503）", httpError)
        assertTrue(httpError!!.isNotBlank())
    }

    @Test
    fun `request pages are one-based`() {
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.favoritesRequest(0, "mr") }
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.historyRequest(0) }
    }

    @Test
    fun `pagination stops on a repeated out of range last page`() {
        val existingIds = (1..45).map(Int::toString)
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 45, existingItemIds = existingIds, nextPageIds = listOf("41", "42", "43", "44", "45")))
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 50, existingItemIds = existingIds, nextPageIds = listOf("41", "42", "43", "44", "45")))
    }

    @Test
    fun `history pagination stops when out of range repeats the first page`() {
        val firstPageIds = (1..20).map(Int::toString)
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 40, existingItemIds = firstPageIds, nextPageIds = firstPageIds))
    }

    @Test
    fun `pagination continues only when next page has new albums under total`() {
        val existingIds = (1..20).map(Int::toString)
        val nextPageIds = (21..40).map(Int::toString)
        assertTrue(JmOnlineLibraryPolicy.hasMore(total = 45, existingItemIds = existingIds, nextPageIds = nextPageIds))
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 40, existingItemIds = existingIds, nextPageIds = nextPageIds))
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 45, existingItemIds = existingIds, nextPageIds = emptyList()))
    }

    @Test
    fun `history merges local and remote by album id preserving local metadata and remote-only items`() {
        val local = listOf(item("10", "local title"), item("20", "local only"))
        val remote = listOf(item("10", "remote title"), item("30", "server only"))

        assertEquals(
            listOf(item("10", "local title"), item("20", "local only"), item("30", "server only")),
            JmOnlineLibraryPolicy.mergeHistory(local, remote),
        )
    }

    @Test
    fun `history request error is not represented as an empty list`() {
        assertEquals(
            JmOnlineLibraryContentState.ERROR,
            JmOnlineLibraryPolicy.contentState(hasItems = false, loading = false, error = "JM API error"),
        )
        assertEquals(
            JmOnlineLibraryContentState.EMPTY,
            JmOnlineLibraryPolicy.contentState(hasItems = false, loading = false, error = null),
        )
    }

    private fun item(id: String, name: String) = JmOnlineLibraryItem(
        id = id,
        name = name,
        author = "",
        description = "",
        image = "",
    )
}
