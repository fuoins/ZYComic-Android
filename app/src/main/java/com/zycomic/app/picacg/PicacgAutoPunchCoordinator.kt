package com.zycomic.app.picacg

/** User choice for the optional automatic Picacg punch-in behavior. */
internal interface PicacgAutoPunchPreference {
    var enabled: Boolean
}

/** Minimal persistence boundary so policy tests need no Android runtime. */
internal interface PicacgAutoPunchBooleanStore {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

internal class StoredPicacgAutoPunchPreference(
    private val store: PicacgAutoPunchBooleanStore,
) : PicacgAutoPunchPreference {
    override var enabled: Boolean
        get() = store.getBoolean(KEY, DEFAULT_ENABLED)
        set(value) = store.putBoolean(KEY, value)

    private companion object {
        const val KEY = "picacg_auto_punch_in_enabled"
        const val DEFAULT_ENABLED = true
    }
}

/** Pure eligibility rule: unknown/already-punched state is never permission to write. */
internal object PicacgAutoPunchPolicy {
    fun shouldPunch(enabled: Boolean, isPunched: Boolean?, sessionIsCurrent: Boolean): Boolean =
        enabled && isPunched == false && sessionIsCurrent
}

/**
 * Loads the visible profile once and conditionally performs one check-in for that page entry.
 * The injected punch operation owns the POST plus its authoritative profile refresh. No failure
 * is retried here; a later manual refresh only reads the profile through its caller.
 */
internal class PicacgAutoPunchCoordinator(
    private val preference: PicacgAutoPunchPreference,
    private val isLoggedIn: () -> Boolean,
    private val currentSessionRevision: () -> Long,
    private val loadProfile: suspend (expectedSessionRevision: Long) -> PicacgProfile,
    private val punchInAndRefresh: suspend (expectedSessionRevision: Long) -> PicacgProfile,
) {
    suspend fun loadProfileForPage(expectedSessionRevision: Long): PicacgProfile? {
        if (!isCurrent(expectedSessionRevision)) return null

        val initialProfile = loadProfile(expectedSessionRevision)
        if (!isCurrent(expectedSessionRevision)) return null

        val shouldPunch = PicacgAutoPunchPolicy.shouldPunch(
            enabled = preference.enabled,
            isPunched = initialProfile.isPunched,
            sessionIsCurrent = isCurrent(expectedSessionRevision),
        )
        if (!shouldPunch) return initialProfile

        val refreshedProfile = punchInAndRefresh(expectedSessionRevision)
        return refreshedProfile.takeIf { isCurrent(expectedSessionRevision) }
    }

    private fun isCurrent(expectedSessionRevision: Long): Boolean =
        isLoggedIn() && currentSessionRevision() == expectedSessionRevision
}
