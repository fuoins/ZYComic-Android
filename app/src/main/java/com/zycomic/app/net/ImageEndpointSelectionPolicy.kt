package com.zycomic.app.net

/** Pure UI-selection policy shared by the speed log and reader source switcher. */
object ImageEndpointSelectionPolicy {

    /**
     * Select exactly one current endpoint from candidates already sorted by speed.
     * A configured/manual domain without a pinned IP maps to its fastest available endpoint.
     */
    fun currentEndpoint(
        sortedCandidates: List<DomainIp>,
        currentDomain: String?,
        currentIp: String?,
    ): DomainIp? {
        if (currentDomain.isNullOrBlank()) return null
        return sortedCandidates.firstOrNull { endpoint ->
            endpoint.domain.equals(currentDomain, ignoreCase = true) &&
                (currentIp == null || endpoint.ip == currentIp)
        }
    }
}
