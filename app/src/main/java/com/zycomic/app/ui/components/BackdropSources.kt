package com.zycomic.app.ui.components

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局背景来源（外观设置页管理）：
 * - 自定义图片：用户选的本地图片路径，设置后**优先于一切**（不走随机源）。
 * - 随机图 API 源：内置多个在线随机图接口，可多选开启；开启后从开启的源里随机取。
 * - 全部关闭且无自定义 → 背景退化为中性渐变兜底。
 */
object BackdropSources {
    data class RandomSource(val id: String, val name: String, val url: String)

    val ALL: List<RandomSource> = listOf(
        RandomSource("elaina", "Elaina", "https://api.elaina.cat/random/"),
        RandomSource("picsum", "Picsum", "https://picsum.photos/1080/1920"),
        RandomSource("dmoe", "Dmoe 二次元", "https://www.dmoe.cc/random.php"),
        RandomSource("btstu", "彼岸壁纸", "https://api.btstu.cn/sjbz/api.php?lx=dongman&format=images"),
    )

    private const val PREFS_NAME = "zy_backdrop_sources"
    private const val KEY_CUSTOM_PATH = "custom_path"
    private const val KEY_ENABLED = "enabled_ids"

    private val _customPath = MutableStateFlow<String?>(null)
    val customPath: StateFlow<String?> = _customPath.asStateFlow()

    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    private var initialized = false

    /** App 启动时调用一次。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _customPath.value = prefs.getString(KEY_CUSTOM_PATH, null)
        _enabled.value = prefs.getStringSet(KEY_ENABLED, null)?.toSet() ?: emptySet()
    }

    fun setCustomPath(context: Context, path: String?) {
        _customPath.value = path
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (path == null) edit.remove(KEY_CUSTOM_PATH) else edit.putString(KEY_CUSTOM_PATH, path)
        edit.apply()
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val next = if (enabled) _enabled.value + id else _enabled.value - id
        _enabled.value = next
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENABLED, next).apply()
    }

    /** 随机取一个开启的源；没有开启的源返回 null。 */
    fun randomSource(): RandomSource? = ALL.filter { it.id in _enabled.value }.randomOrNull()
}
