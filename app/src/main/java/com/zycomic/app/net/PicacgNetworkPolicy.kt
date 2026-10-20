package com.zycomic.app.net

/** Small deterministic rules shared by config validation and Picacg speed probes. */
internal object PicacgNetworkPolicy {
    fun poolsArePaired(hasApiPool: Boolean, hasImagePool: Boolean): Boolean = hasApiPool == hasImagePool

    /** Per Picacg's documented check, an HTTP 200–499 API-root response proves reachability. */
    fun apiRootReachable(statusCode: Int): Boolean = statusCode in 200..499

    /** A source passes only after the real sample GET is HTTP 200, image-typed, and successfully decoded. */
    fun imageSampleVerified(statusCode: Int, contentType: String?, decoded: Boolean): Boolean =
        statusCode == 200 && contentType.orEmpty().substringBefore(';').trim().startsWith("image/", ignoreCase = true) && decoded
}
