package com.zycomic.app.net

import java.util.LinkedHashMap

/** Policies for the version-gated JM top-five quick retest. */
internal object JmSpeedTestQuickProbePolicy {
    fun selectPriorEndpoints(
        priorVersion: Int?,
        currentVersion: Int?,
        priorCompletedAtEpochMs: Long?,
        delays: Map<DomainIp, Long>,
        limit: Int = 5,
    ): List<DomainIp> {
        if (limit <= 0 || priorVersion != currentVersion || priorCompletedAtEpochMs == null || priorCompletedAtEpochMs <= 0L) return emptyList()
        return delays.entries
            .filter { entry ->
                entry.value >= 0L && entry.key.domain.isNotBlank() && entry.key.ip.isNotBlank()
            }
            .take(limit)
            .map { it.key }
    }

    /** All fresh results (including failures) rank by their new delay before untouched history. */
    fun rankPriorDelays(
        fresh: Map<DomainIp, Long>,
        untested: Map<DomainIp, Long>,
    ): LinkedHashMap<DomainIp, Long> = LinkedHashMap<DomainIp, Long>().apply {
        fresh.entries.sortedBy { it.value }.forEach { put(it.key, it.value) }
        untested.forEach { (endpoint, delay) -> if (endpoint !in this) put(endpoint, delay) }
    }

    /** All fresh results rank first by their new delay; untested history retains its insertion order and values. */
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
