package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RouteListPolicyTest {

    @Test
    fun `smaller remote lists replace rather than merge local candidates`() {
        val result = RouteListPolicy.replace(
            lines = listOf("https://remote-line.example"),
            sources = listOf("remote-cdn.example"),
        )

        assertEquals(listOf("https://remote-line.example"), result.lines)
        assertEquals(listOf("remote-cdn.example"), result.sources)
    }

    @Test
    fun `duplicate line hosts and source domains are removed preserving first order`() {
        val result = RouteListPolicy.replace(
            lines = listOf("https://line.example", "http://line.example"),
            sources = listOf("cdn.example", "CDN.EXAMPLE", "other.example"),
        )

        assertEquals(listOf("https://line.example"), result.lines)
        assertEquals(listOf("cdn.example", "other.example"), result.sources)
    }

    @Test
    fun `empty lists are rejected instead of clearing the last usable configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            RouteListPolicy.replace(emptyList(), listOf("cdn.example"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteListPolicy.replace(listOf("https://line.example"), emptyList())
        }
    }
}
