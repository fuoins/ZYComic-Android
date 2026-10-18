package com.zycomic.app.reader

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Small, independently testable policy for the setting-provided related-cover image host. */
internal object JmRelatedMangaPolicy {
    data class SettingsCacheKey(val apiBaseUrl: String, val routeConfiguration: String)

    /** A short-lived, single-entry cache scoped to the active JM route/configuration. */
    class ImageHostCache(
        private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
        private val clockMillis: () -> Long = System::currentTimeMillis,
    ) {
        private data class Entry(val key: SettingsCacheKey, val host: String, val fetchedAt: Long)

        @Volatile private var entry: Entry? = null

        @Synchronized
        fun get(key: SettingsCacheKey): String? {
            val current = entry ?: return null
            if (current.key != key || clockMillis() - current.fetchedAt >= ttlMillis) {
                entry = null
                return null
            }
            return current.host
        }

        @Synchronized
        fun put(key: SettingsCacheKey, host: String) {
            entry = Entry(key, host, clockMillis())
        }

        @Synchronized
        fun clear() {
            entry = null
        }
    }

    fun settingImageHost(payload: Any): String? {
        val root = payload as? JSONObject ?: return null
        val settings = root.optJSONObject("data") ?: root
        val rawHost = settings.optString("img_host").trim()
        val url = rawHost.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.encodedPath !in listOf("", "/") || url.query != null || url.fragment != null) {
            return null
        }
        return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
    }

    fun coverUrl(imgHost: String, albumId: String, detailsAddtime: String): String? {
        if (!albumId.matches(Regex("[0-9]+")) || albumId.all { it == '0' }) return null
        val base = imgHost.toHttpUrlOrNull() ?: return null
        if (base.scheme != "https") return null
        return base.newBuilder()
            .addPathSegment("media")
            .addPathSegment("albums")
            .addPathSegment("${albumId}_3x4.jpg")
            .addQueryParameter("v", detailsAddtime)
            .build()
            .toString()
    }

    fun cacheKey(apiBaseUrl: String, routeConfiguration: String) =
        SettingsCacheKey(apiBaseUrl.trimEnd('/'), routeConfiguration)

    fun imageHostsForAlbum(requestedHost: String, fallbackHosts: List<String>): List<String> =
        (listOf(requestedHost) + fallbackHosts).filter(String::isNotBlank).distinctBy { it.lowercase() }

    private const val DEFAULT_TTL_MILLIS = 10 * 60 * 1000L
}
