package com.zycomic.app.ui.browse

import android.content.Context
import com.zycomic.app.platform.ContentPlatform
import org.json.JSONArray
import org.json.JSONObject

/** 分类页 Tab 配置（SP + JSON）；旧版按平台保存的配置会合并迁入共享列表。 */
object BrowseTabStore {

    private const val PREFS = "zycomic_browse_tabs"
    private const val MIXED_KEY_TABS = "tabs_json_mixed"
    private const val MIXED_KEY_SELECTED_TAB = "selected_tab_mixed"
    private const val LEGACY_KEY_TABS = "tabs_json"
    private const val LEGACY_KEY_SEED = "seed_version"
    private const val SEED_VERSION = 5
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun tabsKey(platform: ContentPlatform) = "tabs_json_${platform.id}"
    private fun seedKey(platform: ContentPlatform) = "seed_version_${platform.id}"
    private fun selectedTabKey(platform: ContentPlatform) = "selected_tab_${platform.id}"
    private fun filterKey(platform: ContentPlatform, tabId: String) = "filter_${platform.id}_$tabId"

    // ---- 默认漫蛙分类 ----
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

    fun defaultTabs(platform: ContentPlatform = ContentPlatform.MANWA): List<BrowseTabItem> {
        if (platform == ContentPlatform.JM) return emptyList()
        return listOf(
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
                name = "禁漫向热门",
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
    }

    /** 读取指定平台的 Tab；首次/版本不符时种子化或迁移并持久化。 */
    fun load(platform: ContentPlatform = ContentPlatform.MANWA): List<BrowseTabItem> {
        var seeded = prefs.getInt(seedKey(platform), 0)
        var raw = prefs.getString(tabsKey(platform), null)
        // 旧版列表只有一份，且只属于漫蛙；首次升级时迁入新的漫蛙 key。
        if (platform == ContentPlatform.MANWA && raw == null) {
            raw = prefs.getString(LEGACY_KEY_TABS, null)
            if (raw != null) seeded = prefs.getInt(LEGACY_KEY_SEED, 0)
        }

        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                val list = mutableListOf<BrowseTabItem>().apply {
                    addAll(List(arr.length()) { BrowseTabItem.fromJson(arr.getJSONObject(it)) })
                }
                if (seeded == 1) list.removeAll { it.id == ID_LATEST_V1 }
                if (platform == ContentPlatform.MANWA && seeded < 4) {
                    list.removeAll { it.id == ID_LATEST_ADD || it.id == ID_RANKING }
                    val yzIdx = list.indexOfFirst { it.id == ID_YUZONG }
                    if (yzIdx >= 0) {
                        val yz = list.removeAt(yzIdx)
                        list.add(0, yz)
                    } else {
                        list.add(0, specialYuzong())
                    }
                }
                if (platform == ContentPlatform.MANWA && seeded < SEED_VERSION) {
                    val migrated = migrateLegacyFilterValues(list, seeded)
                    list.clear()
                    list.addAll(migrated)
                }
                val normalized = list.mapNotNull { item ->
                    if (item.id.isBlank()) {
                        null
                    } else {
                        item.copy(
                            name = if (item.id == ID_JINMAN && item.name == "禁漫热门") "禁漫向热门" else item.name,
                            platformId = platform,
                        )
                    }
                }
                save(normalized, platform)
                prefs.edit().putInt(seedKey(platform), SEED_VERSION).apply()
                return normalized
            }
        }

        val defaults = defaultTabs(platform)
        save(defaults, platform)
        prefs.edit().putInt(seedKey(platform), SEED_VERSION).apply()
        return defaults
    }

    /** 加载共享标签列表；首次使用时把旧版漫蛙和禁漫配置合并，保留各 tab 的平台归属。 */
    fun loadAll(): List<BrowseTabItem> {
        val raw = prefs.getString(MIXED_KEY_TABS, null)
        if (!raw.isNullOrBlank()) {
            val mixed = runCatching {
                val array = JSONArray(raw)
                List(array.length()) { BrowseTabItem.fromJson(array.getJSONObject(it)) }
                    .filter { it.id.isNotBlank() }
            }.getOrNull()
            if (mixed != null) return mixed
        }

        val migrated = load(ContentPlatform.MANWA) + load(ContentPlatform.JM)
        saveAll(migrated)
        return migrated
    }

    fun saveAll(tabs: List<BrowseTabItem>) {
        val array = JSONArray()
        tabs.forEach { array.put(it.toJson()) }
        prefs.edit().putString(MIXED_KEY_TABS, array.toString()).apply()
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

    fun save(tabs: List<BrowseTabItem>, platform: ContentPlatform = ContentPlatform.MANWA) {
        val arr = JSONArray()
        tabs.forEach { arr.put(it.copy(platformId = platform).toJson()) }
        prefs.edit().putString(tabsKey(platform), arr.toString()).apply()
    }

    fun loadSelectedTab(platform: ContentPlatform): Int = prefs.getInt(selectedTabKey(platform), 0)

    fun loadSelectedTab(): Int = prefs.getInt(MIXED_KEY_SELECTED_TAB, 0)

    fun saveSelectedTab(platform: ContentPlatform, index: Int) {
        prefs.edit().putInt(selectedTabKey(platform), index.coerceAtLeast(0)).apply()
    }

    fun saveSelectedTab(index: Int) {
        prefs.edit().putInt(MIXED_KEY_SELECTED_TAB, index.coerceAtLeast(0)).apply()
    }

    fun loadFilter(platform: ContentPlatform, tabId: String): FilterSnapshot? =
        prefs.getString(filterKey(platform, tabId), null)?.let { raw ->
            runCatching { FilterSnapshot.fromJson(JSONObject(raw)) }.getOrNull()
        }

    fun saveFilter(platform: ContentPlatform, tabId: String, snapshot: FilterSnapshot) {
        prefs.edit().putString(filterKey(platform, tabId), snapshot.toJson().toString()).apply()
    }

    fun reset(platform: ContentPlatform = ContentPlatform.MANWA): List<BrowseTabItem> {
        val defaults = defaultTabs(platform)
        save(defaults, platform)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("filter_${platform.id}_") }.forEach { editor.remove(it) }
        editor.putInt(seedKey(platform), SEED_VERSION).putInt(selectedTabKey(platform), 0).apply()
        return defaults
    }

    fun resetAll(): List<BrowseTabItem> {
        val defaults = defaultTabs(ContentPlatform.MANWA) + defaultTabs(ContentPlatform.JM)
        saveAll(defaults)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("filter_manwa_") || it.startsWith("filter_jm_") }.forEach { editor.remove(it) }
        editor.putInt(seedKey(ContentPlatform.MANWA), SEED_VERSION)
            .putInt(seedKey(ContentPlatform.JM), SEED_VERSION)
            .putInt(MIXED_KEY_SELECTED_TAB, 0)
            .apply()
        return defaults
    }
}
