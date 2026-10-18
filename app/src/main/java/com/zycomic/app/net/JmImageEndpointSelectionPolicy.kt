package com.zycomic.app.net

/** One sources2 endpoint shared by the reader's current marker and JM DNS routing. */
object JmImageEndpointSelectionPolicy {
    fun currentEndpoint(
        domain: String,
        configuredDomains: List<String>,
        delays: Map<DomainIp, Long>,
        verdicts: Map<DomainIp, JmSpeedTester.ImgVerdict>,
        manualEndpoint: DomainIp?,
    ): DomainIp? {
        if (configuredDomains.none { it.equals(domain, ignoreCase = true) }) return null
        val candidates = delays.keys.filter { it.domain.equals(domain, ignoreCase = true) && it.ip.isNotBlank() }
        // A manual click pins that exact measured row, independently of automatic speed ranking.
        if (manualEndpoint != null && manualEndpoint.domain.equals(domain, ignoreCase = true)) {
            return candidates.firstOrNull { it.ip.equals(manualEndpoint.ip, ignoreCase = true) }
        }
        return candidates.filter { endpoint ->
            val delay = delays.getValue(endpoint)
            delay >= 0L && delay < Long.MAX_VALUE && verdicts[endpoint] == JmSpeedTester.ImgVerdict.OK
        }.minWithOrNull(compareBy({ delays.getValue(it) }, { it.ip }))
    }
}
