package com.zycomic.app.platform

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Startup-time policy for content platforms and remembered platform selections. */
object StartupPlatformPolicy {
    data class State(
        val disableManwa: Boolean = true,
        val disableJm: Boolean = false,
        val useLastResult: Boolean = true,
        /** Increments whenever any preference value changes; also invalidates stale network-test results. */
        val revision: Long = 0,
    )

    private const val PREFS = "zycomic_startup_platform_policy"
    private const val KEY_DISABLE_MANWA = "disable_manwa"
    private const val KEY_DISABLE_JM = "disable_jm"
    private const val KEY_USE_LAST_RESULT = "use_last_result"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var loaded = false
    private lateinit var preferences: android.content.SharedPreferences

    /** Loads once; repeat calls are intentionally idempotent and never reset [State.revision]. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedManwa = preferences.getBoolean(KEY_DISABLE_MANWA, true)
        val storedJm = preferences.getBoolean(KEY_DISABLE_JM, false)
        val normalized = normalize(storedManwa, storedJm)
        _state.value = State(
            disableManwa = normalized.first,
            disableJm = normalized.second,
            useLastResult = preferences.getBoolean(KEY_USE_LAST_RESULT, true),
            revision = _state.value.revision,
        )
        if (normalized.first != storedManwa || normalized.second != storedJm) {
            preferences.edit()
                .putBoolean(KEY_DISABLE_MANWA, normalized.first)
                .putBoolean(KEY_DISABLE_JM, normalized.second)
                .apply()
        }
        loaded = true
    }

    fun setDisableManwa(disable: Boolean) {
        update(disableManwa = disable)
    }

    fun setDisableJm(disable: Boolean) {
        update(disableJm = disable)
    }

    fun setUseLastResult(use: Boolean) {
        update(useLastResult = use)
    }

    fun isEnabled(platform: ContentPlatform): Boolean = isEnabled(_state.value, platform)

    /** Resolve missing or disabled choices to an available platform, preferring JM by default. */
    fun resolve(platform: ContentPlatform?): ContentPlatform = resolve(_state.value, platform)

    /** Returns false and displays the required guidance when an interactive selection is disabled. */
    fun checkSelection(context: Context, platform: ContentPlatform): Boolean {
        if (isEnabled(platform)) return true
        val message = when (platform) {
            ContentPlatform.MANWA -> "请在每次开屏页关闭不用漫蛙"
            ContentPlatform.JM -> "请在每次开屏页关闭不用禁漫天堂"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return false
    }

    @Synchronized
    private fun update(
        disableManwa: Boolean? = null,
        disableJm: Boolean? = null,
        useLastResult: Boolean? = null,
    ) {
        check(loaded) { "StartupPlatformPolicy.load(context) must be called before setting platform preferences" }
        val previous = _state.value
        val next = transition(previous, disableManwa, disableJm, useLastResult)
        if (next == previous) return
        preferences.edit()
            .putBoolean(KEY_DISABLE_MANWA, next.disableManwa)
            .putBoolean(KEY_DISABLE_JM, next.disableJm)
            .putBoolean(KEY_USE_LAST_RESULT, next.useLastResult)
            .apply()
        _state.value = next
    }

    /** Pure policy helpers, exposed for focused unit tests. */
    internal fun transition(
        state: State,
        disableManwa: Boolean? = null,
        disableJm: Boolean? = null,
        useLastResult: Boolean? = null,
    ): State {
        val manwa = disableManwa ?: state.disableManwa
        val jm = disableJm ?: state.disableJm
        val normalized = when {
            disableManwa == true -> true to false
            disableJm == true -> false to true
            else -> normalize(manwa, jm)
        }
        val candidate = state.copy(
            disableManwa = normalized.first,
            disableJm = normalized.second,
            useLastResult = useLastResult ?: state.useLastResult,
        )
        return if (candidate.disableManwa == state.disableManwa &&
            candidate.disableJm == state.disableJm &&
            candidate.useLastResult == state.useLastResult
        ) {
            state
        } else {
            candidate.copy(revision = state.revision + 1)
        }
    }

    internal fun isEnabled(state: State, platform: ContentPlatform): Boolean = when (platform) {
        ContentPlatform.MANWA -> !state.disableManwa
        ContentPlatform.JM -> !state.disableJm
    }

    internal fun resolve(state: State, platform: ContentPlatform?): ContentPlatform {
        val requested = platform ?: ContentPlatform.JM
        if (isEnabled(state, requested)) return requested
        return when (requested) {
            ContentPlatform.MANWA -> ContentPlatform.JM
            ContentPlatform.JM -> ContentPlatform.MANWA
        }
    }

    private fun normalize(disableManwa: Boolean, disableJm: Boolean): Pair<Boolean, Boolean> =
        if (disableManwa && disableJm) true to false else disableManwa to disableJm
}
