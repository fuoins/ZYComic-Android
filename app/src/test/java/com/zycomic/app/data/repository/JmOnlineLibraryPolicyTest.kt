package com.zycomic.app.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmOnlineLibraryPolicyTest {
    @Test
    fun `favorites are read-only GET requests with default folder and supported order`() {
        val byFavorites = JmOnlineLibraryPolicy.favoritesRequest(page = 1, order = "mr")
        assertEquals("GET", byFavorites.method)
        assertEquals("/favorite", byFavorites.path)
        assertEquals(mapOf("folder_id" to "0", "page" to "1", "o" to "mr"), byFavorites.queryParameters)

        val byImages = JmOnlineLibraryPolicy.favoritesRequest(page = 3, order = "mp")
        assertEquals("GET", byImages.method)
        assertEquals(mapOf("folder_id" to "0", "page" to "3", "o" to "mp"), byImages.queryParameters)
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
    fun `pagination continues only when next page has new albums under total`() {
        val existingIds = (1..20).map(Int::toString)
        val nextPageIds = (21..40).map(Int::toString)
        assertTrue(JmOnlineLibraryPolicy.hasMore(total = 45, existingItemIds = existingIds, nextPageIds = nextPageIds))
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 40, existingItemIds = existingIds, nextPageIds = nextPageIds))
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 45, existingItemIds = existingIds, nextPageIds = emptyList()))
    }
}
