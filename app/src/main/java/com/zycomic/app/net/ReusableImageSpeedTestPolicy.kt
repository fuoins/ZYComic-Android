package com.zycomic.app.net

import java.security.MessageDigest
import java.util.Locale

/** Focused cache identity for image probes; unrelated lines, rules and config versions are excluded. */
internal object ReusableImageSpeedTestPolicy {
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun fingerprint(imageDomains: List<String>, ipMappings: Map<String, List<String>>): String {
        val domains = imageDomains.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
        val applicableDomains = domains.toSet()
        val mappings = ipMappings.entries
            .mapNotNull { (domain, ips) ->
                val normalizedDomain = domain.trim().lowercase(Locale.ROOT)
                if (normalizedDomain !in applicableDomains) return@mapNotNull null
                normalizedDomain to ips.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
            }
            .sortedBy { it.first }
        val content = buildString {
            append("image-domains\n")
            domains.forEach { append(it).append('\n') }
            append("image-ip-mappings\n")
            mappings.forEach { (domain, ips) ->
                append(domain).append('=')
                ips.forEach { append(it).append(',') }
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isFresh(completedAtEpochMs: Long?, nowEpochMs: Long): Boolean =
        completedAtEpochMs != null && completedAtEpochMs <= nowEpochMs && nowEpochMs - completedAtEpochMs <= MAX_AGE_MS

    fun canReuse(
        snapshotFingerprint: String,
        currentFingerprint: String,
        snapshotNetworkIdentity: String,
        currentNetworkIdentity: String,
        completedAtEpochMs: Long?,
        nowEpochMs: Long,
    ): Boolean = snapshotFingerprint == currentFingerprint &&
        snapshotNetworkIdentity == currentNetworkIdentity &&
        isFresh(completedAtEpochMs, nowEpochMs)

    fun matchesResolvedEndpoints(snapshotEndpoints: Set<DomainIp>, currentEndpoints: Set<DomainIp>): Boolean =
        snapshotEndpoints == currentEndpoints
}
