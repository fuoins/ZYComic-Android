package com.zycomic.app.ui.browse

import android.content.SharedPreferences
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.ui.picacg.PicacgBrowseFilter
import com.zycomic.app.ui.picacg.PicacgRankingMode
import com.zycomic.app.ui.picacg.PicacgThemes
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class BrowseTabManagementRegressionTest {
    private var values = mutableMapOf<String, Any?>()

    @BeforeEach
    fun setUp() {
        values = mutableMapOf()
        restartStore()
    }

    @Test
    fun `new JM tabs append to the global tab list and return their immediate selection index`() {
        val current = listOf(
            BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first(),
            BrowseTabStore.picacgHomeTab(),
            BrowseTabStore.jmHomeTab(),
        )
        val added = BrowseTabStore.jmAllHotTab()

        val (next, selectedIndex) = BrowseTabPolicy.insertNewTab(current, added)

        assertEquals(current + added, next)
        assertEquals(current.lastIndex + 1, selectedIndex)
    }

    @Test
    fun `legacy home and new ranking persist all five modes and selected identity after restart`() {
        val modes = PicacgRankingMode.entries
        assertEquals(listOf("H24", "D7", "D30", "RANDOM", "LATEST"), modes.map { it.name })
        assertEquals(PicacgRankingMode.H24, BrowseTabStore.picacgHomeTab().picacgRankingMode)
        assertEquals(PicacgRankingMode.H24, BrowseTabStore.picacgRankingTab().picacgRankingMode)
        val initial = BrowseTabStore.loadAll()
        assertNotNull(initial.firstOrNull { it.id == BrowseTabStore.ID_PICACG_HOME })
        assertNotNull(initial.firstOrNull { it.id == BrowseTabStore.ID_PICACG_CATEGORY })
        for (mode in modes) {
            val home = BrowseTabStore.picacgHomeTab().copy(name = "自定义首页", picacgRankingMode = mode)
            val ranking = BrowseTabStore.picacgRankingTab().copy(name = "自定义排行", picacgRankingMode = mode)
            val tabs = initial.filterNot { it.id in setOf(home.id, ranking.id) } + listOf(home, ranking)
            BrowseTabStore.saveAll(tabs, tabs.lastIndex)
            restartStore()
            val restored = BrowseTabStore.loadAll()
            assertEquals(mode, restored.single { it.id == home.id }.picacgRankingMode)
            assertEquals(mode, restored.single { it.id == ranking.id }.picacgRankingMode)
            assertEquals(ranking.id, restored[BrowseTabStore.loadSelectedTab()].id)
            assertTrue(restored.single { it.id == home.id }.isPicacgRanking())
            assertTrue(restored.single { it.id == ranking.id }.isPicacgRanking())
        }
    }

    @Test
    fun `missing and unknown ranking modes restore the compatible 24 hour default`() {
        for (tab in listOf(BrowseTabStore.picacgHomeTab(), BrowseTabStore.picacgRankingTab())) {
            val old = tab.toJson().apply { remove("picacgRankingMode") }
            assertEquals(PicacgRankingMode.H24, BrowseTabItem.fromJson(old).picacgRankingMode)
            assertEquals(PicacgRankingMode.H24, BrowseTabItem.fromJson(old.put("picacgRankingMode", "future-mode")).picacgRankingMode)
        }
    }

    @Test
    fun `fixed category and custom tabs restore all 40 themes and four API sort values`() {
        val themes = PicacgThemes.titles
        val sorts = listOf("dd", "da", "ld", "vd")
        assertEquals(40, themes.size)
        assertEquals(sorts.toSet(), PicacgBrowseFilter.SORT_CODES)
        val defaults = BrowseTabStore.loadAll()
        for (theme in themes) {
            for (sort in sorts) {
                val filter = PicacgBrowseFilter(category = theme, sort = sort)
                val category = BrowseTabStore.picacgCategoryTab().copy(name = "自定义分类标题", picacgFilter = filter)
                val custom = category.copy(id = "custom_picacg_theme", name = "我的主题", kind = TabKind.FILTER, pageType = null)
                BrowseTabStore.saveAll(defaults.map { if (it.id == category.id) category else it } + custom)
                restartStore()
                val restored = BrowseTabStore.loadAll()
                assertEquals(filter, restored.single { it.id == category.id }.picacgFilter)
                assertEquals(filter, restored.single { it.id == custom.id }.picacgFilter)
                assertEquals("自定义分类标题", restored.single { it.id == category.id }.name)
            }
        }
    }

    @Test
    fun `JM category defaults preserve every API order and only fill missing order with tf`() {
        val apiSorts = com.zycomic.app.reader.JmSource.orderOptions.map { it.second }
        assertEquals(setOf("mr", "mv", "mv_m", "mv_w", "mv_t", "mp", "tf"), apiSorts.toSet())
        assertEquals(apiSorts, JmBrowsePolicy.orderOptions(com.zycomic.app.reader.JmSource.orderOptions).map { it.second })
        assertEquals("tf", JmBrowsePolicy.customCategoryFilters().order)
        assertEquals("tf", JmBrowsePolicy.customCategoryFilters(JmBrowsePolicy.Filters(order = "  ")).order)
        for (id in listOf("custom_jm_saved", JmBrowsePolicy.ALL_HOT_TAB_ID)) {
            for (order in apiSorts) {
                val selected = JmBrowsePolicy.customCategoryFilters(JmBrowsePolicy.Filters(category = "doujin", order = order))
                assertEquals(order, selected.order)
                BrowseTabStore.saveJmFilters(id, selected)
                restartStore()
                val stored = BrowseTabStore.loadJmFilters(id)
                assertEquals(selected, stored)
                assertEquals(order, JmBrowsePolicy.defaultFilters(id, stored).order)
            }
        }
    }

    @Test
    fun `platform plus internal identity survives arbitrary display names and independent filters`() {
        val ranking = BrowseTabStore.picacgRankingTab().copy(name = "纯爱")
        val category = BrowseTabStore.picacgCategoryTab().copy(name = "周榜", picacgFilter = PicacgBrowseFilter(category = "热血", sort = "vd"))
        assertTrue(ranking.isPicacgRanking())
        assertFalse(ranking.isPicacgCategory())
        assertTrue(category.isPicacgCategory())
        assertFalse(category.isPicacgRanking())
        assertEquals(BrowseTabPolicy.identity(ranking), BrowseTabPolicy.identity(ranking.copy(name = "另一个标题")))
        assertFalse(ranking.copy(platformId = ContentPlatform.MANWA).isPicacgRanking())
        val manwaFilter = FilterSnapshot(tags = listOf("冒险"), st = 1)
        BrowseTabStore.saveFilter(ContentPlatform.MANWA, category.id, manwaFilter)
        val jmFilter = JmBrowsePolicy.customCategoryFilters(JmBrowsePolicy.Filters(order = "mv_w"))
        BrowseTabStore.saveJmFilters(category.id, jmFilter)
        BrowseTabStore.loadAll()
        BrowseTabStore.saveAll(listOf(ranking, category), 1)
        restartStore()
        val restored = BrowseTabStore.loadAll()
        assertEquals(listOf(ranking, category), restored)
        assertEquals(manwaFilter, BrowseTabStore.loadFilter(ContentPlatform.MANWA, category.id))
        assertEquals(jmFilter, BrowseTabStore.loadJmFilters(category.id))
        assertEquals(setOf(ContentPlatform.PICACG), BrowseTabStore.defaultTabs(ContentPlatform.PICACG).map { it.platformId }.toSet())
    }

    @Test
    fun `temporary filter changes cannot change persisted category defaults on restart`() {
        val defaults = BrowseTabStore.loadAll()
        val category = defaults.single { it.isPicacgCategory() }.copy(picacgFilter = PicacgBrowseFilter(category = "纯爱", sort = "ld"))
        BrowseTabStore.saveAll(defaults.map { if (it.id == category.id) category else it })
        val session = SessionTabFilters<PicacgBrowseFilter>()
        val identity = "picacg:${category.id}"
        session.get(identity) { category.picacgFilter!! }
        session.set(identity, PicacgBrowseFilter(category = "热血", sort = "da"))
        restartStore()
        val stored = BrowseTabStore.loadAll().single { it.id == category.id }
        assertEquals(category.picacgFilter, stored.picacgFilter)
        assertEquals(category.picacgFilter, SessionTabFilters<PicacgBrowseFilter>().get(identity) { stored.picacgFilter!! })
        assertEquals("热血", session.get(identity) { error("must keep temporary override") }.category)
    }

    /** A fresh preferences instance restores only serialized values, not the previous in-memory proxy. */
    private fun restartStore() {
        values = values.toMutableMap()
        val loader = SharedPreferences::class.java.classLoader
        val editor = Proxy.newProxyInstance(loader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString", "putInt", "putBoolean", "putLong", "putFloat", "putStringSet" -> {
                    values[args!![0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    values.remove(args!![0] as String)
                    proxy
                }
                "clear" -> {
                    values.clear()
                    proxy
                }
                "commit" -> true
                else -> null
            }
        }
        val preferences = Proxy.newProxyInstance(loader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args!![0] as String)
                "getString", "getInt", "getBoolean", "getLong", "getFloat", "getStringSet" -> values[args!![0] as String] ?: args[1]
                else -> null
            }
        } as SharedPreferences
        BrowseTabStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, preferences)
    }
}
