package com.zycomic.app.net

import java.net.URI

/** Small deterministic rules shared by config validation and Picacg speed probes. */
internal object PicacgNetworkPolicy {
    fun poolsArePaired(hasApiPool: Boolean, hasImagePool: Boolean): Boolean = hasApiPool == hasImagePool

    /** The public website is not an API route. Do not substitute a host or change remote config. */
    fun apiBaseAllowed(baseUrl: String): Boolean = runCatching {
        val uri = URI(baseUrl.trim())
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.host.lowercase() !in setOf("picacomic.com", "www.picacomic.com")
    }.getOrDefault(false)

    /** An API-root response may prove reachability, but a redirect never validates this endpoint. */
    fun apiRootReachable(statusCode: Int): Boolean = statusCode in 200..499 && statusCode !in 300..399

    /** A source passes only after the cover-tile GET is HTTP 200, image-typed, and carries an image magic header. */
    fun imageTileVerified(statusCode: Int, contentType: String?, imageMagic: Boolean): Boolean =
        statusCode == 200 && contentType.orEmpty().substringBefore(';').trim().startsWith("image/", ignoreCase = true) && imageMagic
}
