package com.zycomic.app.net

/** Log-only provenance: never infer this round's selection from a historical ranking. */
object SpeedTestLogSelectionPolicy {
    data class Markers(val roundSelected: Boolean, val quickCandidate: Boolean, val retestFailed: Boolean)

    fun markers(
        testedThisRound: Boolean,
        blocked: Boolean,
        roundSelected: Boolean,
        quickCandidate: Boolean,
        failed: Boolean = false,
    ): Markers {
        val fresh = testedThisRound && !blocked
        return Markers(
            roundSelected = fresh && !failed && roundSelected,
            quickCandidate = fresh && quickCandidate,
            retestFailed = fresh && quickCandidate && failed,
        )
    }

    fun <T> selectedEndpoint(candidate: T?, testedEndpoints: Set<T>, successful: (T) -> Boolean): T? =
        candidate?.takeIf { it in testedEndpoints && successful(it) }
}
