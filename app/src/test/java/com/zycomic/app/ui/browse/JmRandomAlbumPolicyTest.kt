package com.zycomic.app.ui.browse

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

class JmRandomAlbumPolicyTest {
    @Test
    fun `random uses valid album IDs and avoids last result when possible`() {
        assertEquals("26", JmRandomAlbumPolicy.SERIES_UPDATE_SECTION_ID)
        assertEquals("12", JmRandomAlbumPolicy.choose(listOf("abc", "0", "0012", "12", "13"), "13", Random(1)))
        assertEquals("12", JmRandomAlbumPolicy.choose(listOf("12"), "12"))
        assertNull(JmRandomAlbumPolicy.choose(listOf("", "jm:12", "-1")))
    }

    @Test
    fun `concurrent loads share a request and successful empty pool is cached`() = runBlocking {
        var calls = 0
        val cache = JmPromoteCache<List<String>>()
        val first = async {
            cache.get {
                calls++
                delay(30)
                emptyList()
            }
        }
        val second = async {
            cache.get {
                calls++
                listOf("unexpected")
            }
        }
        assertEquals(emptyList<String>(), first.await())
        assertEquals(emptyList<String>(), second.await())
        assertEquals(1, calls)
    }

    @Test
    fun `expiration and explicit refresh replace snapshot`() = runBlocking {
        var now = 0L
        var calls = 0
        val cache = JmPromoteCache<Int>(100) { now }
        assertEquals(1, cache.get { ++calls })
        now = 99
        assertEquals(1, cache.get { ++calls })
        now = 100
        assertEquals(2, cache.get { ++calls })
        assertEquals(3, cache.get(forceRefresh = true) { ++calls })
    }

    @Test
    fun `failed load is not turned into a fake successful empty pool`() {
        val cache = JmPromoteCache<List<String>>()
        assertThrows(IllegalStateException::class.java) { runBlocking { cache.get { error("offline fixture") } } }
        runBlocking { assertEquals(listOf("12"), cache.get { listOf("12") }) }
    }

    @Test
    fun `repository selects documented section and retains existing JM route`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app/src/main/java/com/zycomic/app/ui/browse")) }
        val source = Files.readString(root.resolve("app/src/main/java/com/zycomic/app/ui/browse/JmRandomAlbumRepository.kt"))
        assertTrue(source.contains("it.id == JmRandomAlbumPolicy.SERIES_UPDATE_SECTION_ID"))
        assertTrue(source.contains("JmAlbumJumpPolicy.route(id)"))
        assertTrue(source.contains("JmSource.getPromoteSections()"))
        assertTrue(!source.contains("random_recommend"))
    }
}
