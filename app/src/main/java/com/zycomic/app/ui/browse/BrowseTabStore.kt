package com.zycomic.app.ui.browse

import android.content.Context
import org.json.JSONArray

/** 分类页 Tab 列表持久化（SP + JSON），含首次种子化与版本迁移。 */
object BrowseTabStore {

    private const val PREFS = "zycomic_browse_tabs"
    private const val KEY_TABS = "tabs_json"
    private const val KEY_SEED = "seed_version"
    private const val SEED_VERSION = 5

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ---- 内置默认 tab（3 个筛选型热门） ----
    const val ID_GENERAL = "builtin_general"
    const val ID_JINMAN = "builtin_jinman"
    const val ID_REXUE = "builtin_rexue"

    /** v1 内置「最近更新」的旧 id，仅用于 v1→v2 迁移时识别并移除。 */
    private const val ID_LATEST_V1 = "builtin_latest"

    /** 最新更新（最近更新）作为可添加的特殊预设，固定 id，保证只可添加一次。 */
    const val ID_LATEST_ADD = "custom_latest"
    const val LATEST_NAME = "最新更新"

    /** gay排行（整体排行）作为可添加的特殊预设，固定 id，保证只可添加一次。 */
    const val ID_RANKING = "custom_ranking"
    const val RANKING_NAME = "gay排行"

    /** 宇总排行（搜索哔哩/腾讯/快看排行榜）作为可添加的特殊预设。 */
    const val ID_YUZONG = "custom_yuzong"
    const val YUZONG_NAME = "宇总排行"

    private fun specialLatest() = BrowseTabItem(id = ID_LATEST_ADD, name = LATEST_NAME, kind = TabKind.SPECIAL, builtin = false, pageType = BrowsePageType.LATEST)
    private fun specialRanking() = BrowseTabItem(id = ID_RANKING, name = RANKING_NAME, kind = TabKind.SPECIAL, builtin = false, pageType = BrowsePageType.RANKING)
    private fun specialYuzong() = BrowseTabItem(id = ID_YUZONG, name = YUZONG_NAME, kind = TabKind.SPECIAL, builtin = false, pageType = BrowsePageType.YUZONG_RANK)

    fun defaultTabs(): List<BrowseTabItem> = listOf(
        specialYuzong(),
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
    )

    /** 读取 tab 列表；首次/版本不符时种子化或迁移并持久化。解析失败回退默认。 */
    fun load(): List<BrowseTabItem> {
        val seeded = prefs.getInt(KEY_SEED, 0)
        val raw = prefs.getString(KEY_TABS, null)

        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                val list = mutableListOf<BrowseTabItem>().apply {
                    addAll(List(arr.length()) { BrowseTabItem.fromJson(arr.getJSONObject(it)) })
                }
                if (seeded == 1) {
                    list.removeAll { it.id == ID_LATEST_V1 }
                }
                if (seeded < 4) {
                    list.removeAll { it.id == ID_LATEST_ADD || it.id == ID_RANKING }
                    val yzIdx = list.indexOfFirst { it.id == ID_YUZONG }
                    if (yzIdx >= 0) { val yz = list.removeAt(yzIdx); list.add(0, yz) } else list.add(0, specialYuzong())
                }
                if (seeded < SEED_VERSION) {
                    val migrated = migrateLegacyFilterValues(list, seeded)
                    list.clear()
                    list.addAll(migrated)
                }
                if (list.isNotEmpty()) {
                    save(list)
                    prefs.edit().putInt(KEY_SEED, SEED_VERSION).apply()
                    return list
                }
            }
        }

        val def = defaultTabs()
        save(def)
        prefs.edit().putInt(KEY_SEED, SEED_VERSION).apply()
        return def
    }

    /** v5 maps persisted legacy area/end IDs once; all other tab data stays unchanged. */
    internal fun migrateLegacyFilterValues(tabs: List<BrowseTabItem>, seedVersion: Int): List<BrowseTabItem> {
        if (seedVersion >= SEED_VERSION) return tabs

        fun area(value: Int): Int = when (value) {
            1 -> 2
            2 -> 3
            3 -> 4
            4 -> 5
            5 -> 6
            6 -> 1
            else -> value
        }

        fun end(value: Int): Int = when (value) {
            1 -> 2
            2 -> 1
            else -> value
        }

        return tabs.map { tab ->
            val filter = tab.filter
            if (tab.kind != TabKind.FILTER || filter == null) {
                tab
            } else {
                tab.copy(filter = filter.copy(area = area(filter.area), end = end(filter.end)))
            }
        }
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
