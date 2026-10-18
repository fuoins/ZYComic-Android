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
    fun `startup and config callers share the same completed generation`() {
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

    @Test
    fun `applied remote config requires fresh images but not a duplicate completed run`() {
        assertTrue(SpeedTestRunPolicy.canSkip(key, key, false, false))
        assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, key, key, key, remoteConfigUpdated = true))
        assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, key, key, null, remoteConfigUpdated = true))
    }

    @Test
    fun `ordinary caller after a config change also requires fresh images on both platforms`() {
        for (changed in listOf(key.copy(manwaConfigRevision = 4), key.copy(jmConfigRevision = 5))) {
            assertFalse(SpeedTestRunPolicy.canSkip(key, changed, false, false))
            assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, changed, changed, key))
            assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, key, changed, null))
        }
    }

    @Test
    fun `unchanged remote config preserves completed-run and image reuse behavior`() {
        assertTrue(SpeedTestRunPolicy.canSkip(key, key, false, false))
        assertTrue(SpeedTestRunPolicy.canReuseImages(true, false, key, key, key))
    }
}
