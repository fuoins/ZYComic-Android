package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageSourceDisplayPolicyTest {
    private val configured = listOf("fast.example", "recommended.example", "pinned.example")
    private val fastest = DomainIp("fast.example", "192.0.2.1")
    private val recommended = DomainIp("recommended.example", "192.0.2.2")

    @Test
    fun `enabled switch marks fastest successful endpoint as automatic current source`() {
        val current = ImageSourceDisplayPolicy.currentSource(
            configuredDomains = configured,
            successfulEndpoints = listOf(fastest, recommended),
            pinnedDomain = null,
            pinnedIp = null,
            useFastestImgForAll = true,
            serverRecommendedDomain = "recommended.example",
        )

        assertEquals(ImageSourceDisplayPolicy.Kind.AUTOMATIC_FASTEST, current.kind)
        assertEquals(fastest, current.successfulEndpoint)
        assertTrue(current.matchesEndpoint(fastest))
    }

    @Test
    fun `disabled switch keeps server recommendation even when another source is faster`() {
        val current = ImageSourceDisplayPolicy.currentSource(
            configuredDomains = configured,
            successfulEndpoints = listOf(fastest, recommended),
            pinnedDomain = null,
            pinnedIp = null,
            useFastestImgForAll = false,
            serverRecommendedDomain = "recommended.example",
        )

        assertEquals(ImageSourceDisplayPolicy.Kind.SERVER_RECOMMENDED, current.kind)
        assertEquals("recommended.example", current.domain)
        assertNull(current.successfulEndpoint)
        assertTrue(current.description().contains("IP未固定"))
        assertFalse(current.matchesEndpoint(recommended))
    }

    @Test
    fun `enabled switch falls back to server recommendation when no endpoint succeeded`() {
        val current = ImageSourceDisplayPolicy.currentSource(
            configuredDomains = configured,
            successfulEndpoints = emptyList(),
            pinnedDomain = null,
            pinnedIp = null,
            useFastestImgForAll = true,
            serverRecommendedDomain = "recommended.example",
        )

        assertEquals(ImageSourceDisplayPolicy.Kind.SERVER_RECOMMENDED, current.kind)
        assertEquals("recommended.example", current.domain)
        assertNull(current.successfulEndpoint)
    }

    @Test
    fun `valid manual pin remains current when its IP is absent from successful endpoints`() {
        val pinnedIp = "192.0.2.99"
        val current = ImageSourceDisplayPolicy.currentSource(
            configuredDomains = configured,
            successfulEndpoints = listOf(fastest, recommended),
            pinnedDomain = "pinned.example",
            pinnedIp = pinnedIp,
            useFastestImgForAll = true,
            serverRecommendedDomain = "recommended.example",
        )

        assertEquals(ImageSourceDisplayPolicy.Kind.MANUAL_PIN, current.kind)
        assertEquals("pinned.example", current.domain)
        assertEquals(pinnedIp, current.ip)
        assertNull(current.successfulEndpoint)
        assertTrue(current.notInSuccessfulListDescription().contains("不代表测速成功"))
        assertFalse(current.matchesEndpoint(fastest))
    }

    @Test
    fun `server recommendation outside measured list remains current without claiming success`() {
        val current = ImageSourceDisplayPolicy.currentSource(
            configuredDomains = configured,
            successfulEndpoints = listOf(fastest),
            pinnedDomain = null,
            pinnedIp = null,
            useFastestImgForAll = false,
            serverRecommendedDomain = "chapter-cdn.example",
        )

        assertEquals(ImageSourceDisplayPolicy.Kind.SERVER_RECOMMENDED, current.kind)
        assertEquals("chapter-cdn.example", current.domain)
        assertNull(current.successfulEndpoint)
        assertFalse(current.matchesEndpoint(fastest))
    }

    @Test
    fun `IP mismatch does not select a different successful endpoint for the same domain`() {
        val otherIp = DomainIp("pinned.example", "192.0.2.10")
        val current = ImageSourceDisplayPolicy.currentSource(
            configuredDomains = configured,
            successfulEndpoints = listOf(otherIp),
            pinnedDomain = "pinned.example",
            pinnedIp = "192.0.2.11",
            useFastestImgForAll = false,
            serverRecommendedDomain = "recommended.example",
        )

        assertEquals(ImageSourceDisplayPolicy.Kind.MANUAL_PIN, current.kind)
        assertNull(current.successfulEndpoint)
        assertFalse(current.matchesEndpoint(otherIp))
    }

    @Test
    fun `invalid manual pin is ignored just as runtime rewrite policy ignores it`() {
        val current = ImageSourceDisplayPolicy.currentSource(
            configuredDomains = configured,
            successfulEndpoints = listOf(fastest),
            pinnedDomain = "removed.example",
            pinnedIp = "192.0.2.88",
            useFastestImgForAll = true,
            serverRecommendedDomain = "recommended.example",
        )

        assertEquals(ImageSourceDisplayPolicy.Kind.AUTOMATIC_FASTEST, current.kind)
        assertEquals(fastest, current.successfulEndpoint)
    }

    @Test
    fun `chapter first-domain fallback is shown when recommendation is empty`() {
        val current = ImageSourceDisplayPolicy.currentSource(
            configuredDomains = configured,
            successfulEndpoints = emptyList(),
            pinnedDomain = null,
            pinnedIp = null,
            useFastestImgForAll = false,
            serverRecommendedDomain = " ",
            serverFallbackDomain = "chapter-first.example",
        )

        assertEquals(ImageSourceDisplayPolicy.Kind.SERVER_RECOMMENDED, current.kind)
        assertEquals("chapter-first.example", current.domain)
        assertNull(current.successfulEndpoint)
    }
}
