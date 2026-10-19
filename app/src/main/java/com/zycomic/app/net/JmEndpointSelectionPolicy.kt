package com.zycomic.app.net

import java.util.Locale

/** Pure policy for selecting exactly one measured IP for one JM hostname. */
object JmEndpointSelectionPolicy {
    fun select(
        domain: String,
        configuredDomains: List<String>,
        delays: Map<DomainIp, Long>,
        successful: (DomainIp) -> Boolean,
        manualEndpoint: DomainIp? = null,
        excluded: Set<DomainIp> = emptySet(),
    ): DomainIp? {
        if (configuredDomains.none { it.equals(domain, ignoreCase = true) }) return null
        val candidates = delays.keys.filter { endpoint ->
            endpoint.domain.equals(domain, ignoreCase = true) && endpoint.ip.isNotBlank() &&
                excluded.none { it.normalizedForComparison() == endpoint.normalizedForComparison() } && delays[endpoint]?.let { it >= 0L && it < Long.MAX_VALUE } == true &&
                successful(endpoint)
        }
        if (manualEndpoint != null && manualEndpoint.domain.equals(domain, ignoreCase = true)) {
            candidates.firstOrNull { it.ip.equals(manualEndpoint.ip, ignoreCase = true) }?.let { return it }
        }
        return candidates.minWithOrNull(
            compareBy<DomainIp>({ delays.getValue(it) }, { it.ip.lowercase(Locale.ROOT) }),
        )
    }
}
