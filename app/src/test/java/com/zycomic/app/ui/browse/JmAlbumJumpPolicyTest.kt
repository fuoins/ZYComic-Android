package com.zycomic.app.ui.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JmAlbumJumpPolicyTest {
    @Test
    fun `positive decimal IDs produce JM detail routes`() {
        assertEquals("jm:12345", JmAlbumJumpPolicy.route("12345"))
        assertEquals("jm:12", JmAlbumJumpPolicy.route(" 0012 "))
    }

    @Test
    fun `direct entry preserves every digit beyond machine integer range`() {
        val albumId = "123456789012345678901234567890"
        assertEquals("jm:$albumId", JmAlbumJumpPolicy.route(" 00$albumId "))
    }

    @Test
    fun `blank zero non-decimal and signed input are rejected`() {
        listOf("", "   ", "0", "000", "abc", "12a", "1.5", "-2", "+2", "１２").forEach { input ->
            assertNull(JmAlbumJumpPolicy.route(input), "Expected '$input' to be rejected")
        }
    }
}
