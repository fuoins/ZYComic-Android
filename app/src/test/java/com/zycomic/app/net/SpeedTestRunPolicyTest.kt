package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedTestRunPolicyTest {
    private val key = SpeedTestRunKey(1, 2, 3, "wifi-1|direct")

    @Test
    fun `new process measures candidates rather than directly using old latency`() {
        assertFalse(SpeedTestRunPolicy.canSkip(null, key, false, false))
        assertTrue(SpeedTestRunPolicy.canFastTest(true, false))
    }

    @Test
    fun `startup and config callers share the same completed generation`() {
        assertTrue(SpeedTestRunPolicy.canSkip(key, key, false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key, true, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key, false, true))
    }

    @Test
    fun `network change starts a run without disqualifying version-matched history`() {
        val changed = key.copy(networkIdentity = "wifi-2|direct")
        assertFalse(SpeedTestRunPolicy.canSkip(key, changed, false, false))
        assertTrue(SpeedTestRunPolicy.canReuseImages(true, false, changed, changed, key))
        assertTrue(SpeedTestRunPolicy.canReuseImages(true, false, key, changed, null))
    }

    @Test
    fun `platform and config revisions still invalidate in-process completed generation`() {
        assertFalse(SpeedTestRunPolicy.canSkip(key, key.copy(platformRevision = 2), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key.copy(manwaConfigRevision = 4), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key.copy(jmConfigRevision = 5), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key.copy(picacgConfigRevision = 6), false, false))
        assertFalse(SpeedTestRunPolicy.canSkip(key, key.copy(remoteVersion = 21), false, false))
    }

    @Test
    fun `line only image only both and neither are independent`() {
        for (line in listOf(false, true)) {
            for (image in listOf(false, true)) {
                val lineFast = SpeedTestRunPolicy.canFastTest(line, false)
                val imageFast = SpeedTestRunPolicy.canFastTest(image, false)
                if (line) assertTrue(lineFast) else assertFalse(lineFast)
                if (image) assertTrue(imageFast) else assertFalse(imageFast)
            }
        }
    }

    @Test
    fun `manual full retest overrides either remembered-result preference`() {
        assertFalse(SpeedTestRunPolicy.canFastTest(true, true))
        assertFalse(SpeedTestRunPolicy.canFastTest(false, false))
        assertFalse(SpeedTestRunPolicy.canReuseImages(true, true, key, key, key))
    }

    @Test
    fun `actual remote config update forces full test without duplicate completed run`() {
        assertTrue(SpeedTestRunPolicy.canSkip(key, key, false, false))
        assertFalse(SpeedTestRunPolicy.canFastTest(true, false, remoteConfigUpdated = true))
        assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, key, key, null, remoteConfigUpdated = true))
    }

    @Test
    fun `new config disables old top five for every line and image toggle combination`() {
        for (line in listOf(false, true)) {
            for (image in listOf(false, true)) {
                assertFalse(SpeedTestRunPolicy.canFastTest(line, false, remoteConfigUpdated = true))
                assertFalse(SpeedTestRunPolicy.canFastTest(image, false, remoteConfigUpdated = true))
                assertFalse(SpeedTestRunPolicy.canReuseImages(image, false, key, key, key, remoteConfigUpdated = true))
            }
        }
    }
}
