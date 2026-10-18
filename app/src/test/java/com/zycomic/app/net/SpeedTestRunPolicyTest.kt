package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedTestRunPolicyTest {
    private val key = SpeedTestRunKey(1, 2, 3, "wifi-1|direct")

    @Test
    fun `a new process always probes lines even when image reuse is enabled`() {
        assertFalse(SpeedTestRunPolicy.canSkip(null, key, false, false))
        assertTrue(SpeedTestRunPolicy.canReuseImages(true, false, key, key, null))
    }

    @Test
    fun `startup and initial connectivity callback share the same completed round`() {
        assertTrue(SpeedTestRunPolicy.canSkip(key, key, false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key, true, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key, false, true))
    }

    @Test
    fun `real network changes cannot be hidden by recent success or image reuse`() {
        val changed = key.copy(networkIdentity = "wifi-2|direct")
        assertFalse(SpeedTestRunPolicy.canSkip(key, changed, false, false))
        assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, changed, changed, key))
        assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, key, changed, null))
    }

    @Test
    fun `platform and config changes start a new round`() {
        assertFalse(SpeedTestRunPolicy.canSkip(key, key.copy(platformRevision = 2), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key.copy(manwaConfigRevision = 4), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key.copy(jmConfigRevision = 5), false, false))
    }

    @Test
    fun `manual retest and disabled reuse never reuse images`() {
        assertFalse(SpeedTestRunPolicy.canReuseImages(true, true, key, key, key))
        assertFalse(SpeedTestRunPolicy.canReuseImages(false, false, key, key, key))
    }
}
