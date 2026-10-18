package com.zycomic.app.net

import java.util.Locale

/** Builds stable, de-duplicated per-IP probe targets without replacing their URL hostname. */
object DomainIpProbePolicy {
    fun endpoints(hostname: String, ips: Iterable<String>): List<DomainIp> {
        if (hostname.isBlank()) return emptyList()
        val seen = HashSet<String>()
        return ips.mapNotNull { rawIp ->
            val ip = rawIp.trim()
            if (ip.isEmpty()) return@mapNotNull null
            val key = ip.lowercase(Locale.ROOT)
            if (!seen.add(key)) return@mapNotNull null
            DomainIp(hostname, ip)
        }
    }
}
