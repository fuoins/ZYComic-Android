package com.zycomic.app.net

import java.util.Locale

/** Immutable, client-owned Picacg network defaults. Remote DNS results can only augment Picacomic hosts. */
object PicacgBuiltinNetwork {
    val apiUrls: List<String> = listOf(
        "https://picaapi.go2778.com/",
        "https://picaapi.picacomic.com/",
    )

    /** The only selectable image origins; DNS-only aliases are deliberately not image sources. */
    val imageHosts: List<String> = listOf(
        "storage-b.go2778.com",
        "storage-b.picacomic.com",
    )

    private val rootRules = linkedMapOf(
        "go2778.com" to listOf("104.21.42.75", "172.67.159.59"),
        "picacomic.com" to listOf("104.20.42.9", "172.66.173.71"),
        "safedataplj.com" to listOf("104.21.75.42", "172.67.213.2"),
    )

    /** DNS role aliases are exact entries, not selectable image hosts. */
    private val roleLabels = listOf("picaapi", "img", "storage-b", "storage1")

    /**
     * Returns fixed client-owned rules plus vetted SmartDNS candidates for picacomic.com only.
     * Baseline addresses always stay first and are never replaced by the remote response.
     */
    fun rules(smartIps: List<String> = emptyList()): Map<String, List<String>> {
        val rules = linkedMapOf<String, List<String>>()
        rootRules.forEach { (root, ips) ->
            rules[root] = ips
            roleLabels.forEach { role -> rules["$role.$root"] = ips }
        }

        val smartCandidates = smartIps.asSequence()
            .map(String::trim)
            .filter(::isPublicIpv4)
            .distinct()
            .toList()
        if (smartCandidates.isNotEmpty()) {
            rules.keys.filter { isWithinDomain(it, "picacomic.com") }.forEach { host ->
                rules[host] = (rules.getValue(host) + smartCandidates).distinct()
            }
        }
        return rules.mapValues { (_, ips) -> ips.toList() }
    }

    /** Exact host wins; otherwise match the longest root-domain suffix on a label boundary. */
    fun lookup(rules: Map<String, List<String>>, host: String): List<String> {
        val normalizedHost = normalizeHost(host) ?: return emptyList()
        val normalizedRules = rules.entries.mapNotNull { (ruleHost, ips) ->
            normalizeHost(ruleHost)?.let { it to ips }
        }
        normalizedRules.firstOrNull { (ruleHost, _) -> ruleHost == normalizedHost }?.let { return it.second.toList() }
        return normalizedRules
            .asSequence()
            .filter { (ruleHost, _) -> normalizedHost.endsWith(".$ruleHost") }
            .maxByOrNull { (ruleHost, _) -> ruleHost.length }
            ?.second
            ?.toList()
            .orEmpty()
    }

    private fun isWithinDomain(host: String, root: String): Boolean =
        host.equals(root, ignoreCase = true) || host.endsWith(".$root", ignoreCase = true)

    private fun normalizeHost(host: String): String? {
        val trimmed = host.trim()
        val withoutFinalDot = if (trimmed.endsWith('.')) trimmed.dropLast(1) else trimmed
        val normalized = withoutFinalDot.lowercase(Locale.ROOT)
        if (normalized.isEmpty() || normalized.length > 253) return null
        val labels = normalized.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-' ||
                    label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
            }
        ) {
            return null
        }
        return normalized
    }
}
