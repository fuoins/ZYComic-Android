package com.zycomic.app.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONObject

/** The account statistics included in JM's login response; absent or malformed fields remain null. */
data class JmAccountSnapshot(
    val level: String? = null,
    val exp: String? = null,
    val nextLevelExp: String? = null,
    val coin: String? = null,
    val albumFavorites: String? = null,
    val albumFavoritesMax: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        putNullable("level", level)
        putNullable("exp", exp)
        putNullable("nextLevelExp", nextLevelExp)
        putNullable("coin", coin)
        putNullable("album_favorites", albumFavorites)
        putNullable("album_favorites_max", albumFavoritesMax)
    }

    private fun JSONObject.putNullable(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    companion object {
        fun fromJson(json: JSONObject): JmAccountSnapshot = JmAccountSnapshot(
            level = json.numericText("level"),
            exp = json.numericText("exp"),
            nextLevelExp = json.numericText("nextLevelExp"),
            coin = json.numericText("coin"),
            albumFavorites = json.numericText("album_favorites"),
            albumFavoritesMax = json.numericText("album_favorites_max"),
        )

        private fun JSONObject.numericText(key: String): String? {
            val value = opt(key) ?: return null
            if (value == JSONObject.NULL || (value !is String && value !is Number)) return null
            val text = value.toString().trim()
            return text.takeIf { it.isNotEmpty() && it.toLongOrNull()?.let { number -> number >= 0 } == true }
        }
    }
}

/** Opaque token capturing the login attempt and account session it is allowed to update. */
data class JmLoginAttempt(
    val generation: Long,
    val sessionRevision: Long,
    val preserveSessionOnUnauthorized: Boolean = false,
)

/** Pure persistence and identity rules shared by the auth store and its offline tests. */
internal object JmAccountSnapshotPolicy {
    fun encode(snapshot: JmAccountSnapshot): String = snapshot.toJson().toString()

    fun decode(serialized: String?): JmAccountSnapshot? = serialized?.let {
        runCatching { JmAccountSnapshot.fromJson(JSONObject(it)) }.getOrNull()
    }

    fun forAccount(
        profile: JmAccountSnapshot?,
        previousUsername: String?,
        previousUid: String?,
        nextUsername: String,
        nextUid: String?,
    ): JmAccountSnapshot? = profile.takeIf { previousUsername == nextUsername && previousUid == nextUid }
}

/** Generation plus session-revision guard prevents late login responses replacing a newer account. */
internal class JmLoginAttemptPolicy {
    private var generation = 0L

    fun begin(sessionRevision: Long, preserveSessionOnUnauthorized: Boolean = false): JmLoginAttempt {
        generation++
        return JmLoginAttempt(generation, sessionRevision, preserveSessionOnUnauthorized)
    }

    fun invalidate() {
        generation++
    }

    fun isCurrent(attempt: JmLoginAttempt, sessionRevision: Long): Boolean =
        attempt.generation == generation && attempt.sessionRevision == sessionRevision

    fun runIfCurrent(attempt: JmLoginAttempt, sessionRevision: Long, action: () -> Unit): Boolean {
        if (!isCurrent(attempt, sessionRevision)) return false
        action()
        return true
    }
}

/** Pure policy for opt-in credential persistence and account/revision isolation. */
internal object JmCredentialRefreshPolicy {
    fun shouldStorePassword(rememberPassword: Boolean, username: String?, password: String?): Boolean =
        rememberPassword && !username.isNullOrBlank() && !password.isNullOrBlank()

    fun belongsToAccount(
        savedAccountName: String?,
        savedUid: String?,
        currentAccountName: String?,
        currentUid: String?,
    ): Boolean = savedAccountName == currentAccountName && savedUid == currentUid

    fun isSameSession(expectedRevision: Long, currentRevision: Long): Boolean = expectedRevision == currentRevision
}

/** Process-local guard preventing overlapping explicit credential refreshes. */
internal class JmRefreshGate {
    private var active = false

    @Synchronized
    fun tryStart(): Boolean {
        if (active) return false
        active = true
        return true
    }

    @Synchronized
    fun finish() {
        active = false
    }
}

/** Login cookies are isolated until the matching login attempt is atomically accepted. */
internal class JmLoginCookieJar(
    private val original: CookieJar,
    requestUrl: HttpUrl,
) : CookieJar {
    private val initialCookies = original.loadForRequest(requestUrl).toList()
    private val bufferedWrites = mutableListOf<Pair<HttpUrl, List<Cookie>>>()

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = initialCookies.filter { it.matches(url) }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isNotEmpty()) bufferedWrites += url to cookies.toList()
    }

    @Synchronized
    fun replayBufferedCookies() {
        bufferedWrites.forEach { (url, cookies) -> original.saveFromResponse(url, cookies) }
        bufferedWrites.clear()
    }
}
