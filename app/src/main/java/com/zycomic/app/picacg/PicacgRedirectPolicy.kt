package com.zycomic.app.picacg

import okhttp3.HttpUrl
import java.io.IOException

/** Validates the only redirect Picacg requests may follow. */
internal object PicacgRedirectPolicy {
    /** Fixed categories for logs; never include the supplied Location or any URL component. */
    fun diagnosticOutcome(source: HttpUrl, location: String?): String {
        if (source.scheme != "http") return "source-not-http"
        val target = location?.let(source::resolve) ?: return "missing-or-invalid-location"
        if (!target.host.equals(source.host, ignoreCase = true)) return "cross-host-rejected"
        if (target.scheme != "https") return "target-not-https"
        if (target.encodedPath != source.encodedPath || target.encodedQuery != source.encodedQuery) {
            return "path-or-query-changed"
        }
        if (target.username.isNotEmpty() || target.password.isNotEmpty()) return "url-user-info-rejected"
        return "same-host-https"
    }

    fun targetFor301(source: HttpUrl, location: String?): HttpUrl {
        if (source.scheme != "http") {
            throw IOException("Picacg 301 redirect rejected: source is not HTTP")
        }
        val target = location?.let(source::resolve)
            ?: throw IOException("Picacg 301 redirect rejected: invalid Location")
        if (target.scheme != "https" || !target.host.equals(source.host, ignoreCase = true)) {
            throw IOException("Picacg 301 redirect rejected: target must use HTTPS on the same hostname")
        }
        if (target.encodedPath != source.encodedPath || target.encodedQuery != source.encodedQuery) {
            throw IOException("Picacg 301 redirect rejected: path or query changed")
        }
        if (target.username.isNotEmpty() || target.password.isNotEmpty()) {
            throw IOException("Picacg 301 redirect rejected: URL user info is not allowed")
        }
        return target
    }
}
