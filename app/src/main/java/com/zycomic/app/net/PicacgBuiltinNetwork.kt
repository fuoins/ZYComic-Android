package com.zycomic.app.net

import java.util.Locale

/** Immutable, client-owned Picacg network defaults. Remote DNS results can only augment Picacomic hosts. */
object PicacgBuiltinNetwork {
    val apiUrls: List<String> = listOf(
        "https://picaapi.go2778.com/",
        "https://picaapi.picacomic.com/",
    )

    /**
     * Content (page) storage buckets serving `/static/tobs` and `/static/sub_storage_*`.
     * The speed test selects the fastest root and pins it to a concrete IP; order only seeds
     * the first default and probe order, so the directly-reachable domestic mirror comes first.
     */
    val imageHosts: List<String> = listOf(
        "storage-b.safedataplj.com",
        "storage-b.go2778.com",
        "storage-b.picacomic.com",
    )

    /**
     * Cover/thumbnail imgproxy nodes. These are exactly the hosts that a storage-b bucket 301-redirects
     * `/static/tobeimg/...` to; each `img.{root}` mirrors `storage-b.{root}` and shares the same
     * Cloudflare anycast IPs. They serve thumbnails only — `/static/tobs` returns 502 on img nodes,
     * so they must never be mixed into [imageHosts]. They are not user-selectable: the chosen content
     * root determines the cover mirror automatically.
     */
    val coverHosts: List<String> = listOf(
        "img.safedataplj.com",
        "img.go2778.com",
        "img.picacomic.com",
    )

    /**
     * Fixed 50x67 imgproxy speed tile (base64url of a permanent storage-b origin JPEG). The insecure
     * form needs no signature, token, auth or redirect; the response is ~1.9KB JPEG and is reused for
     * every root/IP probe so a cold/unauthenticated start can still rank sources.
     */
    private const val SPEED_TILE_ORIGIN_BASE64 =
        "aHR0cHM6Ly9zdG9yYWdlLWIucGljYWNvbWljLmNvbS9zdGF0aWMvOWYyNmUxYTMtZmQyMC00MzMzLTg1YjgtZmM2ZjVmZTA4ODZmLmpwZw"

    /** Probe URL for a cover mirror; the returned tile is a stable ~1.9KB JPEG. */
    fun speedTileUrl(coverHost: String): String =
        "https://${coverHost.trim().lowercase(Locale.ROOT)}/insecure/rs:fill:50:67:0/$SPEED_TILE_ORIGIN_BASE64"

    /** Maps a content bucket `storage-b.{root}` to its cover mirror `img.{root}`; null if no mirror exists. */
    fun coverHostFor(contentHost: String?): String? {
        val host = normalizeHost(contentHost) ?: return null
        if (!host.startsWith("storage-b.")) return null
        val candidate = host.replaceFirst("storage-b.", "img.")
        return candidate.takeIf { c -> coverHosts.any { it.equals(c, ignoreCase = true) } }
    }

    /** Maps a cover mirror `img.{root}` back to its content bucket `storage-b.{root}`; null otherwise. */
    fun contentHostFor(coverHost: String?): String? {
        val host = normalizeHost(coverHost) ?: return null
        if (!host.startsWith("img.")) return null
        val candidate = host.replaceFirst("img.", "storage-b.")
        return candidate.takeIf { c -> imageHosts.any { it.equals(c, ignoreCase = true) } }
    }

    /** True when [host] is one of the cover imgproxy mirrors. */
    fun isCoverHost(host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        return coverHosts.any { it.equals(normalized, ignoreCase = true) }
    }

    /** Resolves a host against the built-in root rules; used as a last-resort DNS fallback for mirrors. */
    fun builtInLookup(host: String): List<String> = lookup(rootRules, host)

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
