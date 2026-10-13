package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 图源选择纯逻辑的回归测试。
 *
 * 覆盖两条已确认的设计决定：
 * - 优先级：手动 pin > 排除冷却后的派生最快 > 章节接口点名 > 列表首个
 * - 冷却语义：标记在**本轮测速内**永久有效；但当候选**全部**被标记时降级忽略冷却，
 *   避免一次网络抖动把整轮候选掏空后彻底无法选域/换域。
 */
class ImgDomainSelectorTest {

    private val candidates = listOf("a.cc", "b.cc", "c.cc")

    @Test
    fun `pinned domain wins even when another one is faster`() {
        val delays = mapOf("a.cc" to 50L, "b.cc" to 90L, "c.cc" to 30L)

        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = delays,
            cooled = emptySet(),
            pinned = "b.cc",
            chapterNamed = emptyList(),
        )

        assertEquals("b.cc", choice.domain)
        assertEquals(ImgDomainSelector.Reason.PINNED, choice.reason)
    }

    @Test
    fun `without pin the fastest uncooled domain wins`() {
        val delays = mapOf("a.cc" to 50L, "b.cc" to 90L, "c.cc" to 30L)

        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = delays,
            cooled = setOf("c.cc"),
            pinned = null,
            chapterNamed = emptyList(),
        )

        assertEquals("a.cc", choice.domain)
        assertEquals(ImgDomainSelector.Reason.FASTEST, choice.reason)
    }

    @Test
    fun `when every eligible domain is cooled the fastest is still returned`() {
        val delays = mapOf("a.cc" to 50L, "b.cc" to 90L, "c.cc" to 30L)

        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = delays,
            cooled = setOf("a.cc", "b.cc", "c.cc"),
            pinned = null,
            chapterNamed = emptyList(),
        )

        assertEquals("c.cc", choice.domain)
        assertEquals(ImgDomainSelector.Reason.FASTEST_COOLDOWN_IGNORED, choice.reason)
    }

    @Test
    fun `domains without delay data are never picked over a measured one`() {
        // c.cc 本轮没测到（缺 key）：不应被当成 0ms 而击败 a.cc
        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = mapOf("a.cc" to 200L),
            cooled = emptySet(),
            pinned = null,
            chapterNamed = emptyList(),
        )

        assertEquals("a.cc", choice.domain)
        assertEquals(ImgDomainSelector.Reason.FASTEST, choice.reason)
    }

    @Test
    fun `unreachable delay value counts as no data`() {
        val delays = mapOf("a.cc" to ImgDomainSelector.UNAVAILABLE, "b.cc" to 60L)

        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = delays,
            cooled = emptySet(),
            pinned = null,
            chapterNamed = emptyList(),
        )

        assertEquals("b.cc", choice.domain)
    }

    @Test
    fun `no speed data falls back to the domain named by the chapter api`() {
        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = emptyMap(),
            cooled = emptySet(),
            pinned = null,
            chapterNamed = listOf("newimg.cc", ""),
        )

        assertEquals("newimg.cc", choice.domain)
        assertEquals(ImgDomainSelector.Reason.CHAPTER_NAMED, choice.reason)
    }

    @Test
    fun `without any data at all the first domain in the list is used`() {
        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = emptyMap(),
            cooled = emptySet(),
            pinned = null,
            chapterNamed = emptyList(),
        )

        assertEquals("a.cc", choice.domain)
        assertEquals(ImgDomainSelector.Reason.FIRST_IN_LIST, choice.reason)
    }

    @Test
    fun `a pin pointing at a domain removed from the list is ignored`() {
        val delays = mapOf("a.cc" to 50L, "b.cc" to 30L)

        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = delays,
            cooled = emptySet(),
            pinned = "gone.cc",
            chapterNamed = emptyList(),
        )

        assertEquals("b.cc", choice.domain)
        assertEquals(ImgDomainSelector.Reason.FASTEST, choice.reason)
    }

    @Test
    fun `pin and cooldown matching ignores case`() {
        val delays = mapOf("a.cc" to 50L, "b.cc" to 30L)

        val pinnedUpper = ImgDomainSelector.select(candidates, delays, emptySet(), "B.CC", emptyList())
        assertEquals("b.cc", pinnedUpper.domain)
        assertEquals(ImgDomainSelector.Reason.PINNED, pinnedUpper.reason)

        val cooledUpper = ImgDomainSelector.select(candidates, delays, setOf("B.CC"), null, emptyList())
        assertEquals("a.cc", cooledUpper.domain)
    }

    @Test
    fun `equal delays keep list order so the result is deterministic`() {
        val delays = mapOf("a.cc" to 40L, "b.cc" to 40L)

        val choice = ImgDomainSelector.select(candidates, delays, emptySet(), null, emptyList())

        assertEquals("a.cc", choice.domain)
    }

    @Test
    fun `empty candidate list and no named domain yields nothing`() {
        val choice = ImgDomainSelector.select(emptyList(), emptyMap(), emptySet(), null, emptyList())

        assertNull(choice.domain)
        assertEquals(ImgDomainSelector.Reason.NONE, choice.reason)
    }

    @Test
    fun `blank named domain is skipped`() {
        val choice = ImgDomainSelector.select(
            candidates = candidates,
            delays = emptyMap(),
            cooled = emptySet(),
            pinned = null,
            chapterNamed = listOf("  ", "x.cc"),
        )

        assertEquals("x.cc", choice.domain)
        assertEquals(ImgDomainSelector.Reason.CHAPTER_NAMED, choice.reason)
    }
}
