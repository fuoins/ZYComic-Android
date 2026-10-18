package com.zycomic.app.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.IOException

/** Immutable identity captured for one library operation; the token is intentionally not a data-class field. */
internal class JmRequestSession internal constructor(
    val revision: Long,
    val accountName: String,
    internal val capturedToken: String,
) {
    /** Capture identity and token under the auth-store monitor, without retaining the lock for any I/O. */
    fun snapshotCookieJar(original: CookieJar, url: HttpUrl): CookieJar {
        val snapshot = synchronized(JmAuthStore) {
            requireCurrentLocked(this)
            original.loadForRequest(url).toList()
        }
        return object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = snapshot.filter { it.matches(url) }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(JmAuthStore) {
                    if (isCurrentLocked(this@JmRequestSession)) {
                        original.saveFromResponse(url, cookies)
                    }
                }
            }
        }
    }

    companion object {
        /** Returns null when no complete authenticated identity is available. */
        fun capture(): JmRequestSession? = synchronized(JmAuthStore) {
            val token = JmAuthStore.token() ?: return@synchronized null
            val name = JmAuthStore.accountName ?: return@synchronized null
            JmRequestSession(JmAuthStore.sessionRevision, name, token)
        }

        /** Fail fast before/after a request if another login or logout advanced the session revision. */
        fun requireCurrent(session: JmRequestSession) {
            synchronized(JmAuthStore) {
                requireCurrentLocked(session)
            }
        }

        /** Clear credentials only when a 401 belongs to the still-current account revision. */
        fun clearIfCurrent(session: JmRequestSession): Boolean = synchronized(JmAuthStore) {
            if (!isCurrentLocked(session)) return@synchronized false
            JmAuthStore.clear()
            true
        }

        internal fun isCurrentRevision(sessionRevision: Long, currentRevision: Long): Boolean =
            sessionRevision == currentRevision

        private fun isCurrentLocked(session: JmRequestSession): Boolean =
            isCurrentRevision(session.revision, JmAuthStore.sessionRevision) &&
                session.accountName == JmAuthStore.accountName

        private fun requireCurrentLocked(session: JmRequestSession) {
            if (!isCurrentLocked(session)) {
                throw IOException("JM 登录账号已切换，请重新加载后再操作")
            }
        }
    }
}
