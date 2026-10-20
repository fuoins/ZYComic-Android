package com.zycomic.app.picacg

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Produces fixed, redacted diagnostics; never returns a URL, host, path, query, or response content. */
internal object PicacgDiagnosticPolicy {
    enum class BaseKind(val label: String) {
        API_BASE("api-base"),
        WEBSITE_ROOT("website-root"),
        UNKNOWN("unknown"),
    }

    // The documented public site roots differ from the API's picaapi subdomain. The classifier is
    // diagnostic-only: it does not rewrite or reject the configured URL or affect request signing.
    private val websiteRoots =
        setOf(
            "picacomic.com",
            "manhuabika.com",
            "diwodiwo.xyz",
            "go2778.com",
        )

    fun classifyBase(url: String): BaseKind {
        val parsed = url.toHttpUrlOrNull() ?: return BaseKind.UNKNOWN
        val labels = parsed.host.lowercase().split('.')
        if (labels.firstOrNull() in setOf("api", "picaapi")) {
            return BaseKind.API_BASE
        }
        if (parsed.host.lowercase() in websiteRoots || labels.firstOrNull() == "www") return BaseKind.WEBSITE_ROOT
        if (parsed.pathSegments.firstOrNull() == "api") return BaseKind.API_BASE
        return BaseKind.UNKNOWN
    }

    fun signInBase(baseUrl: String): String =
        "Picacg sign-in configured endpoint=${classifyBase(baseUrl).label}"

    fun redirectResponse(source: HttpUrl, status: Int, location: String?): String {
        val sourceKind = classifyBase(source.toString()).label
        val outcome = PicacgRedirectPolicy.diagnosticOutcome(source, location)
        return "Picacg HTTP $status redirect; source=$sourceKind; location=$outcome"
    }
}
