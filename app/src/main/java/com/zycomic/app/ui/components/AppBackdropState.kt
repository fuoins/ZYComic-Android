package com.zycomic.app.ui.components

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 背景候选：URL 连同所属平台，背景层按平台选择图片加载器。 */
data class BackdropEntry(
    val platform: ContentPlatform?,
    val url: String,
)

/**
 * 全局随机封面背景的候选缓存。
 *
 * 每个 tab 加载出一页漫画后，把这一页的封面 URL 写进**自己 tab 的缓存**（key = tab id，
 * 不是平台：同一平台的不同 tab 各自独立）。背景从“所有开启背景开关的 tab”的缓存并集里
 * 随机取一张，所以任何页面都可能有背景，且切到空 tab 也不会黑屏。
 */
object AppBackdropState {
    private const val MAX_POOL_PER_TAB = 60

    private val pools = java.util.concurrent.ConcurrentHashMap<String, List<BackdropEntry>>()
    private val _current = MutableStateFlow<BackdropEntry?>(null)
    val current: StateFlow<BackdropEntry?> = _current.asStateFlow()

    /**
     * 池内容修订号：只在“池从空变非空”时 +1（此后翻页写入不再变化）。
     * 主界面把它作为随机触发信号——首次加载出封面时立刻随机一次，翻页不随机。
     */
    private val _poolRevision = MutableStateFlow(0L)
    val poolRevision: StateFlow<Long> = _poolRevision.asStateFlow()

    /** 由浏览页在列表加载后调用；空列表忽略。 */
    fun registerCovers(tabId: String, platform: ContentPlatform?, urls: List<String>) {
        val clean = urls
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_POOL_PER_TAB)
            .map { BackdropEntry(platform, it) }
        if (clean.isEmpty()) return
        val wasEmpty = pools.isEmpty()
        pools[tabId] = clean
        if (wasEmpty) _poolRevision.value += 1L
    }

    /**
     * 从所有开启背景开关的 tab 的缓存并集里随机取一张（优先排除当前正在显示的那张，
     * 避免切换时随机到同一张白换一次）。取不到时**保持当前背景不变**（不闪、不黑屏）。
     */
    fun randomFrom(enabledTabIds: Set<String>) {
        val candidates = enabledTabIds.flatMap { pools[it].orEmpty() }
        if (candidates.isEmpty()) return
        val currentUrl = _current.value?.url
        val fresh = candidates.filter { it.url != currentUrl }
        val picked = (if (fresh.isNotEmpty()) fresh else candidates).randomOrNull() ?: return
        _current.value = picked
    }

    fun clear() {
        _current.value = null
    }
}
