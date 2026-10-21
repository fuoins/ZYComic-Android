package com.zycomic.app.picacg

import com.zycomic.app.data.dto.Manga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PicacgLibraryCacheTest {
    @BeforeEach
    fun clearCache() {
        PicacgLibraryCache.clear()
    }

    @Test
    fun `snapshot hits are isolated by session sort and local query`() {
        PicacgLibraryCache.activateSession(sessionRevision = 11, favouritesRevision = 3, authenticated = true)
        val recent = key()
        val otherSort = key(sort = "ld")
        val otherQuery = key(query = "dragon")
        val snapshot = entry("comic-1")

        assertTrue(PicacgLibraryCache.put(recent, snapshot))
        assertEquals(snapshot, PicacgLibraryCache.get(recent))
        assertNull(PicacgLibraryCache.get(otherSort))
        assertNull(PicacgLibraryCache.get(otherQuery))
    }

    @Test
    fun `new session and favorites revision clear snapshots and reject old responses`() {
        val oldKey = key()
        PicacgLibraryCache.activateSession(sessionRevision = 11, favouritesRevision = 3, authenticated = true)
        assertTrue(PicacgLibraryCache.put(oldKey, entry("old")))

        PicacgLibraryCache.activateSession(sessionRevision = 12, favouritesRevision = 3, authenticated = true)
        assertNull(PicacgLibraryCache.get(oldKey))
        assertFalse(PicacgLibraryCache.put(oldKey, entry("stale-session")))

        val newSessionKey = key(sessionRevision = 12)
        assertTrue(PicacgLibraryCache.put(newSessionKey, entry("new")))
        PicacgLibraryCache.activateSession(sessionRevision = 12, favouritesRevision = 4, authenticated = true)
        assertNull(PicacgLibraryCache.get(newSessionKey))
        assertFalse(PicacgLibraryCache.put(newSessionKey, entry("stale-favorites")))
    }

    @Test
    fun `logout clears snapshots and cached data remains until successful replacement`() {
        val cacheKey = key()
        val staleSnapshot = entry("visible-while-refreshing")
        PicacgLibraryCache.activateSession(sessionRevision = 11, favouritesRevision = 3, authenticated = true)
        assertTrue(PicacgLibraryCache.put(cacheKey, staleSnapshot))

        // A failed refresh performs no cache write, so the already displayed snapshot remains readable.
        assertEquals(staleSnapshot, PicacgLibraryCache.get(cacheKey))
        val refreshed = entry("fresh")
        assertTrue(PicacgLibraryCache.put(cacheKey, refreshed))
        assertEquals(refreshed, PicacgLibraryCache.get(cacheKey))

        PicacgLibraryCache.activateSession(sessionRevision = 12, favouritesRevision = 4, authenticated = false)
        assertNull(PicacgLibraryCache.get(cacheKey))
    }

    private fun key(
        sessionRevision: Long = 11,
        favouritesRevision: Long = 3,
        sort: String = "dd",
        query: String = "",
    ) = PicacgLibraryCacheKey(sessionRevision, favouritesRevision, sort, query)

    private fun entry(id: String) = PicacgLibraryCacheEntry(listOf(Manga(id = id, name = "Comic $id")), page = 2, hasMore = true)
}
