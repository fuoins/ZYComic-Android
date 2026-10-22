package com.zycomic.app.net

import java.net.URI
import java.util.Locale

data class PicacgApiProbeEndpoint(
    val endpoint: DomainIp,
    val success: Boolean,
    val elapsedMs: Long,
)

data class PicacgApiRouteCandidate(
    val url: String,
    val endpoint: DomainIp,
)

/** Pure eligibility policy: runtime recovery can use only current-snapshot successful configured probes. */
internal object PicacgApiCandidatePolicy {
    fun candidates(
        apiUrls: List<String>,
        currentIndex: Int,
        expectedHost: String,
        committedEndpoint: DomainIp?,
        probes: List<PicacgApiProbeEndpoint>,
        snapshotRevision: Long,
        configRevision: Long,
        apiRouteRules: Map<String, List<String>>,
        manual: Boolean,
        online: Boolean,
    ): List<PicacgApiRouteCandidate> {
        if (!online || manual || snapshotRevision != configRevision || committedEndpoint == null) return emptyList()
        if (currentIndex !in apiUrls.indices || !hostOf(apiUrls[currentIndex]).equals(expectedHost, true) ||
            !committedEndpoint.domain.equals(expectedHost, true) ||
            probes.none { it.success && it.endpoint.normalizedForComparison() == committedEndpoint.normalizedForComparison() }
        ) {
            return emptyList()
        }

        return (1 until apiUrls.size).mapNotNull { offset ->
            val url = apiUrls[(currentIndex + offset) % apiUrls.size]
            val host = hostOf(url)
            probes.asSequence()
                .filter { it.success && it.endpoint.domain.equals(host, true) }
                .filter { probe ->
                    apiRouteRules.entries.any { (ruleHost, ips) ->
                        ruleHost.equals(host, true) && ips.any { it.trim() == probe.endpoint.ip }
                    }
                }
                .filter { it.endpoint.normalizedForComparison() != committedEndpoint.normalizedForComparison() }
                .minByOrNull(PicacgApiProbeEndpoint::elapsedMs)
                ?.let { PicacgApiRouteCandidate(url, it.endpoint) }
        }
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
}
