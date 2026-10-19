package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmToggleActionPolicyTest {
    @Test
    fun `toggle only starts when idle and authenticated`() {
        assertTrue(JmToggleActionPolicy.canStart(busy = false, authenticated = true))
        assertFalse(JmToggleActionPolicy.canStart(busy = true, authenticated = true))
        assertFalse(JmToggleActionPolicy.canStart(busy = false, authenticated = false))
    }
}
