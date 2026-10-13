package com.zycomic.app.net

/** A single hostname routed to one concrete address for probing or selection. */
data class DomainIp(
    val domain: String,
    val ip: String,
)
