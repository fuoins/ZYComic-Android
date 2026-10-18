package com.zycomic.app.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class JmOnlineLibraryPolicyTest {
    @Test
    fun `favorites request includes requested folder order and page`() {
        val request = JmOnlineLibraryPolicy.favoritesRequest(page = 1)
        assertEquals("GET", request.method)
        assertEquals("/favorite", request.path)
        assertEquals(mapOf("folder_id" to "", "page" to "1", "o" to "mr"), request.queryParameters)

        val folderRequest = JmOnlineLibraryPolicy.favoritesRequest(page = 3, order = "mp", folderId = "FID_7")
        assertEquals(mapOf("folder_id" to "FID_7", "page" to "3", "o" to "mp"), folderRequest.queryParameters)
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.favoritesRequest(1, "unknown") }
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.favoritesRequest(0) }
    }

    @Test
    fun `favorite toggle supports comma separated IDs as multipart form field`() {
        val request = JmOnlineLibraryPolicy.favoriteRequest("10,20,30")
        assertEquals("POST", request.method)
        assertEquals("/favorite", request.path)
        assertEquals(mapOf("aid" to "10,20,30"), request.formParameters)
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.favoriteRequest("10,no") }
    }

    @Test
    fun `favorite folder request fields match each documented operation exactly`() {
        assertEquals(
            mapOf("type" to "add", "folder_name" to "Reading", "folder_id" to "", "aid" to ""),
            JmOnlineLibraryPolicy.favoriteFolderAddRequest("Reading").formParameters,
        )
        assertEquals(
            mapOf("type" to "edit", "folder_id" to "FID_7", "folder_name" to "Later"),
            JmOnlineLibraryPolicy.favoriteFolderEditRequest("FID_7", "Later").formParameters,
        )
        assertEquals(
            mapOf("type" to "move", "folder_id" to "FID_7", "aid" to "10,20", "folder_name" to "Reading"),
            JmOnlineLibraryPolicy.favoriteFolderMoveRequest("FID_7", "10,20", "Reading").formParameters,
        )
        assertEquals(
            mapOf("type" to "del", "folder_id" to "FID_7"),
            JmOnlineLibraryPolicy.favoriteFolderDeleteRequest("FID_7").formParameters,
        )
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.favoriteFolderAddRequest(" ") }
    }

    @Test
    fun `watch list reads pages and deletes by id using documented fields`() {
        val request = JmOnlineLibraryPolicy.historyRequest(page = 1)
        assertEquals("GET", request.method)
        assertEquals("/watch_list", request.path)
        assertEquals(mapOf("page" to "1"), request.queryParameters)
        assertEquals(20, JmOnlineLibraryPolicy.PAGE_SIZE)

        val delete = JmOnlineLibraryPolicy.deleteHistoryRequest("1480008")
        assertEquals("POST", delete.method)
        assertEquals("/watch_list", delete.path)
        assertEquals(mapOf("id" to "1480008"), delete.formParameters)
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.deleteHistoryRequest("bad") }
    }

    @Test
    fun `album lookup uses documented is favorite read endpoint`() {
        val request = JmOnlineLibraryPolicy.albumRequest("1480008")
        assertEquals("GET", request.method)
        assertEquals("/album", request.path)
        assertEquals(mapOf("id" to "1480008"), request.queryParameters)
    }

    @Test
    fun `favorite response parses items and uppercase FID folder list`() {
        val (page, folders) = JmOnlineLibraryPolicy.parseFavoritesResponse(
            """{"list":[{"id":"10","name":"Title","author":"A","description":"","image":""}],"folder_list":[{"FID":"FID_7","UID":"22","name":"Reading"}],"total":1,"count":1}""",
        )
        assertEquals(listOf(item("10", "Title").copy(author = "A")), page.items)
        assertEquals(1, page.total)
        assertEquals(listOf(JmFavoriteFolder("FID_7", "Reading")), folders)
    }

    @Test
    fun `valid empty favorites and watch responses are distinct from malformed responses`() {
        val (favorites, folders) = JmOnlineLibraryPolicy.parseFavoritesResponse(
            """{"list":[],"folder_list":[],"total":0,"count":0}""",
        )
        assertTrue(favorites.items.isEmpty())
        assertTrue(folders.isEmpty())
        assertEquals(0, favorites.total)

        val history = JmOnlineLibraryPolicy.parseHistoryResponse("""{"list":[],"total":0}""")
        assertTrue(history.items.isEmpty())
        assertEquals(0, history.total)

        assertThrows(IOException::class.java) {
            JmOnlineLibraryPolicy.parseFavoritesResponse("""{"list":[],"total":0}""")
        }
        assertThrows(IOException::class.java) {
            JmOnlineLibraryPolicy.parseHistoryResponse("""{"list":[]}""")
        }
        assertThrows(IOException::class.java) {
            JmOnlineLibraryPolicy.parseFavoritesResponse("not json")
        }
    }

    @Test
    fun `write responses accept documented success and reject API failure or incomplete JSON`() {
        assertEquals(
            "add",
            JmOnlineLibraryPolicy.parseFavoriteWriteResponse(
                """{"status":"ok","msg":"added","type":"add"}""",
            ).type,
        )
        assertEquals(
            "ok",
            JmOnlineLibraryPolicy.parseFavoriteFolderWriteResponse(
                """{"status":"ok","msg":"folder edited"}""",
            ).status,
        )
        assertEquals(
            "1",
            JmOnlineLibraryPolicy.parseDeleteHistoryResponse(
                """{"status":1,"msg":"deleted"}""",
            ).status,
        )

        assertEquals(
            "folder not empty",
            assertThrows(IOException::class.java) {
                JmOnlineLibraryPolicy.parseFavoriteFolderWriteResponse(
                    """{"status":"fail","msg":"folder not empty","code":400}""",
                )
            }.message,
        )
        assertThrows(IOException::class.java) {
            JmOnlineLibraryPolicy.parseFavoriteWriteResponse("""{"msg":"missing status"}""")
        }
        assertThrows(IOException::class.java) {
            JmOnlineLibraryPolicy.parseDeleteHistoryResponse("""{"status":0,"msg":"failed"}""")
        }
    }

    @Test
    fun `HTTP and decrypted API failures are not mistaken for successful writes or empty data`() {
        assertEquals(
            "JM 写操作失败（HTTP 503）",
            JmOnlineLibraryPolicy.writeResponseError(false, 503, null),
        )
        assertEquals(
            "JM 写操作失败：session expired",
            JmOnlineLibraryPolicy.writeResponseError(true, 200, "session expired"),
        )
        assertEquals(
            "JM 列表请求失败：账号需要重新登录",
            JmOnlineLibraryPolicy.listResponseError(true, 200, "账号需要重新登录"),
        )
        assertEquals(
            "JM 列表请求失败（HTTP 503）",
            JmOnlineLibraryPolicy.listResponseError(false, 503, null),
        )
    }

    @Test
    fun `pagination clamps favorites and stops on repeated watch page`() {
        assertEquals(4, JmOnlineLibraryPolicy.favoritePageCount(73))
        assertEquals(0, JmOnlineLibraryPolicy.favoritePageCount(0))
        assertEquals(4, JmOnlineLibraryPolicy.clampFavoritePage(99, 73))
        assertEquals(1, JmOnlineLibraryPolicy.clampFavoritePage(2, 0))
        assertThrows(IllegalArgumentException::class.java) { JmOnlineLibraryPolicy.clampFavoritePage(0, 30) }

        val firstPageIds = (1..20).map(Int::toString)
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 40, existingItemIds = firstPageIds, nextPageIds = firstPageIds))
        val nextPageIds = (21..40).map(Int::toString)
        assertTrue(JmOnlineLibraryPolicy.hasMore(total = 45, existingItemIds = firstPageIds, nextPageIds = nextPageIds))
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 40, existingItemIds = firstPageIds, nextPageIds = nextPageIds))
        assertFalse(JmOnlineLibraryPolicy.hasMore(total = 45, existingItemIds = firstPageIds, nextPageIds = emptyList()))
    }

    @Test
    fun `failed loading remains distinct from a valid empty response`() {
        assertEquals(
            JmOnlineLibraryContentState.ERROR,
            JmOnlineLibraryPolicy.contentState(hasItems = false, loading = false, error = "JM API error"),
        )
        assertEquals(
            JmOnlineLibraryContentState.EMPTY,
            JmOnlineLibraryPolicy.contentState(hasItems = false, loading = false, error = null),
        )
    }

    @Test
    fun `initial request does not wait for a lazy layout and scroll waits for successful laid out content`() {
        assertTrue(JmOnlineLibraryPolicy.shouldRequestPage(loggedIn = true, hasMore = true, generationIsCurrent = true))
        assertFalse(JmOnlineLibraryPolicy.shouldRequestPage(loggedIn = false, hasMore = true, generationIsCurrent = true))
        assertFalse(JmOnlineLibraryPolicy.shouldRequestPage(loggedIn = true, hasMore = false, generationIsCurrent = true))
        assertFalse(JmOnlineLibraryPolicy.shouldRequestPage(loggedIn = true, hasMore = true, generationIsCurrent = false))

        assertFalse(JmOnlineLibraryPolicy.shouldScrollToTop(hasVisibleItems = true, loading = false, hasError = false, listLaidOut = false))
        assertFalse(JmOnlineLibraryPolicy.shouldScrollToTop(hasVisibleItems = true, loading = true, hasError = false, listLaidOut = true))
        assertFalse(JmOnlineLibraryPolicy.shouldScrollToTop(hasVisibleItems = false, loading = false, hasError = false, listLaidOut = true))
        assertFalse(JmOnlineLibraryPolicy.shouldScrollToTop(hasVisibleItems = true, loading = false, hasError = true, listLaidOut = true))
        assertTrue(JmOnlineLibraryPolicy.shouldScrollToTop(hasVisibleItems = true, loading = false, hasError = false, listLaidOut = true))
    }

    private fun item(id: String, name: String) = JmOnlineLibraryItem(
        id = id,
        name = name,
        author = "",
        description = "",
        image = "",
    )
}
