package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImageEndpointSelectionPolicyTest {

    @Test
    fun `manual pin remains current while the fastest endpoint is independently available`() {
        val fastest = DomainIp("fast.example", "192.0.2.1")
        val pinned = DomainIp("pinned.example", "192.0.2.2")

        assertEquals(
            pinned,
            ImageEndpointSelectionPolicy.currentEndpoint(
                sortedCandidates = listOf(fastest, pinned),
                currentDomain = "PINNED.EXAMPLE",
                currentIp = pinned.ip,
            ),
        )
    }

    @Test
    fun `domain-only selection marks the fastest candidate for that domain exactly once`() {
        val first = DomainIp("cdn.example", "192.0.2.1")
        val second = DomainIp("cdn.example", "192.0.2.2")

        assertEquals(
            first,
            ImageEndpointSelectionPolicy.currentEndpoint(
                sortedCandidates = listOf(first, second),
                currentDomain = "cdn.example",
                currentIp = null,
            ),
        )
    }

    @Test
    fun `missing current domain has no selected endpoint`() {
        assertNull(ImageEndpointSelectionPolicy.currentEndpoint(emptyList(), null, null))
    }
}
