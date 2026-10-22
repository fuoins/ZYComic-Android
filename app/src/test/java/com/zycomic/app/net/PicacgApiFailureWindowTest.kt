package com.zycomic.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicacgApiFailureWindowTest {
    private val endpoint = DomainIp("api.pica.example", "198.51.100.10")

    @Test
    fun `recovery threshold requires three consecutive online read transport failures`() {
        val window = PicacgApiFailureWindow()

        assertFalse(window.recordFailure(endpoint, networkRevision = 7, online = true))
        assertFalse(window.recordFailure(endpoint, networkRevision = 7, online = true))
        assertTrue(window.recordFailure(endpoint, networkRevision = 7, online = true))
        assertEquals(3, window.failureCount(endpoint))

        window.recordResponse(endpoint, networkRevision = 7, online = true)
        assertEquals(0, window.failureCount(endpoint))
        assertFalse(window.recordFailure(endpoint, networkRevision = 7, online = true))
    }

    @Test
    fun `offline failures do not increment or reset counters and network changes start a new streak`() {
        val window = PicacgApiFailureWindow()

        assertFalse(window.recordFailure(endpoint, networkRevision = 1, online = true))
        assertFalse(window.recordFailure(endpoint, networkRevision = 1, online = true))
        assertFalse(window.recordFailure(endpoint, networkRevision = 1, online = false))
        assertEquals(2, window.failureCount(endpoint))
        assertFalse(window.recordFailure(endpoint, networkRevision = 2, online = true))
        assertEquals(1, window.failureCount(endpoint))
        assertFalse(window.recordFailure(endpoint, networkRevision = 2, online = true))
        assertTrue(window.recordFailure(endpoint, networkRevision = 2, online = true))

        window.recordResponse(endpoint, networkRevision = 2, online = false)
        assertEquals(3, window.failureCount(endpoint))
    }
}
