package com.zycomic.app.platform

enum class ContentPlatform { MANWA, PICACG, JM }
data class PolicyState(
    val revision: Long = 0L,
    val useLastLineResult: Boolean = false,
    val useLastResult: Boolean = false,
)
object StartupPlatformPolicy {
    val state = kotlinx.coroutines.flow.MutableStateFlow(PolicyState())
    fun isEnabled(platform: ContentPlatform): Boolean = true
    fun load(context: android.content.Context) = Unit
    fun setManwaEnabled(enabled: Boolean): Boolean {
        state.value = state.value.copy(revision = state.value.revision + 1L)
        return true
    }
}
