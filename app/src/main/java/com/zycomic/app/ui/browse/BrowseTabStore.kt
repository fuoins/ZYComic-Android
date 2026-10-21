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
    private const val JM_ALL_HOT_SEEDED_KEY = "jm_all_hot_seeded_v1"
    private const val PICACG_DEFAULT_TABS_SEEDED_KEY = "picacg_default_tabs_seeded_v1"
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun tabsKey(platform: ContentPlatform) = "tabs_json_${platform.id}"
    private fun seedKey(platform: ContentPlatform) = "seed_version_${platform.id}"
    private fun selectedTabKey(platform: ContentPlatform) = "selected_tab_${platform.id}"
    private fun filterKey(platform: ContentPlatform, tabId: String) = "filter_${platform.id}_$tabId"
    private fun jmFilterKey(tabId: String) = "jm_filters_$tabId"

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

    const val ID_JM_HOME = "jm_recommendations"
    const val JM_HOME_NAME = "jm首页"
    const val LEGACY_JM_HOME_NAME = "JM 推荐"
    const val PREVIOUS_JM_HOME_NAME = "首页"
    const val ID_PICACG_HOME = "picacg_home"
    const val PICACG_HOME_NAME = "哔咔首页"
    const val ID_PICACG_RANKING = "picacg_ranking"
    const val PICACG_RANKING_NAME = "哔咔排行"
    const val ID_PICACG_CATEGORY = "picacg_category"
    const val PICACG_CATEGORY_NAME = "哔咔分类"
    const val JM_ALL_HOT_NAME = "jm热门"
    val LEGACY_JM_ALL_HOT_NAMES = setOf("JM 全部热门", "JM全部热门")

    private fun specialLatest() = BrowseTabItem(id = ID_LATEST_ADD, name = LATEST_NAME, kind = TabKind.SPECIAL, builtin = false, pageType = BrowsePageType.LATEST)
    private fun specialRanking() = BrowseTabItem(id = ID_RANKING, name = RANKING_NAME, kind = TabKind.SPECIAL, builtin = false, pageType = BrowsePageType.RANKING)
    private fun specialYuzong() = BrowseTabItem(id = ID_YUZONG, name = YUZONG_NAME, kind = TabKind.SPECIAL, builtin = false, pageType = BrowsePageType.YUZONG_RANK)
    fun jmHomeTab() = BrowseTabItem(
        id = ID_JM_HOME,
        name = JM_HOME_NAME,
        kind = TabKind.FILTER,
        builtin = true,
        platformId = ContentPlatform.JM,
    )

    fun jmAllHotTab() = BrowseTabItem(
        id = JmBrowsePolicy.ALL_HOT_TAB_ID,
        name = JM_ALL_HOT_NAME,
        kind = TabKind.FILTER,
        builtin = true,
        platformId = ContentPlatform.JM,
    )

    fun picacgHomeTab() = BrowseTabItem(
        id = ID_PICACG_HOME,
        name = PICACG_HOME_NAME,
        kind = TabKind.FILTER,
        builtin = true,
        platformId = ContentPlatform.PICACG,
    )

    /** The legacy homepage identity is retained for existing users, but now represents the 24-hour ranking. */
    fun picacgRankingDefaultTab() = picacgHomeTab().copy(name = PICACG_RANKING_NAME)

    fun picacgRankingTab() = BrowseTabItem(
        id = ID_PICACG_RANKING,
        name = PICACG_RANKING_NAME,
        kind = TabKind.SPECIAL,
        pageType = BrowsePageType.PICACG_RANKING,
        platformId = ContentPlatform.PICACG,
    )

    fun picacgCategoryTab() = BrowseTabItem(
        id = ID_PICACG_CATEGORY,
        name = PICACG_CATEGORY_NAME,
        kind = TabKind.SPECIAL,
        pageType = BrowsePageType.PICACG_CATEGORY,
        platformId = ContentPlatform.PICACG,
    )

    fun defaultTabs(platform: ContentPlatform = ContentPlatform.MANWA): List<BrowseTabItem> {
        if (platform == ContentPlatform.JM) return listOf(jmHomeTab(), jmAllHotTab())
        if (platform == ContentPlatform.PICACG) return listOf(picacgRankingDefaultTab(), picacgCategoryTab())
        return listOf(
            specialYuzong(),
            BrowseTabItem(
                id = ID_GENERAL,
                name = "mw一般向热门",
                kind = TabKind.FILTER,
                builtin = true,
                filter = FilterSnapshot(gender = 2, tags = emptyList(), area = 0, end = 0, st = 2),
            ),
            BrowseTabItem(
                id = ID_JINMAN,
                name = "mw禁漫向热门",
                kind = TabKind.FILTER,
                builtin = true,
                filter = FilterSnapshot(gender = 1, tags = emptyList(), area = 0, end = 0, st = 2),
            ),
            BrowseTabItem(
                id = ID_REXUE,
                name = "mw热血热门",
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
                    if (item.id.isBlank()) null else item.copy(platformId = platform)
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
            if (mixed != null) {
                val normalized = mixed.map { item ->
                    if (item.platformId == ContentPlatform.JM && item.filter != null) item.copy(filter = null) else item
                }
                val selectedIdentity = loadSelectedTabOrNull()?.let { normalized.getOrNull(it) }?.let(BrowseTabPolicy::identity)
                val seedJmDefaults = !prefs.getBoolean(JM_ALL_HOT_SEEDED_KEY, false)
                val seedPicacgDefaults = !prefs.getBoolean(PICACG_DEFAULT_TABS_SEEDED_KEY, false)
                var next = normalized
                if (seedJmDefaults) next = BrowseTabPolicy.addMissingJmDefaults(next, defaultTabs(ContentPlatform.JM))
                if (next.none { it.platformId == ContentPlatform.PICACG && it.id == ID_PICACG_RANKING }) {
                    next = BrowseTabPolicy.addMissingPicacgHome(next, picacgRankingDefaultTab())
                }
                if (seedPicacgDefaults) next = addMissingPicacgCategory(next)
                val normalizedDefaults = renameLegacyPicacgHome(
                    if (seedJmDefaults || seedPicacgDefaults) migrateCompleteDefaultPreset(next) else next,
                )
                val selectedIndex = selectedIdentity?.let { selected -> normalizedDefaults.indexOfFirst { BrowseTabPolicy.identity(it) == selected } }
                saveAll(normalizedDefaults, selectedIndex)
                val editor = prefs.edit()
                if (seedJmDefaults) editor.putBoolean(JM_ALL_HOT_SEEDED_KEY, true)
                if (seedPicacgDefaults) editor.putBoolean(PICACG_DEFAULT_TABS_SEEDED_KEY, true)
                editor.apply()
                return normalizedDefaults
            }
        }

        val legacyTabsExist = prefs.contains(LEGACY_KEY_TABS) || ContentPlatform.entries.any { prefs.contains(tabsKey(it)) }
        if (!legacyTabsExist) {
            val defaults = defaultSharedTabs()
            saveAll(defaults)
            prefs.edit()
                .putBoolean(JM_ALL_HOT_SEEDED_KEY, true)
                .putBoolean(PICACG_DEFAULT_TABS_SEEDED_KEY, true)
                .apply()
            return defaults
        }
        val migrated = addMissingPicacgCategory(load(ContentPlatform.MANWA) + picacgHomeTab() + load(ContentPlatform.JM))
        val normalizedDefaults = renameLegacyPicacgHome(migrateCompleteDefaultPreset(migrated))
        saveAll(normalizedDefaults)
        prefs.edit()
            .putBoolean(JM_ALL_HOT_SEEDED_KEY, true)
            .putBoolean(PICACG_DEFAULT_TABS_SEEDED_KEY, true)
            .apply()
        return normalizedDefaults
    }

    private fun defaultSharedTabs(): List<BrowseTabItem> {
        val manwa = defaultTabs(ContentPlatform.MANWA).associateBy { it.id }
        return listOf(
            specialYuzong(),
            jmHomeTab(),
            jmAllHotTab(),
            picacgRankingDefaultTab(),
            picacgCategoryTab(),
            manwa.getValue(ID_GENERAL),
            manwa.getValue(ID_JINMAN),
            manwa.getValue(ID_REXUE),
        )
    }

    /** Convert only exact, untouched prior default sequences; customized names/order remain user-owned. */
    private fun migrateCompleteDefaultPreset(tabs: List<BrowseTabItem>): List<BrowseTabItem> {
        val manwa = defaultTabs(ContentPlatform.MANWA).associateBy { it.id }
        val previousDefaults = listOf(
            specialYuzong(),
            manwa.getValue(ID_GENERAL).copy(name = "一般向热门"),
            manwa.getValue(ID_JINMAN).copy(name = "禁漫向热门"),
            manwa.getValue(ID_REXUE).copy(name = "热血热门"),
            picacgHomeTab(),
            jmHomeTab().copy(name = PREVIOUS_JM_HOME_NAME),
            jmAllHotTab().copy(name = "全部热门"),
        )
        val previousCurrentDefaults = listOf(
            specialYuzong(),
            jmHomeTab(),
            jmAllHotTab(),
            picacgHomeTab(),
            manwa.getValue(ID_GENERAL),
            manwa.getValue(ID_JINMAN),
            manwa.getValue(ID_REXUE),
        )
        val defaults = defaultSharedTabs()
        return listOf(previousDefaults, previousCurrentDefaults).asSequence()
            .map(::withPicacgCategoryAfterHome)
            .mapNotNull { BrowseTabPolicy.migrateCompleteDefaultPreset(tabs, it, defaults) }
            .firstOrNull() ?: tabs
    }

    private fun renameLegacyPicacgHome(tabs: List<BrowseTabItem>): List<BrowseTabItem> =
        tabs.map { item ->
            if (item.platformId == ContentPlatform.PICACG && item.id == ID_PICACG_HOME && item.name == PICACG_HOME_NAME) {
                item.copy(name = PICACG_RANKING_NAME)
            } else {
                item
            }
        }

    private fun addMissingPicacgCategory(tabs: List<BrowseTabItem>): List<BrowseTabItem> {
        val rankingId = if (tabs.any { it.platformId == ContentPlatform.PICACG && it.id == ID_PICACG_RANKING }) {
            ID_PICACG_RANKING
        } else {
            ID_PICACG_HOME
        }
        return BrowseTabPolicy.addFixedPicacgTab(tabs, picacgCategoryTab(), rankingId) ?: tabs
    }

    private fun withPicacgCategoryAfterHome(tabs: List<BrowseTabItem>): List<BrowseTabItem> =
        tabs.flatMap { item ->
            if (item.platformId == ContentPlatform.PICACG && item.id == ID_PICACG_HOME) listOf(item, picacgCategoryTab()) else listOf(item)
        }

    fun saveAll(tabs: List<BrowseTabItem>, selectedTabIndex: Int? = null) {
        val array = JSONArray()
        tabs.forEach { array.put(it.toJson()) }
        val editor = prefs.edit().putString(MIXED_KEY_TABS, array.toString())
        selectedTabIndex?.let { editor.putInt(MIXED_KEY_SELECTED_TAB, it.coerceAtLeast(0)) }
        editor.apply()
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

    fun loadSelectedTabOrNull(): Int? = if (prefs.contains(MIXED_KEY_SELECTED_TAB)) loadSelectedTab() else null

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

    /** JM browse filters have a separate schema and keyspace from Manwa FilterSnapshot values. */
    internal fun loadJmFilters(tabId: String): JmBrowsePolicy.Filters? =
        prefs.getString(jmFilterKey(tabId), null)?.let { raw ->
            runCatching {
                JmFilterSnapshot.fromJson(JSONObject(raw), JmBrowsePolicy.MODE_CATEGORY).toFilters()
            }.getOrNull()
        }

    internal fun saveJmFilters(tabId: String, filters: JmBrowsePolicy.Filters) {
        JmFilterSnapshot.fromFilters(filters).toJson().let { prefs.edit().putString(jmFilterKey(tabId), it.toString()).apply() }
    }

    fun deleteJmFilters(tabId: String) {
        prefs.edit().remove(jmFilterKey(tabId)).apply()
    }

    fun reset(platform: ContentPlatform = ContentPlatform.MANWA): List<BrowseTabItem> {
        val defaults = defaultTabs(platform)
        save(defaults, platform)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("filter_${platform.id}_") }.forEach { editor.remove(it) }
        if (platform == ContentPlatform.JM) prefs.all.keys.filter { it.startsWith("jm_filters_") }.forEach { editor.remove(it) }
        editor.putInt(seedKey(platform), SEED_VERSION).putInt(selectedTabKey(platform), 0).apply()
        return defaults
    }

    fun resetAll(): List<BrowseTabItem> {
        val defaults = defaultSharedTabs()
        saveAll(defaults)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("filter_manwa_") || it.startsWith("filter_jm_") }.forEach { editor.remove(it) }
        prefs.all.keys.filter { it.startsWith("jm_filters_") }.forEach { editor.remove(it) }
        editor.putInt(seedKey(ContentPlatform.MANWA), SEED_VERSION)
            .putInt(seedKey(ContentPlatform.JM), SEED_VERSION)
            .putInt(seedKey(ContentPlatform.PICACG), SEED_VERSION)
            .putInt(MIXED_KEY_SELECTED_TAB, 0)
            .apply()
        return defaults
    }
}
