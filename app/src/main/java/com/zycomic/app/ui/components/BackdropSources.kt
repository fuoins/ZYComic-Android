package com.zycomic.app.ui.components

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 背景图模式：暗色与亮色各存一套自定义图，互不影响。 */
enum class BackdropMode(val key: String, val label: String) {
    DARK("dark", "暗色"),
    LIGHT("light", "亮色"),
    ;

    companion object {
        fun of(dark: Boolean): BackdropMode = if (dark) DARK else LIGHT

        /** 按当前资源配置判断（非 Compose 场景用，如配置导入导出）。 */
        fun of(context: Context): BackdropMode {
            val night = (
                context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                ) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            return of(night)
        }
    }
}

/**
 * 全局背景来源（外观设置页管理），按 [BackdropMode] 分两套：
 * - 自定义图片：用户选的本地图片路径，设置后**优先于一切**（不走随机源）。
 * - 分页自定义图：分类/书架/历史/我的/设置/搜索 各页可单独指定一张，优先于全局自定义图。
 * - 随机图源：网络接口，两套共用（内容本身与亮暗无关）。
 * - 全部关闭且无自定义 → 使用内置默认背景图（暗色/亮色各一张）。
 *
 * 单模式时代的旧键（custom_path / page_path_xxx）在 [init] 时灌进两套，两套共用同一张文件，
 * 删除时通过引用检查避免误删另一套还在用的图。
 */
object BackdropSources {
    data class RandomSource(val id: String, val name: String, val url: String)

    val ALL: List<RandomSource> = listOf(
        RandomSource("elaina", "Elaina", "https://api.elaina.cat/random/"),
    )

    /** 分页 key：主界面 5 个底部页 + 搜索页。 */
    val PAGE_KEYS: List<String> = listOf("browse", "library", "history", "profile", "settings", "search")

    private const val PREFS_NAME = "zy_backdrop_sources"
    private const val KEY_CUSTOM_PREFIX = "custom_path_"
    private const val KEY_ENABLED = "enabled_ids"
    private const val KEY_PAGE_PREFIX = "page_path_"

    /** 旧版本的单模式键，升级时迁移。 */
    private const val LEGACY_KEY_CUSTOM = "custom_path"
    private const val LEGACY_KEY_PAGE_PREFIX = "page_path_"

    private val _customPaths = MutableStateFlow<Map<BackdropMode, String>>(emptyMap())
    val customPaths: StateFlow<Map<BackdropMode, String>> = _customPaths.asStateFlow()

    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    private val _perPage = MutableStateFlow<Map<BackdropMode, Map<String, String>>>(emptyMap())
    val perPage: StateFlow<Map<BackdropMode, Map<String, String>>> = _perPage.asStateFlow()

    private var initialized = false

    /** App 启动时调用一次。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = prefs(context)

        // 旧值先读出来，作为两套的兜底：升级后用户的图不会凭空消失。
        val legacyCustom = prefs.getString(LEGACY_KEY_CUSTOM, null)
        val legacyPages = PAGE_KEYS.mapNotNull { key ->
            prefs.getString(LEGACY_KEY_PAGE_PREFIX + key, null)?.let { key to it }
        }.toMap()

        _customPaths.value = buildMap {
            BackdropMode.entries.forEach { mode ->
                val stored = prefs.getString(customKey(mode), null) ?: legacyCustom
                if (!stored.isNullOrBlank()) put(mode, stored)
            }
        }
        _perPage.value = buildMap {
            BackdropMode.entries.forEach { mode ->
                val pages = PAGE_KEYS.mapNotNull { key ->
                    val stored = prefs.getString(pagePrefsKey(key, mode), null) ?: legacyPages[key]
                    stored?.takeIf(String::isNotBlank)?.let { key to it }
                }.toMap()
                if (pages.isNotEmpty()) put(mode, pages)
            }
        }
        _enabled.value = prefs.getStringSet(KEY_ENABLED, null)?.toSet() ?: emptySet()

        // 把迁移结果写回新键，下次启动直接命中；旧键随后删除——
        // 否则用户清空某一套后重启，旧值会从遗留键里“复活”。
        val editor = prefs.edit()
        _customPaths.value.forEach { (mode, path) ->
            if (!prefs.contains(customKey(mode))) editor.putString(customKey(mode), path)
        }
        _perPage.value.forEach { (mode, pages) ->
            pages.forEach { (key, path) ->
                if (!prefs.contains(pagePrefsKey(key, mode))) editor.putString(pagePrefsKey(key, mode), path)
            }
        }
        editor.remove(LEGACY_KEY_CUSTOM)
        PAGE_KEYS.forEach { editor.remove(LEGACY_KEY_PAGE_PREFIX + it) }
        editor.apply()
    }

    fun customPath(mode: BackdropMode): String? = _customPaths.value[mode]

    fun pagePath(pageKey: String, mode: BackdropMode): String? = _perPage.value[mode]?.get(pageKey)

    /** 某一模式下的全部分页自定义图，供设置页渲染。 */
    fun pages(mode: BackdropMode): Map<String, String> = _perPage.value[mode].orEmpty()

    fun setCustomPath(context: Context, mode: BackdropMode, path: String?) {
        val old = _customPaths.value[mode]
        _customPaths.value = if (path.isNullOrBlank()) {
            _customPaths.value - mode
        } else {
            _customPaths.value + (mode to path)
        }
        val editor = prefs(context).edit()
        if (path.isNullOrBlank()) editor.remove(customKey(mode)) else editor.putString(customKey(mode), path)
        editor.apply()
        if (!old.isNullOrBlank() && old != path) deleteIfUnreferenced(old)
    }

    fun setPagePath(context: Context, pageKey: String, mode: BackdropMode, path: String?) {
        val old = pagePath(pageKey, mode)
        val current = _perPage.value[mode].orEmpty()
        val next = if (path.isNullOrBlank()) current - pageKey else current + (pageKey to path)
        _perPage.value = if (next.isEmpty()) _perPage.value - mode else _perPage.value + (mode to next)
        val editor = prefs(context).edit()
        val key = pagePrefsKey(pageKey, mode)
        if (path.isNullOrBlank()) editor.remove(key) else editor.putString(key, path)
        editor.apply()
        if (!old.isNullOrBlank() && old != path) deleteIfUnreferenced(old)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val next = if (enabled) _enabled.value + id else _enabled.value - id
        _enabled.value = next
        prefs(context).edit().putStringSet(KEY_ENABLED, next).apply()
    }

    /** 随机取一个开启的源；没有开启的源返回 null。 */
    fun randomSource(): RandomSource? = ALL.filter { it.id in _enabled.value }.randomOrNull()

    /** 删除文件前先确认没有别的模式/分页还在引用同一张图。 */
    private fun deleteIfUnreferenced(path: String) {
        if (path.isBlank()) return
        val inUse = _customPaths.value.values.any { it == path } ||
            _perPage.value.values.any { pages -> pages.values.any { it == path } }
        if (!inUse) runCatching { File(path).delete() }
    }

    private fun customKey(mode: BackdropMode): String = KEY_CUSTOM_PREFIX + mode.key

    private fun pagePrefsKey(pageKey: String, mode: BackdropMode): String =
        KEY_PAGE_PREFIX + pageKey + "_" + mode.key

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
