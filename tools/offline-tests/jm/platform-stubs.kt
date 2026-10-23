package com.zycomic.app.platform

import kotlinx.coroutines.flow.MutableStateFlow

enum class ContentPlatform { MANWA, PICACG, JM }
data class PolicyState(val revision: Long = 0L)
object StartupPlatformPolicy {
    var enabled = true
    val state = MutableStateFlow(PolicyState())
    fun isEnabled(platform: ContentPlatform): Boolean = enabled && platform == ContentPlatform.JM
}
