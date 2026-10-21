package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedTestLogSelectionPolicyTest {
    @Test
    fun `fresh full-round selection is marked without implying quick-candidate membership`() {
        val markers = SpeedTestLogSelectionPolicy.markers(
            testedThisRound = true,
            blocked = false,
            roundSelected = true,
            quickCandidate = false,
        )

        assertTrue(markers.roundSelected)
        assertFalse(markers.quickCandidate)
        assertFalse(markers.retestFailed)
    }

    @Test
    fun `fresh quick candidate may be marked even when its probe failed and it was not chosen`() {
        val markers = SpeedTestLogSelectionPolicy.markers(
            testedThisRound = true,
            blocked = false,
            roundSelected = false,
            quickCandidate = true,
            failed = true,
        )

        assertFalse(markers.roundSelected)
        assertTrue(markers.quickCandidate)
        assertTrue(markers.retestFailed)
    }

    @Test
    fun `failed endpoint cannot claim round selection even with stale selected metadata`() {
        val markers = SpeedTestLogSelectionPolicy.markers(true, false, true, true, failed = true)

        assertFalse(markers.roundSelected)
        assertTrue(markers.quickCandidate)
        assertTrue(markers.retestFailed)
    }

    @Test
    fun `successful quick object is not selected unless explicitly chosen`() {
        val markers = SpeedTestLogSelectionPolicy.markers(true, false, false, true)

        assertFalse(markers.roundSelected)
        assertTrue(markers.quickCandidate)
        assertFalse(markers.retestFailed)
    }

    @Test
    fun `historical reused endpoint never receives current-round selection markers`() {
        val markers = SpeedTestLogSelectionPolicy.markers(
            testedThisRound = false,
            blocked = false,
            roundSelected = true,
            quickCandidate = true,
            failed = true,
        )

        assertFalse(markers.roundSelected)
        assertFalse(markers.quickCandidate)
        assertFalse(markers.retestFailed)
    }

    @Test
    fun `blocked endpoint never receives selection markers`() {
        val markers = SpeedTestLogSelectionPolicy.markers(
            testedThisRound = true,
            blocked = true,
            roundSelected = true,
            quickCandidate = true,
            failed = true,
        )

        assertFalse(markers.roundSelected)
        assertFalse(markers.quickCandidate)
        assertFalse(markers.retestFailed)
    }

    @Test
    fun `selected endpoint must be an actual fresh successful measurement`() {
        val freshSuccessful = "api.example/192.0.2.10"
        val freshFailed = "api.example/192.0.2.11"
        val historicalFastest = "api.example/192.0.2.12"
        val testedThisRound = setOf(freshSuccessful, freshFailed)
        val successful: (String) -> Boolean = { it == freshSuccessful }

        assertEquals(
            freshSuccessful,
            SpeedTestLogSelectionPolicy.selectedEndpoint(freshSuccessful, testedThisRound, successful),
        )
        assertNull(SpeedTestLogSelectionPolicy.selectedEndpoint(freshFailed, testedThisRound, successful))
        // A historically fastest endpoint is not selected merely because it wins a ranking.
        assertNull(SpeedTestLogSelectionPolicy.selectedEndpoint(historicalFastest, testedThisRound, successful))
    }

    @Test
    fun `empty fresh set or absent explicit selection yields no selected endpoint`() {
        val endpoint = "api.example/192.0.2.20"
        val successful: (String) -> Boolean = { true }

        assertNull(SpeedTestLogSelectionPolicy.selectedEndpoint(endpoint, emptySet(), successful))
        assertNull(SpeedTestLogSelectionPolicy.selectedEndpoint(null, setOf(endpoint), successful))
    }

    @Test
    fun `stored round selection stays fixed when the live route later changes`() {
        val originallySelected = "api.example/192.0.2.30"
        val newlySelectedByLiveRoute = "api.example/192.0.2.31"
        val testedThisRound = setOf(originallySelected, newlySelectedByLiveRoute)
        val storedSelectedEndpoint = SpeedTestLogSelectionPolicy.selectedEndpoint(
            originallySelected,
            testedThisRound,
        ) { true }

        // Subsequent runtime route changes must not rewrite the selection already stored for this log.
        val currentLiveRoute = newlySelectedByLiveRoute
        assertEquals(originallySelected, storedSelectedEndpoint)
        assertFalse(storedSelectedEndpoint == currentLiveRoute)
    }
}
