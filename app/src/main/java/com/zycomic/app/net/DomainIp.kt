package com.zycomic.app.net

/** A hostname/address endpoint; an empty IP denotes a JM transport-managed hostname route. */
data class DomainIp(
    val domain: String,
    val ip: String,
)

internal fun DomainIp.normalizedForComparison(): DomainIp = DomainIp(
    domain = domain.lowercase(java.util.Locale.ROOT),
    ip = ip.trim().lowercase(java.util.Locale.ROOT),
)
