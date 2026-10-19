package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JmEndpointSelectionPolicyTest {
    private val slow = DomainIp("api.example", "203.0.113.1")
    private val fast = DomainIp("api.example", "203.0.113.2")
    private val otherHost = DomainIp("image.example", "203.0.113.2")

    @Test
    fun `selects exactly one fastest successful address for the requested domain`() {
        val selected = JmEndpointSelectionPolicy.select(
            domain = "API.EXAMPLE",
            configuredDomains = listOf("api.example"),
            delays = mapOf(slow to 42L, fast to 9L, otherHost to 1L),
            successful = { true },
        )
        assertEquals(fast, selected)
        assertEquals(1, listOf(slow, fast).count { it == selected })
    }

    @Test
    fun `manual IP is pinned while valid and a fresh failed pin falls over`() {
        val delays = mapOf(slow to 42L, fast to 9L)
        assertEquals(
            slow,
            JmEndpointSelectionPolicy.select("api.example", listOf("api.example"), delays, { true }, slow),
        )
        assertEquals(
            fast,
            JmEndpointSelectionPolicy.select("api.example", listOf("api.example"), delays, { it != slow }, slow),
        )
    }

    @Test
    fun `excludes failed addresses and does not escape configured domain pool`() {
        assertEquals(
            fast,
            JmEndpointSelectionPolicy.select(
                "api.example",
                listOf("api.example"),
                mapOf(slow to 3L, fast to 7L),
                { true },
                excluded = setOf(slow),
            ),
        )
        assertNull(JmEndpointSelectionPolicy.select("image.example", listOf("api.example"), mapOf(otherHost to 1L), { true }))
        assertNull(JmEndpointSelectionPolicy.select("api.example", listOf("api.example"), mapOf(slow to Long.MAX_VALUE), { true }))
    }
}
