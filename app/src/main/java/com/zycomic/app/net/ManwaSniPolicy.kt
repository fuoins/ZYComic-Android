package com.zycomic.app.net

import java.util.Locale

/** Direct Manwa transport omits SNI only for explicitly configured hosts outside Cloudflare. */
internal object ManwaSniPolicy {
    fun isConfiguredHost(host: String, configuredDomains: Set<String>): Boolean {
        val normalizedHost = normalizeHost(host)
        return normalizedHost.isNotEmpty() && configuredDomains.any { normalizeHost(it) == normalizedHost }
    }

    fun shouldOmitSni(host: String, configuredDomains: Set<String>, peerIsCloudflare: Boolean): Boolean =
        !peerIsCloudflare && isConfiguredHost(host, configuredDomains)

    private fun normalizeHost(host: String): String = host.trim().trimEnd('.').lowercase(Locale.ROOT)
}
