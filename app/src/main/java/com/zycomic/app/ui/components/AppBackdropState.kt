package com.zycomic.app.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局随机封面背景的封面池。
 *
 * 浏览页每加载出一页漫画，就把这一页的封面 URL 按平台注册进来（只保留最近 N 张）；
 * 切换标签页 / 底部导航页时，按当前 tab 配置的“背景源”随机取一张。
 */
object AppBackdropState {
    private const val MAX_POOL_PER_SOURCE = 60

    private val pools = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    /** 由浏览页在列表加载后调用；空列表忽略。 */
    fun registerCovers(platformId: String, urls: List<String>) {
        val clean = urls.filter { it.isNotBlank() }.distinct().take(MAX_POOL_PER_SOURCE)
        if (clean.isEmpty()) return
        pools[platformId] = clean
    }

    /** 从指定来源（平台 id 列表）随机取一张；全部为空时回落到任意池，再没有则清空。 */
    fun randomFrom(sourceIds: List<String>) {
        val candidates = sourceIds.flatMap { pools[it].orEmpty() }
        val picked = candidates.randomOrNull() ?: pools.values.flatten().randomOrNull()
        _current.value = picked
    }

    fun clear() {
        _current.value = null
    }
}
