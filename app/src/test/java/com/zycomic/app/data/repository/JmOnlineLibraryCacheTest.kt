package com.zycomic.app.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JmOnlineLibraryCacheTest {
    @BeforeEach
    fun clearCache() {
        JmOnlineLibraryCache.clear()
    }

    @Test
    fun `favorites snapshots are isolated by session folder and sort order`() {
        JmOnlineLibraryCache.activateSession(7, "reader@example.com", authenticated = true)
        val allRecent = key()
        val folderRecent = key(folderId = "FID_7")
        val allPopular = key(sortOrder = "mp")
        assertTrue(JmOnlineLibraryCache.put(allRecent, entry("10")))

        assertEquals(listOf("10"), JmOnlineLibraryCache.get(allRecent)?.items?.map { it.id })
        assertNull(JmOnlineLibraryCache.get(folderRecent))
        assertNull(JmOnlineLibraryCache.get(allPopular))
    }

    @Test
    fun `switching account or logging out invalidates old snapshots and rejects stale writes`() {
        val oldKey = key()
        JmOnlineLibraryCache.activateSession(7, "reader@example.com", authenticated = true)
        assertTrue(JmOnlineLibraryCache.put(oldKey, entry("10")))

        JmOnlineLibraryCache.activateSession(7, "other@example.com", authenticated = true)
        assertNull(JmOnlineLibraryCache.get(oldKey))
        assertFalse(JmOnlineLibraryCache.put(oldKey, entry("stale")))
        val sameRevisionNewAccount = key(identity = "other@example.com")
        assertTrue(JmOnlineLibraryCache.put(sameRevisionNewAccount, entry("20")))

        JmOnlineLibraryCache.activateSession(8, "other@example.com", authenticated = true)
        assertNull(JmOnlineLibraryCache.get(sameRevisionNewAccount))
        assertFalse(JmOnlineLibraryCache.put(sameRevisionNewAccount, entry("stale")))
        val newKey = key(revision = 8, identity = "other@example.com")
        assertTrue(JmOnlineLibraryCache.put(newKey, entry("20")))

        JmOnlineLibraryCache.activateSession(9, "", authenticated = false)
        assertNull(JmOnlineLibraryCache.get(newKey))
        assertFalse(JmOnlineLibraryCache.put(newKey, entry("stale")))
    }

    @Test
    fun `history snapshots are process cached separately with raw paging cursor`() {
        JmOnlineLibraryCache.activateSession(7, "reader@example.com", authenticated = true)
        val historyKey = key(listType = JmOnlineLibraryListType.HISTORY)
        val historyEntry = JmOnlineLibraryCacheEntry(
            listOf(item("10")),
            total = 42,
            folders = emptyList(),
            page = 3,
            hasMore = true,
            historyLoadedIds = setOf("10", "11", "12"),
        )

        assertTrue(JmOnlineLibraryCache.put(historyKey, historyEntry))
        assertEquals(historyEntry, JmOnlineLibraryCache.get(historyKey))
        assertNull(JmOnlineLibraryCache.get(key()))
    }

    @Test
    fun `cached snapshot remains visible during background refresh and after refresh failure`() {
        JmOnlineLibraryCache.activateSession(7, "reader@example.com", authenticated = true)
        val historyKey = key(listType = JmOnlineLibraryListType.HISTORY)
        val stale = JmOnlineLibraryCacheEntry(
            listOf(item("10")),
            total = 20,
            folders = emptyList(),
            page = 2,
            hasMore = true,
            historyLoadedIds = setOf("10", "11", "12"),
        )
        assertTrue(JmOnlineLibraryCache.put(historyKey, stale))

        var state = JmOnlineLibraryLoadState(
            snapshot = JmOnlineLibraryCache.get(historyKey),
            loading = true,
        )
        assertEquals(stale, state.snapshot)
        assertTrue(state.loading)

        state = state.beginLoad()
        assertEquals(stale, state.snapshot)
        assertTrue(state.loading)
        assertNull(state.error)

        state = state.fail("offline")
        assertEquals(stale, state.snapshot)
        assertFalse(state.loading)
        assertEquals("offline", state.error)

        val refreshed = stale.copy(items = listOf(item("20")), page = 1, historyLoadedIds = setOf("20"))
        state = state.beginLoad().complete(refreshed)
        assertEquals(refreshed, state.snapshot)
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test
    fun `page append deduplicates repeated IDs while refresh replaces the snapshot`() {
        val old = listOf(item("10"), item("20"))
        val appended = JmOnlineLibraryCachePolicy.appendPage(old, listOf(item("20"), item("30")))

        assertEquals(listOf("10", "20", "30"), appended.map { it.id })
        val refreshed = JmOnlineLibraryCachePolicy.replaceFirstPage(
            JmOnlineLibraryPage(listOf(item("40"), item("40")), 1),
        )
        assertEquals(listOf("40"), refreshed.map { it.id })
    }

    private fun key(
        revision: Long = 7,
        identity: String = "reader@example.com",
        listType: JmOnlineLibraryListType = JmOnlineLibraryListType.FAVORITES,
        folderId: String = "",
        sortOrder: String = "mr",
    ) = JmOnlineLibraryCacheKey(revision, identity, listType, folderId, sortOrder)

    private fun entry(id: String) = JmOnlineLibraryCacheEntry(listOf(item(id)), 1, emptyList(), 1, false)

    private fun item(id: String) = JmOnlineLibraryItem(id, "Manga $id", "Author", "", "")
}
