package com.zycomic.app.ui.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrowseTabCenteringPolicyTest {
    @Test
    fun `middle tab moves toward viewport center`() {
        assertEquals(80f, BrowseTabCenteringPolicy.scrollDelta(200, 60, 0, 300, true, true))
        assertEquals(-80f, BrowseTabCenteringPolicy.scrollDelta(40, 60, 0, 300, true, true))
    }

    @Test
    fun `first and last tab stop at content boundary`() {
        assertEquals(0f, BrowseTabCenteringPolicy.scrollDelta(0, 60, 0, 300, false, true))
        assertEquals(0f, BrowseTabCenteringPolicy.scrollDelta(240, 60, 0, 300, true, false))
    }

    @Test
    fun `already centered and short lists do not move`() {
        assertEquals(0f, BrowseTabCenteringPolicy.scrollDelta(120, 60, 0, 300, true, true))
        assertEquals(0f, BrowseTabCenteringPolicy.scrollDelta(40, 60, 0, 300, false, false))
    }
}
