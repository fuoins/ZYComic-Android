package com.zycomic.app.reader

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
}
