package com.zycomic.app.reader

import com.zycomic.app.net.DomainIp
import java.util.Locale

/** Reader presentation helpers for the JM sources2 image-domain pool. */
object JmReaderImageSourcePolicy {
    fun rankedDomains(domains: List<String>, delaysMs: Map<String, Long>): List<String> =
        domains.withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> {
                    delayFor(it.value, delaysMs)?.takeIf { delay -> delay < Long.MAX_VALUE } ?: Long.MAX_VALUE
                }.thenBy { it.index },
            )
            .map { it.value }

    fun delayFor(domain: String, delaysMs: Map<String, Long>): Long? =
        delaysMs[domain.lowercase(Locale.ROOT)]
            ?: delaysMs.entries.firstOrNull { it.key.equals(domain, ignoreCase = true) }?.value

    fun rankedEndpoints(
        delaysMs: Map<DomainIp, Long>,
        successfulEndpoints: Set<DomainIp>,
        domainOrder: List<String>,
    ): List<DomainIp> = delaysMs.keys.filter { endpoint ->
        domainOrder.any { it.equals(endpoint.domain, ignoreCase = true) }
    }.sortedWith(
        compareBy<DomainIp> {
            if (it in successfulEndpoints && (delaysMs[it] ?: Long.MAX_VALUE) < Long.MAX_VALUE) 0 else 1
        }.thenBy { delaysMs[it]?.takeIf { delay -> delay < Long.MAX_VALUE } ?: Long.MAX_VALUE }
            .thenBy { endpoint -> domainOrder.indexOfFirst { it.equals(endpoint.domain, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it } }
            .thenBy { it.ip.lowercase(Locale.ROOT) },
    )

    fun delayFor(endpoint: DomainIp, delaysMs: Map<DomainIp, Long>): Long? =
        delaysMs[endpoint] ?: delaysMs.entries.firstOrNull {
            it.key.domain.equals(endpoint.domain, ignoreCase = true) && it.key.ip.equals(endpoint.ip, ignoreCase = true)
        }?.value
}
