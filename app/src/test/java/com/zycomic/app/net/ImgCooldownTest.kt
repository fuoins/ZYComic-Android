package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 冷却标记表的行为：本轮永久、清空才恢复、大小写不敏感。
 * 注意这里**没有** TTL——按设计，30 分钟自然到期被「每轮测速开始时整表清空」替代。
 */
class ImgCooldownTest {

    @Test
    fun `marked domain stays cooling forever within the round`() {
        val cooldown = ImgCooldown()
        cooldown.mark("newmwtuyuan1.cc")

        assertTrue(cooldown.isCooling("newmwtuyuan1.cc"))
        // 再次标记不改变结论：本轮内永久
        cooldown.mark("newmwtuyuan1.cc")
        assertTrue(cooldown.isCooling("newmwtuyuan1.cc"))
    }

    @Test
    fun `clear resets every mark`() {
        val cooldown = ImgCooldown()
        cooldown.mark("a.cc")
        cooldown.mark("b.cc")

        cooldown.clear()

        assertFalse(cooldown.isCooling("a.cc"))
        assertFalse(cooldown.isCooling("b.cc"))
        assertTrue(cooldown.snapshot().isEmpty())
    }

    @Test
    fun `unmarked domain is available`() {
        val cooldown = ImgCooldown()
        cooldown.mark("a.cc")

        assertFalse(cooldown.isCooling("b.cc"))
    }

    @Test
    fun `matching ignores case and snapshot is lowercase`() {
        val cooldown = ImgCooldown()
        cooldown.mark("A.CC")

        assertTrue(cooldown.isCooling("a.cc"))
        assertEquals(setOf("a.cc"), cooldown.snapshot())
    }

    @Test
    fun `blank host is not marked`() {
        val cooldown = ImgCooldown()
        cooldown.mark("  ")

        assertTrue(cooldown.snapshot().isEmpty())
    }
}
