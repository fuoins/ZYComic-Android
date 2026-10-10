package com.zycomic.app.ui.browse

import android.content.Context
import org.json.JSONArray

/** 分类页 Tab 列表持久化（SP + JSON），含首次种子化与版本迁移。 */
object BrowseTabStore {

    private const val PREFS = "zycomic_browse_tabs"
    private const val KEY_TABS = "tabs_json"
    private const val KEY_SEED = "seed_version"
    private const val SEED_VERSION = 1

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ---- 内置默认 tab（受保护的内置项） ----
    const val ID_GENERAL = "builtin_general"
    const val ID_JINMAN = "builtin_jinman"
    const val ID_REXUE = "builtin_rexue"
    const val ID_LATEST = "builtin_latest"
    /** gay排行（整体排行）作为可添加的特殊预设，固定 id，保证只可添加一次。 */
    const val ID_RANKING = "custom_ranking"
    const val RANKING_NAME = "gay排行"

    fun defaultTabs(): List<BrowseTabItem> = listOf(
        BrowseTabItem(
            id = ID_GENERAL,
            name = "一般向热门",
            kind = TabKind.FILTER,
            builtin = true,
            filter = FilterSnapshot(gender = 2, tags = emptyList(), area = 0, end = 0, st = 2),
        ),
        BrowseTabItem(
            id = ID_JINMAN,
            name = "禁漫热门",
            kind = TabKind.FILTER,
            builtin = true,
            filter = FilterSnapshot(gender = 1, tags = emptyList(), area = 0, end = 0, st = 2),
        ),
        BrowseTabItem(
            id = ID_REXUE,
            name = "热血热门",
            kind = TabKind.FILTER,
            builtin = true,
            filter = FilterSnapshot(gender = 2, tags = listOf("热血"), area = 0, end = 0, st = 2),
        ),
        BrowseTabItem(
            id = ID_LATEST,
            name = "最近更新",
            kind = TabKind.SPECIAL,
            builtin = true,
            pageType = BrowsePageType.LATEST,
        ),
    )

    /** 读取 tab 列表；首次/版本不符时种子化并持久化。解析失败也回退默认。 */
    fun load(): List<BrowseTabItem> {
        val seeded = prefs.getInt(KEY_SEED, 0)
        val raw = prefs.getString(KEY_TABS, null)
        if (seeded == SEED_VERSION && !raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                val list = List(arr.length()) { BrowseTabItem.fromJson(arr.getJSONObject(it)) }
                // 至少要有一个 tab，且内置最近更新必须存在
                if (list.isNotEmpty() && list.any { it.id == ID_LATEST }) return list
            }
        }
        val def = defaultTabs()
        save(def)
        prefs.edit().putInt(KEY_SEED, SEED_VERSION).apply()
        return def
    }

    fun save(tabs: List<BrowseTabItem>) {
        val arr = JSONArray()
        tabs.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_TABS, arr.toString()).apply()
    }

    fun reset(): List<BrowseTabItem> {
        val def = defaultTabs()
        save(def)
        prefs.edit().putInt(KEY_SEED, SEED_VERSION).apply()
        return def
    }
}
