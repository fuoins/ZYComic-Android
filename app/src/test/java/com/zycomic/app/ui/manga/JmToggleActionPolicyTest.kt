package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmToggleActionPolicyTest {
    @Test
    fun `toggle only starts when idle and authenticated`() {
        assertTrue(JmToggleActionPolicy.canStart(busy = false, authenticated = true))
        assertFalse(JmToggleActionPolicy.canStart(busy = true, authenticated = true))
        assertFalse(JmToggleActionPolicy.canStart(busy = false, authenticated = false))
    }

    @Test
    fun `confirmed success toggles known state without inventing unknown state`() {
        assertEquals(true, JmToggleActionPolicy.likedAfterConfirmedSuccess(false))
        assertEquals(false, JmToggleActionPolicy.likedAfterConfirmedSuccess(true))
        assertNull(JmToggleActionPolicy.likedAfterConfirmedSuccess(null))
    }

    @Test
    fun `explicit detail state wins over confirmed fallback`() {
        listOf(false, true).forEach { refreshed ->
            listOf(false, true, null).forEach { confirmed ->
                assertEquals(refreshed, JmToggleActionPolicy.likedAfterRefresh(refreshed, confirmed))
            }
        }
    }

    @Test
    fun `missing detail state preserves confirmed success only`() {
        assertEquals(true, JmToggleActionPolicy.likedAfterRefresh(null, true))
        assertEquals(false, JmToggleActionPolicy.likedAfterRefresh(null, false))
        assertNull(JmToggleActionPolicy.likedAfterRefresh(null, null))
    }
}
