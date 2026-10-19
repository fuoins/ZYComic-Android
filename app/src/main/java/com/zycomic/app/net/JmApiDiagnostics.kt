package com.zycomic.app.net

import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.Locale

/** No query values, bodies, arbitrary headers, credentials or literal endpoint addresses are logged. */
internal object JmApiDiagnostics {
    fun message(request: Request, response: Response, selected: DomainIp?, actual: DomainIp?): String = buildString {
        append("path=").append(safePath(request.url.encodedPath))
        append(" method=").append(request.method.takeIf { it in METHODS } ?: "OTHER")
        append(" status=").append(response.code)
        append(" selected=").append(endpointId(selected))
        append(" actual=").append(endpointId(actual))
        val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        append(" contentType=").append(
            when (type) {
                "application/json", "text/html", "text/plain" -> type
                else -> if (type.endsWith("+json")) "json" else "other"
            },
        )
        append(" cloudflareHeaderPresent=").append(response.header("Server").equals("cloudflare", true) || response.header("CF-Ray") != null)
        append(" challengeMarked=").append(response.header("cf-mitigated").equals("challenge", true))
        append(" wafHeaderPresent=").append(response.header("X-Sucuri-Block") != null || response.header("X-Akamai-Error") != null)
        append(" requestIdPresent=").append(listOf("CF-Ray", "X-Request-ID", "X-Amz-Cf-Id").any { response.header(it) != null })
        append(" authorizationPresent=").append(request.header("Authorization") != null)
        append(" cookiePresent=").append(request.header("Cookie") != null)
        append(" avsPresent=").append(request.headers.values("Cookie").any { header -> header.split(';').any { it.substringBefore('=').trim() == "AVS" } })
        append(" tokenPresent=").append(request.header("token") != null)
        append(" tokenparamPresent=").append(request.header("tokenparam") != null)
        append(" avsUpdatePresent=").append(response.headers.values("Set-Cookie").any(::isAvsCookie))
        append(" avsUpdateSuppressed=").append(!response.isSuccessful && response.headers.values("Set-Cookie").any(::isAvsCookie))
    }

    fun isAvsCookie(header: String): Boolean = header.substringBefore('=').trim() == "AVS"

    private fun endpointId(endpoint: DomainIp?): String = endpoint?.let {
        MessageDigest.getInstance("SHA-256").digest("${it.domain.lowercase(Locale.ROOT)}|${it.ip}".toByteArray())
            .take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    } ?: "unknown"

    private fun safePath(path: String): String = when {
        path in API_PATHS -> path
        Regex("/useredit/[0-9]+").matches(path) -> "/useredit/:id"
        else -> "/:other"
    }

    private val METHODS = setOf("GET", "POST", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS")
    private val API_PATHS = setOf(
        "/", "/confirm", "/login", "/register", "/forgot", "/album", "/chapter", "/search", "/latest",
        "/categories", "/filter", "/promote", "/promote_list", "/week", "/week/filter", "/serialization",
        "/favorite", "/favorite_folder", "/watch_list", "/like", "/comment", "/comment_delete", "/forum",
        "/daily", "/daily_chk", "/setting", "/hot_tags",
    )
}
