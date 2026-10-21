package com.zycomic.app.picacg

import com.zycomic.app.data.dto.Manga
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PicacgDetailCacheTest {
    private val state = PicacgComicState(false, true, 3, 2, true, "author", "team", "creator", emptyList(), emptyList())

    @BeforeEach
    fun prepare() {
        PicacgDetailCache.clear()
        PicacgDetailCache.activateSession(1L, true)
    }

    @AfterEach
    fun cleanup() {
        PicacgDetailCache.clear()
    }

    @Test
    fun `snapshot retains detail but invalidates interaction state`() {
        val manga = Manga(id = "comic")
        assertTrue(PicacgDetailCache.put(1L, "comic", manga, state))
        PicacgDetailCache.invalidateState(1L, "comic")
        assertEquals(manga, PicacgDetailCache.get(1L, "comic")?.manga)
        assertNull(PicacgDetailCache.get(1L, "comic")?.state)
    }

    @Test
    fun `new session removes snapshots and rejects old in flight responses`() {
        PicacgDetailCache.put(1L, "comic", Manga(id = "comic"), state)
        PicacgDetailCache.activateSession(2L, true)
        assertNull(PicacgDetailCache.get(1L, "comic"))
        assertNull(PicacgDetailCache.get(2L, "comic"))
        assertFalse(PicacgDetailCache.put(1L, "comic", Manga(id = "comic"), state))
    }

    @Test
    fun `logout and explicit clear remove all snapshots`() {
        PicacgDetailCache.put(1L, "comic", Manga(id = "comic"), state)
        PicacgDetailCache.activateSession(1L, false)
        assertNull(PicacgDetailCache.get(1L, "comic"))
        assertFalse(PicacgDetailCache.put(1L, "comic", Manga(id = "comic"), state))
        PicacgDetailCache.activateSession(2L, true)
        PicacgDetailCache.put(2L, "comic", Manga(id = "comic"), state)
        PicacgDetailCache.clear()
        assertNull(PicacgDetailCache.get(2L, "comic"))
    }

    @Test
    fun `twenty entry limit evicts least recently used detail`() {
        repeat(20) { PicacgDetailCache.put(1L, "$it", Manga(id = "$it"), state) }
        assertEquals("0", PicacgDetailCache.get(1L, "0")?.manga?.id)
        PicacgDetailCache.put(1L, "20", Manga(id = "20"), state)
        assertNull(PicacgDetailCache.get(1L, "1"))
        assertEquals("0", PicacgDetailCache.get(1L, "0")?.manga?.id)
        assertEquals("20", PicacgDetailCache.get(1L, "20")?.manga?.id)
    }

    @Test
    fun `old session cannot invalidate current interaction state`() {
        PicacgDetailCache.activateSession(2L, true)
        PicacgDetailCache.put(2L, "comic", Manga(id = "comic"), state)
        PicacgDetailCache.invalidateState(1L, "comic")
        assertEquals(state, PicacgDetailCache.get(2L, "comic")?.state)
    }
}
