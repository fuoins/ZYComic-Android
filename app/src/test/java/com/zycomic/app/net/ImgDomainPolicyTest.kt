package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 图源改写目标的优先级（手动切图源修复的核心逻辑）。
 *
 * 修的是既有 bug：`useFastestImgForAll` 为 true 时，拦截器与所有拼 URL 的地方只读派生的
 * `fastestImgDomain`，而 `setImgHost` 改的是 `imgIndex`——它根本没参与改写。于是阅读器与
 * 设置页里的手动切换图源对实际请求完全无效（空操作）。
 *
 * 这里只定优先级，不涉及冷却/探测那些已按决定移除的部分。
 */
class ImgDomainPolicyTest {

    private val imgDomains = listOf("a.cc", "b.cc", "c.cc")

    @Test
    fun `manual pin wins over the derived fastest domain`() {
        val target = ImgDomainPolicy.rewriteTarget(
            pinned = "b.cc",
            imgDomains = imgDomains,
            useFastestImgForAll = true,
            derivedFastest = "c.cc",
        )

        assertEquals("b.cc", target)
    }

    @Test
    fun `manual pin wins even when the fastest switch is off`() {
        // 开关关闭时用户照样能手动指定图源，这是"手动切换"该有的语义
        val target = ImgDomainPolicy.rewriteTarget(
            pinned = "a.cc",
            imgDomains = imgDomains,
            useFastestImgForAll = false,
            derivedFastest = "c.cc",
        )

        assertEquals("a.cc", target)
    }

    @Test
    fun `pin matching ignores case but returns the domain as listed`() {
        val target = ImgDomainPolicy.rewriteTarget(
            pinned = "B.CC",
            imgDomains = imgDomains,
            useFastestImgForAll = true,
            derivedFastest = "c.cc",
        )

        assertEquals("b.cc", target)
    }

    @Test
    fun `a pin pointing at a domain removed from the list is ignored`() {
        // 远程 ruledns 可能把用户曾固定的域删掉，这时必须退回自动逻辑，
        // 不能继续把图片改写到一个已不存在的图源上
        val target = ImgDomainPolicy.rewriteTarget(
            pinned = "gone.cc",
            imgDomains = imgDomains,
            useFastestImgForAll = true,
            derivedFastest = "c.cc",
        )

        assertEquals("c.cc", target)
    }

    @Test
    fun `without pin and with the switch on the derived fastest domain is used`() {
        val target = ImgDomainPolicy.rewriteTarget(
            pinned = null,
            imgDomains = imgDomains,
            useFastestImgForAll = true,
            derivedFastest = "c.cc",
        )

        assertEquals("c.cc", target)
    }

    @Test
    fun `nothing is rewritten without a pin and with the switch off`() {
        // 保持既有行为：不接管，用服务端给的原始域名
        val target = ImgDomainPolicy.rewriteTarget(
            pinned = null,
            imgDomains = imgDomains,
            useFastestImgForAll = false,
            derivedFastest = "c.cc",
        )

        assertNull(target)
    }

    @Test
    fun `blank pin counts as no pin`() {
        val target = ImgDomainPolicy.rewriteTarget(
            pinned = "   ",
            imgDomains = imgDomains,
            useFastestImgForAll = true,
            derivedFastest = null,
        )

        assertNull(target)
    }

    @Test
    fun `no derived result means no rewrite`() {
        val target = ImgDomainPolicy.rewriteTarget(
            pinned = null,
            imgDomains = imgDomains,
            useFastestImgForAll = true,
            derivedFastest = null,
        )

        assertNull(target)
    }
}
