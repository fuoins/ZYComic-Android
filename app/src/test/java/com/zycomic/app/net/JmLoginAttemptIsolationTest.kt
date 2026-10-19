package com.zycomic.app.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmLoginAttemptIsolationTest {
    @Test
    fun `late login cookies and 401 cannot mutate a newer account`() {
        val accountUrl = "https://jm.offline.invalid/login".toHttpUrl()
        val sharedJar = MemoryJar()
        sharedJar.saveFromResponse(accountUrl, listOf(cookie("AVS", "new-account")))
        val attempts = JmLoginAttemptPolicy()
        val oldAttempt = attempts.begin(sessionRevision = 12)
        val oldResponseJar = JmLoginCookieJar(sharedJar, accountUrl)
        oldResponseJar.saveFromResponse(accountUrl, listOf(cookie("AVS", "old-login")))

        val newAttempt = attempts.begin(sessionRevision = 13)
        var accountName: String? = "new-account"
        val staleCookieCommit = attempts.runIfCurrent(oldAttempt, sessionRevision = 13) {
            oldResponseJar.replayBufferedCookies()
        }
        val staleUnauthorizedClear = attempts.runIfCurrent(oldAttempt, sessionRevision = 13) {
            accountName = null
        }

        assertFalse(staleCookieCommit)
        assertFalse(staleUnauthorizedClear)
        assertEquals("new-account", accountName)
        assertEquals("new-account", sharedJar.loadForRequest(accountUrl).single().value)
        assertTrue(attempts.isCurrent(newAttempt, sessionRevision = 13))
    }

    @Test
    fun `accepted current login replays buffered cookies after account commit`() {
        val accountUrl = "https://jm.offline.invalid/login".toHttpUrl()
        val sharedJar = MemoryJar()
        val attempts = JmLoginAttemptPolicy()
        val currentAttempt = attempts.begin(sessionRevision = 20)
        val responseJar = JmLoginCookieJar(sharedJar, accountUrl)
        responseJar.saveFromResponse(accountUrl, listOf(cookie("AVS", "accepted-login")))
        var accountName: String? = null
        var sessionRevision = 20L

        assertTrue(
            attempts.runIfCurrent(currentAttempt, sessionRevision) {
                accountName = "accepted-account"
                sessionRevision++
                responseJar.replayBufferedCookies()
            },
        )
        assertEquals("accepted-account", accountName)
        assertEquals("accepted-login", sharedJar.loadForRequest(accountUrl).single().value)
        assertFalse(attempts.isCurrent(currentAttempt, sessionRevision))
    }

    private fun cookie(name: String, value: String): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain("jm.offline.invalid")
        .path("/")
        .build()

    private class MemoryJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies += cookie
            }
        }
    }
}
