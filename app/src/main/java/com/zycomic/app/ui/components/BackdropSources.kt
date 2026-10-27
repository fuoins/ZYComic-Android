package com.zycomic.app.ui.components

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局背景来源（外观设置页管理）：
 * - 自定义图片：用户选的本地图片路径，设置后**优先于一切**（不走随机源）。
 * - 分页自定义图：分类/书架/历史/我的/设置/搜索 各页可单独指定一张，优先于全局自定义图。
 * - 随机图 API 源：内置在线随机图接口，可开启；开启后从开启的源里随机取。
 * - 全部关闭且无自定义 → 背景退化为丰富渐变兜底。
 */
object BackdropSources {
    data class RandomSource(val id: String, val name: String, val url: String)

    val ALL: List<RandomSource> = listOf(
        RandomSource("elaina", "Elaina", "https://api.elaina.cat/random/"),
    )

    /** 分页 key：主界面 5 个底部页 + 搜索页。 */
    val PAGE_KEYS: List<String> = listOf("browse", "library", "history", "profile", "settings", "search")

    private const val PREFS_NAME = "zy_backdrop_sources"
    private const val KEY_CUSTOM_PATH = "custom_path"
    private const val KEY_ENABLED = "enabled_ids"
    private const val KEY_PAGE_PREFIX = "page_path_"

    private val _customPath = MutableStateFlow<String?>(null)
    val customPath: StateFlow<String?> = _customPath.asStateFlow()

    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    private val _perPage = MutableStateFlow<Map<String, String>>(emptyMap())
    val perPage: StateFlow<Map<String, String>> = _perPage.asStateFlow()

    private var initialized = false

    /** App 启动时调用一次。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _customPath.value = prefs.getString(KEY_CUSTOM_PATH, null)
        _enabled.value = prefs.getStringSet(KEY_ENABLED, null)?.toSet() ?: emptySet()
        _perPage.value = PAGE_KEYS.mapNotNull { key ->
            prefs.getString(KEY_PAGE_PREFIX + key, null)?.let { key to it }
        }.toMap()
    }

    fun setCustomPath(context: Context, path: String?) {
        _customPath.value = path
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (path == null) edit.remove(KEY_CUSTOM_PATH) else edit.putString(KEY_CUSTOM_PATH, path)
        edit.apply()
    }

    fun setPagePath(context: Context, pageKey: String, path: String?) {
        val next = if (path == null) _perPage.value - pageKey else _perPage.value + (pageKey to path)
        _perPage.value = next
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (path == null) edit.remove(KEY_PAGE_PREFIX + pageKey) else edit.putString(KEY_PAGE_PREFIX + pageKey, path)
        edit.apply()
    }

    fun pagePath(pageKey: String): String? = _perPage.value[pageKey]

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val next = if (enabled) _enabled.value + id else _enabled.value - id
        _enabled.value = next
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENABLED, next).apply()
    }

    /** 随机取一个开启的源；没有开启的源返回 null。 */
    fun randomSource(): RandomSource? = ALL.filter { it.id in _enabled.value }.randomOrNull()
}
