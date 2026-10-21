package com.zycomic.app.net

/** Pure policy for the optional fast retest. History compatibility is intentionally version-only. */
internal object SpeedTestHistoryPolicy {
    const val RETEST_LIMIT = 5

    /** Stable, compact identity for one probe target. Callers supply all transport/probe inputs. */
    fun endpointIdentity(
        platform: String,
        kind: String,
        endpoint: DomainIp,
        request: String,
        route: String,
        conditions: String,
    ): String {
        val input = listOf(platform, kind, endpoint.domain.lowercase(java.util.Locale.ROOT), endpoint.ip, request, route, conditions)
            .joinToString("\u0000")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Reuse only exact endpoint identities; missing identities from legacy records never match. */
    fun <T> matchingHistory(
        values: Map<DomainIp, T>,
        savedIdentities: Map<DomainIp, String>,
        currentIdentities: Map<DomainIp, String>,
    ): LinkedHashMap<DomainIp, T> = LinkedHashMap<DomainIp, T>().apply {
        values.forEach { (endpoint, value) ->
            if (savedIdentities[endpoint]?.let { it.isNotBlank() && it == currentIdentities[endpoint] } == true) {
                put(endpoint, value)
            }
        }
    }

    fun isVersionCompatible(historyVersion: Int?, activeVersion: Int?): Boolean =
        historyVersion == activeVersion

    fun selectTopSuccessful(
        delays: Map<DomainIp, Long>,
        limit: Int = RETEST_LIMIT,
        successful: (DomainIp, Long) -> Boolean = { _, delay -> delay < Long.MAX_VALUE },
    ): List<DomainIp> = delays.entries
        .filter { (endpoint, delay) -> delay >= 0L && successful(endpoint, delay) }
        .sortedWith(compareBy<Map.Entry<DomainIp, Long>>({ it.value }, { it.key.domain.lowercase(java.util.Locale.ROOT) }, { it.key.ip }))
        .take(limit.coerceAtLeast(0))
        .map { it.key }

    /** Select the next fixed-size slice from an already ranked history, including previous failures. */
    fun selectRankedTop(delays: Map<DomainIp, Long>, limit: Int = RETEST_LIMIT): List<DomainIp> =
        delays.keys.take(limit.coerceAtLeast(0))

    /** Place this run's fresh outcomes in latency order, followed by the old untested ranking unchanged. */
    fun rankAfterRun(previous: Map<DomainIp, Long>, fresh: Map<DomainIp, Long>): Map<DomainIp, Long> = buildMap {
        fresh.entries.sortedWith(
            compareBy<Map.Entry<DomainIp, Long>>(
                { it.value },
                { it.key.domain.lowercase(java.util.Locale.ROOT) },
                { it.key.ip },
            ),
        ).forEach { (endpoint, delay) -> put(endpoint, delay) }
        previous.forEach { (endpoint, delay) -> if (endpoint !in fresh) put(endpoint, delay) }
    }

    /** Keep the previous ranking order; replace selected samples in place with fresh values. */
    fun merge(
        previous: Map<DomainIp, Long>,
        selected: Set<DomainIp>,
        fresh: Map<DomainIp, Long>,
    ): Map<DomainIp, Long> = buildMap {
        previous.forEach { (endpoint, delay) ->
            if (endpoint in selected) {
                fresh[endpoint]?.let { put(endpoint, it) }
            } else {
                put(endpoint, delay)
            }
        }
        fresh.forEach { (endpoint, delay) -> if (endpoint !in this) put(endpoint, delay) }
    }
}
