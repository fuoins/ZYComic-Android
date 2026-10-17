package com.zycomic.app.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** JM age-gate cookies stay in an in-memory jar separate from all Manwa cookies. */
class JmCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        newCookies.forEach { cookie ->
            cookies.removeAll { existing ->
                existing.name == cookie.name && existing.domain == cookie.domain && existing.path == cookie.path
            }
            if (cookie.expiresAt > now) cookies += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun hasCookie(url: HttpUrl, name: String): Boolean {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.any { it.name == name && it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
    }
}
