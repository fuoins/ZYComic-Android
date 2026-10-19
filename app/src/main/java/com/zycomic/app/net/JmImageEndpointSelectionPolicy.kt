package com.zycomic.app.net

/** sources2-specific adapter for the shared exact-host/IP endpoint policy. */
object JmImageEndpointSelectionPolicy {
    fun currentEndpoint(
        domain: String,
        configuredDomains: List<String>,
        delays: Map<DomainIp, Long>,
        verdicts: Map<DomainIp, JmSpeedTester.ImgVerdict>,
        manualEndpoint: DomainIp?,
        excluded: Set<DomainIp> = emptySet(),
    ): DomainIp? = JmEndpointSelectionPolicy.select(
        domain = domain,
        configuredDomains = configuredDomains,
        delays = delays,
        successful = { verdicts[it] == JmSpeedTester.ImgVerdict.OK },
        manualEndpoint = manualEndpoint,
        excluded = excluded,
    )
}
