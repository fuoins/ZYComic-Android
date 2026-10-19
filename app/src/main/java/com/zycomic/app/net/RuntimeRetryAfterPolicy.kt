package com.zycomic.app.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** A 429 health check never gets probed again before its Retry-After deadline. */
internal class RuntimeRetryAfterPolicy(
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val epochMs: () -> Long = System::currentTimeMillis,
) {
    private val deadlines = ConcurrentHashMap<DomainIp, Long>()

    fun isBlocked(endpoint: DomainIp): Boolean {
        val key = endpoint.normalizedForComparison()
        val deadline = deadlines[key] ?: return false
        if (monotonicMs() < deadline) return true
        deadlines.remove(key, deadline)
        return false
    }

    fun record(endpoint: DomainIp, retryAfter: String?) {
        val value = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val delay = value.toLongOrNull()?.takeIf { it >= 0L }?.let {
            it.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
        } ?: runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - epochMs())
                .coerceAtLeast(0L)
        }.getOrNull() ?: return
        val now = monotonicMs()
        val deadline = runCatching { Math.addExact(now, delay) }.getOrDefault(Long.MAX_VALUE)
        deadlines.merge(endpoint.normalizedForComparison(), deadline, ::maxOf)
    }
}
