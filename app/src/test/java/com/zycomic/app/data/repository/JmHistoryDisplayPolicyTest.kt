package com.zycomic.app.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class JmHistoryDisplayPolicyTest {
    @Test
    fun `only clear chapter suffixes are hidden`() {
        listOf("神秘的桌游-神秘的桌游 第3话", "标题 第\n2話 ", "作品 第十二章", "标题 第３话").forEach {
            assertTrue(JmHistoryDisplayPolicy.isStandaloneChapter(it), it)
        }
        listOf("神秘的桌游（官方版）", "第3话的秘密", "作品 全3话", "第3话（完整版）", "作品 第一次", "普通漫画").forEach {
            assertFalse(JmHistoryDisplayPolicy.isStandaloneChapter(it), it)
        }
    }

    @Test
    fun `hidden first page is skipped and visible rows filled without changing source`() = runBlocking {
        val hidden = (1..20).map { item(it, "作品 第${it}话") }
        val parents = (21..40).map { item(it, "漫画$it") }
        val source = listOf(JmOnlineLibraryPage(hidden, 45), JmOnlineLibraryPage(parents, 45))
        val requests = mutableListOf<Int>()
        val result = JmHistoryDisplayPolicy.loadVisiblePage(0, emptySet()) { page ->
            requests += page
            source[page - 1]
        }
        assertEquals(listOf(1, 2), requests)
        assertEquals(parents, result.items)
        assertEquals(40, result.loadedIds.size)
        assertEquals(2, result.lastPage)
        assertTrue(result.hasMore)
        assertEquals(hidden, source.first().items)
        assertEquals(45, result.total)
    }

    @Test
    fun `partial visible pages continue and last short page stops using raw total`() = runBlocking {
        val first = (1..20).map { item(it, if (it <= 15) "作品 第${it}话" else "漫画$it") }
        val second = (21..25).map { item(it, "漫画$it") }
        val result = JmHistoryDisplayPolicy.loadVisiblePage(0, emptySet()) { page ->
            JmOnlineLibraryPage(if (page == 1) first else second, 25)
        }
        assertEquals((16..25).map(Int::toString), result.items.map { it.id })
        assertEquals(25, result.loadedIds.size)
        assertFalse(result.hasMore)
        assertEquals(2, result.lastPage)
    }

    @Test
    fun `repeated hidden or visible pages cannot create a paging loop`() = runBlocking {
        val hidden = (1..20).map { item(it, "作品 第${it}话") }
        val requests = mutableListOf<Int>()
        val result = JmHistoryDisplayPolicy.loadVisiblePage(0, emptySet()) { page ->
            requests += page
            JmOnlineLibraryPage(hidden, 200)
        }
        assertEquals(listOf(1, 2), requests)
        assertTrue(result.items.isEmpty())
        assertFalse(result.hasMore)
        val repeated = JmHistoryDisplayPolicy.loadVisiblePage(2, result.loadedIds) {
            JmOnlineLibraryPage(hidden.map { it.copy(name = "漫画") }, 200)
        }
        assertTrue(repeated.items.isEmpty())
        assertFalse(repeated.hasMore)
    }

    @Test
    fun `empty list stops and errors remain errors not empty history`() = runBlocking {
        val result = JmHistoryDisplayPolicy.loadVisiblePage(0, emptySet()) { JmOnlineLibraryPage(emptyList(), 20) }
        assertFalse(result.hasMore)
        assertTrue(result.items.isEmpty())
        assertThrows(IOException::class.java) {
            runBlocking { JmHistoryDisplayPolicy.loadVisiblePage(0, emptySet()) { throw IOException("offline fixture") } }
        }
    }

    private fun item(id: Int, name: String) = JmOnlineLibraryItem(id.toString(), name, "", "", "")
}
