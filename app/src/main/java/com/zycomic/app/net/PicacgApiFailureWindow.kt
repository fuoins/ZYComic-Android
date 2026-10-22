package com.zycomic.app.net

import java.util.concurrent.ConcurrentHashMap

/** Three consecutive failures per concrete endpoint and network generation; offline calls are inert. */
internal class PicacgApiFailureWindow(private val threshold: Int = 3) {
    private data class FailureState(val count: Int, val networkRevision: Long)

    private val failures = ConcurrentHashMap<DomainIp, FailureState>()

    init {
        require(threshold > 0)
    }

    /** Returns true starting with the threshold failure; an offline event never changes the streak. */
    fun recordFailure(endpoint: DomainIp, networkRevision: Long, online: Boolean): Boolean {
        if (!online) return false
        val key = endpoint.normalizedForComparison()
        val count = failures.compute(key) { _, previous ->
            val continuing = previous?.takeIf { it.networkRevision == networkRevision }?.count ?: 0
            FailureState((continuing + 1).coerceAtMost(threshold), networkRevision)
        }?.count ?: 0
        return count >= threshold
    }

    fun recordResponse(endpoint: DomainIp, networkRevision: Long, online: Boolean) {
        if (!online) return
        val key = endpoint.normalizedForComparison()
        if (failures[key]?.networkRevision == networkRevision) failures.remove(key)
    }

    fun failureCount(endpoint: DomainIp): Int = failures[endpoint.normalizedForComparison()]?.count ?: 0
}
