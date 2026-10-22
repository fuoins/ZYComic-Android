package com.zycomic.app.platform

import kotlinx.coroutines.flow.MutableStateFlow

enum class ContentPlatform { MANWA, PICACG, JM }

object StartupPlatformPolicy {
    data class State(
        val disableManwa: Boolean = false,
        val disablePicacg: Boolean = false,
        val disableJm: Boolean = false,
        val useLastResult: Boolean = true,
        val useLastLineResult: Boolean = true,
        val revision: Long = 0L,
    )
    val state = MutableStateFlow(State())
    fun isEnabled(platform: ContentPlatform) = isEnabled(state.value, platform)
    fun isEnabled(policy: State, platform: ContentPlatform) = when (platform) {
        ContentPlatform.MANWA -> !policy.disableManwa
        ContentPlatform.PICACG -> !policy.disablePicacg
        ContentPlatform.JM -> !policy.disableJm
    }
}
