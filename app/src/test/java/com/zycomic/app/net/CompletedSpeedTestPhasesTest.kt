package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletedSpeedTestPhasesTest {
    private val key = CompletedSpeedTestPhases.Key(20, 2, 1, "wifi-1", "PICACG")
    private val lines = CompletedSpeedTestPhases.Phase.LINES
    private val images = CompletedSpeedTestPhases.Phase.IMAGES

    @Test
    fun `completed API does not invent image completion or invalidate other platforms`() {
        val phases = CompletedSpeedTestPhases()
        val manwa = key.copy(platform = "MANWA")
        val jm = key.copy(platform = "JM")
        for (platform in listOf(manwa, jm)) {
            phases.record(platform, lines)
            phases.record(platform, images)
        }
        phases.record(key, lines)
        assertTrue(phases.canReuse(key, lines, false, false))
        assertFalse(phases.canReuse(key, images, false, false))
        for (platform in listOf(manwa, jm)) {
            assertTrue(phases.canReuse(platform, lines, false, false))
            assertTrue(phases.canReuse(platform, images, false, false))
        }
    }

    @Test
    fun `configuration version route network and policy changes invalidate completion`() {
        val phases = CompletedSpeedTestPhases()
        phases.record(key, lines)
        for (changed in listOf(
            key.copy(remoteVersion = 21),
            key.copy(routingRevision = 3),
            key.copy(platformRevision = 2),
            key.copy(networkIdentity = "wifi-2"),
            key.copy(platform = "MANWA"),
        )) {
            assertFalse(phases.contains(changed, lines))
        }
    }

    @Test
    fun `explicit retests override completion and failure cannot resurrect old phases`() {
        val phases = CompletedSpeedTestPhases()
        phases.record(key, lines)
        phases.record(key, images)
        assertFalse(phases.canReuse(key, lines, true, false))
        assertFalse(phases.canReuse(key, images, false, true))
        phases.invalidate(key.platform)
        assertFalse(phases.contains(key, lines))
        assertFalse(phases.contains(key, images))
    }

    @Test
    fun `a new process does not skip its normal startup measurement`() {
        assertFalse(CompletedSpeedTestPhases().contains(key, lines))
        assertFalse(CompletedSpeedTestPhases().contains(key, images))
    }
}
