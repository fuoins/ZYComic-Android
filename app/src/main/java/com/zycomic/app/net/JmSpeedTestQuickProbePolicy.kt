package com.zycomic.app.net

import java.util.LinkedHashMap

/** Current configuration defines eligibility; history is handled only by the adaptive scheduler. */
internal object JmSpeedTestQuickProbePolicy {
    /** Keep every valid current endpoint in configuration/DNS order; never add a historical candidate. */
    fun <T> currentCandidates(
        candidates: List<T>,
        key: (T) -> String,
        endpoint: (T) -> DomainIp,
    ): List<T> = candidates.distinctBy(key).filter { candidate ->
        endpoint(candidate).domain.isNotBlank() && endpoint(candidate).ip.isNotBlank()
    }

    fun isImageEndpointFailure(verdict: JmSpeedTester.ImgVerdict?, delayMs: Long): Boolean =
        verdict != JmSpeedTester.ImgVerdict.NO_IMAGE && delayMs >= Long.MAX_VALUE

    /** Fresh measurements rank first; untouched rows are retained only for independent history display. */
    fun rankPriorDelays(
        fresh: Map<DomainIp, Long>,
        untested: Map<DomainIp, Long>,
    ): LinkedHashMap<DomainIp, Long> = LinkedHashMap<DomainIp, Long>().apply {
        fresh.entries.sortedBy { it.value }.forEach { put(it.key, it.value) }
        untested.forEach { (endpoint, delay) -> if (endpoint !in this) put(endpoint, delay) }
    }

    fun mergeDelays(
        prior: Map<DomainIp, Long>,
        fresh: Map<DomainIp, Long>,
        tested: Set<DomainIp>,
    ): LinkedHashMap<DomainIp, Long> = LinkedHashMap<DomainIp, Long>().apply {
        fresh.entries.sortedBy { it.value }.forEach { put(it.key, it.value) }
        prior.forEach { (endpoint, delay) -> if (endpoint !in tested && endpoint !in this) put(endpoint, delay) }
    }

    fun mergeTestedAt(
        prior: Map<DomainIp, Long>,
        fresh: Map<DomainIp, Long>,
        tested: Set<DomainIp>,
        freshOrder: Iterable<DomainIp> = fresh.keys,
    ): LinkedHashMap<DomainIp, Long> = LinkedHashMap<DomainIp, Long>().apply {
        freshOrder.forEach { endpoint -> fresh[endpoint]?.let { time -> if (endpoint in tested) put(endpoint, time) } }
        fresh.forEach { (endpoint, time) -> if (endpoint in tested && endpoint !in this) put(endpoint, time) }
        prior.forEach { (endpoint, time) -> if (endpoint !in tested) put(endpoint, time) }
    }
}
