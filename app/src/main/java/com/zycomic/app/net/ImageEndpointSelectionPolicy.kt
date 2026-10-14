package com.zycomic.app.net

/** Pure image-endpoint selection policy shared by UI selection and runtime fallback. */
object ImageEndpointSelectionPolicy {

    /** Successful endpoint order shared by automatic selection and image retry. */
    fun rankedSuccessfulEndpoints(
        delays: Map<DomainIp, Long>,
        verdicts: Map<DomainIp, SpeedTester.ImgVerdict>,
        configuredDomains: List<String>,
    ): List<DomainIp> {
        val allowedDomains = configuredDomains.mapTo(HashSet()) { it.lowercase() }
        return delays.entries.asSequence()
            .filter { (endpoint, delay) ->
                endpoint.domain.lowercase() in allowedDomains &&
                    delay < Long.MAX_VALUE && verdicts[endpoint] == SpeedTester.ImgVerdict.OK
            }
            .sortedWith(compareBy({ it.value }, { it.key.domain }, { it.key.ip }))
            .map { it.key }
            .distinct()
            .toList()
    }

    /**
     * Keep the current/manual endpoint first, then follow measured rank order. If it is ranked,
     * continue after it and wrap only when needed; an unmeasured manual pin precedes all ranks.
     */
    fun retryOrder(preferred: DomainIp?, rankedCandidates: List<DomainIp>): List<DomainIp> {
        preferred ?: return rankedCandidates.distinct()
        val rank = rankedCandidates.indexOf(preferred)
        val afterPreferred = if (rank >= 0) {
            rankedCandidates.drop(rank + 1) + rankedCandidates.take(rank)
        } else {
            rankedCandidates
        }
        return (listOf(preferred) + afterPreferred).distinct()
    }

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
