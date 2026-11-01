package com.zycomic.app.net

import java.util.Locale

/** Builds stable, de-duplicated per-IP probe targets without replacing their URL hostname. */
object DomainIpProbePolicy {
    /**
     * [allowDomainFallback] is used on transports that must not pin addresses (system VPN):
     * the probe then keeps the hostname and lets system DNS resolve it, represented by a blank IP.
     */
    fun endpoints(
        hostname: String,
        ips: Iterable<String>,
        allowDomainFallback: Boolean = false,
    ): List<DomainIp> {
        if (hostname.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val resolved = ips.mapNotNull { rawIp ->
            val ip = rawIp.trim()
            if (ip.isEmpty()) return@mapNotNull null
            val key = ip.lowercase(Locale.ROOT)
            if (!seen.add(key)) return@mapNotNull null
            DomainIp(hostname, ip)
        }
        if (resolved.isEmpty() && allowDomainFallback) return listOf(DomainIp(hostname, ""))
        return resolved
    }
}
