package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JmDetailActionsTest {
    @Test
    fun `large counts retain every decimal digit`() {
        assertEquals("2147483647", jmCountForDisplay(Int.MAX_VALUE))
    }

    @Test
    fun `unknown counts keep the placeholder`() {
        assertEquals("—", jmCountForDisplay(null))
    }
}
