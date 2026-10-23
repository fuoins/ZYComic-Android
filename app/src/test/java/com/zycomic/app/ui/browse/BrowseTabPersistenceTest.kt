package com.zycomic.app.ui.browse

import android.content.SharedPreferences
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.ui.picacg.PicacgBrowseFilter
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class BrowseTabPersistenceTest {
    @Test
    fun `Picacg custom filters persist separately and never leak into other platforms`() {
        val tab = BrowseTabItem(
            id = "picacg-custom",
            name = "主题",
            kind = TabKind.FILTER,
            platformId = ContentPlatform.PICACG,
            picacgFilter = PicacgBrowseFilter(category = " 纯爱 ", sort = "vd"),
        )
        val encoded = tab.toJson()
        assertEquals(PicacgBrowseFilter(category = "纯爱", sort = "vd"), BrowseTabItem.fromJson(encoded).picacgFilter)
        assertFalse(encoded.has("filter"))
        val jm = tab.copy(platformId = ContentPlatform.JM).toJson()
        assertFalse(jm.has("picacgFilter"))
        jm.put("picacgFilter", encoded.getJSONObject("picacgFilter"))
        assertNull(BrowseTabItem.fromJson(jm).picacgFilter)
    }

    @Test
    fun `legacy Picacg custom tab without theme fields loads compatible defaults`() {
        val legacy = BrowseTabItem(id = "old", name = "旧主题", kind = TabKind.FILTER, platformId = ContentPlatform.PICACG).toJson()
        assertEquals(PicacgBrowseFilter(), BrowseTabItem.fromJson(legacy).picacgFilter)
    }

    @Test
    fun `complete legacy default preset migrates names and order while customized presets stay untouched`() {
        installMemoryPreferences()
        val legacy = legacyDefaultTabs()
        BrowseTabStore.saveAll(legacy, selectedTabIndex = 5)
        val jmFilters = JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, category = "doujin", order = "mv_m")
        BrowseTabStore.saveJmFilters(BrowseTabStore.ID_JM_HOME, jmFilters)

        val migrated = BrowseTabStore.loadAll()
        assertEquals(defaultIdentityOrder(), migrated.map(BrowseTabPolicy::identity))
        assertEquals(BrowseTabStore.JM_HOME_NAME, migrated[1].name)
        assertEquals(BrowseTabStore.JM_ALL_HOT_NAME, migrated[2].name)
        assertEquals(BrowseTabPolicy.identity(legacy[5]), BrowseTabPolicy.identity(migrated[BrowseTabStore.loadSelectedTab()]))
        assertEquals(jmFilters, BrowseTabStore.loadJmFilters(BrowseTabStore.ID_JM_HOME))

        installMemoryPreferences()
        val customName = legacy.map { item ->
            if (item.id == BrowseTabStore.ID_JM_HOME) item.copy(name = "我的首页") else item
        }
        val customOrder = customName.reversed()
        val selected = 4
        BrowseTabStore.saveAll(customOrder, selectedTabIndex = selected)
        val customized = BrowseTabStore.loadAll()
        val expectedCustomOrder = customOrder.flatMap { item ->
            if (item.id == BrowseTabStore.ID_PICACG_HOME) listOf(item, BrowseTabStore.picacgCategoryTab()) else listOf(item)
        }
        assertEquals(expectedCustomOrder.map(BrowseTabPolicy::identity), customized.map(BrowseTabPolicy::identity))
        assertEquals("我的首页", customized.first { it.id == BrowseTabStore.ID_JM_HOME }.name)
        assertEquals(BrowseTabPolicy.identity(customOrder[selected]), BrowseTabPolicy.identity(customized[BrowseTabStore.loadSelectedTab()]))
    }

    @Test
    fun `default shared tabs use the approved exact cross-platform order and reset restores it`() {
        installMemoryPreferences()
        val loaded = BrowseTabStore.loadAll()
        assertEquals(defaultIdentityOrder(), loaded.map(BrowseTabPolicy::identity))
        assertEquals(
            listOf("宇总排行", "jm首页", "jm热门", "哔咔排行", "哔咔分类", "mw一般向热门", "mw禁漫向热门", "mw热血热门"),
            loaded.map { it.name },
        )

        val restored = BrowseTabStore.resetAll()
        assertEquals(loaded.map { BrowseTabPolicy.identity(it) }, restored.map { BrowseTabPolicy.identity(it) })
    }

    @Test
    fun `customized default filter blocks legacy preset migration without changing selection or snapshot`() {
        installMemoryPreferences()
        val legacy = legacyDefaultTabs().toMutableList()
        val changedFilter = legacy[1].filter!!.copy(tags = listOf("用户筛选"))
        legacy[1] = legacy[1].copy(filter = changedFilter)
        BrowseTabStore.saveAll(legacy, selectedTabIndex = 1)

        val loaded = BrowseTabStore.loadAll()
        val expected = legacy.flatMap { item ->
            if (item.id == BrowseTabStore.ID_PICACG_HOME) listOf(item, BrowseTabStore.picacgCategoryTab()) else listOf(item)
        }
        assertEquals(expected.map(BrowseTabPolicy::identity), loaded.map(BrowseTabPolicy::identity))
        assertEquals(changedFilter, loaded.first { it.id == BrowseTabStore.ID_GENERAL }.filter)
        assertEquals(BrowseTabPolicy.identity(legacy[1]), BrowseTabPolicy.identity(loaded[BrowseTabStore.loadSelectedTab()]))
    }

    @Test
    fun `Picacg ranking and category migrate into old defaults without changing customized order or labels`() {
        installMemoryPreferences()
        val manwa = BrowseTabStore.defaultTabs(ContentPlatform.MANWA).associateBy { it.id }
        val previousDefaults = listOf(
            manwa.getValue(BrowseTabStore.ID_YUZONG),
            BrowseTabStore.jmHomeTab(),
            BrowseTabStore.jmAllHotTab(),
            BrowseTabStore.picacgHomeTab(),
            manwa.getValue(BrowseTabStore.ID_GENERAL),
            manwa.getValue(BrowseTabStore.ID_JINMAN),
            manwa.getValue(BrowseTabStore.ID_REXUE),
        )
        BrowseTabStore.saveAll(previousDefaults, selectedTabIndex = 3)

        val migrated = BrowseTabStore.loadAll()
        assertEquals(defaultIdentityOrder(), migrated.map(BrowseTabPolicy::identity))
        assertEquals(BrowseTabStore.PICACG_RANKING_NAME, migrated.first { it.id == BrowseTabStore.ID_PICACG_HOME }.name)
        assertEquals(BrowseTabStore.ID_PICACG_CATEGORY, migrated[BrowseTabStore.loadSelectedTab() + 1].id)
        assertEquals(BrowseTabStore.ID_PICACG_HOME, migrated[BrowseTabStore.loadSelectedTab()].id)

        installMemoryPreferences()
        val customized = previousDefaults.reversed().map { item ->
            if (item.id == BrowseTabStore.ID_PICACG_HOME) item.copy(name = "我的哔咔") else item
        }
        BrowseTabStore.saveAll(customized, selectedTabIndex = 2)
        val preserved = BrowseTabStore.loadAll()
        val expectedOrder = customized.flatMap { item ->
            if (item.id == BrowseTabStore.ID_PICACG_HOME) listOf(item, BrowseTabStore.picacgCategoryTab()) else listOf(item)
        }
        assertEquals(expectedOrder.map(BrowseTabPolicy::identity), preserved.map(BrowseTabPolicy::identity))
        assertEquals("我的哔咔", preserved.first { it.id == BrowseTabStore.ID_PICACG_HOME }.name)
        assertEquals(BrowseTabPolicy.identity(customized[2]), BrowseTabPolicy.identity(preserved[BrowseTabStore.loadSelectedTab()]))
    }

    @Test
    fun `adding missing fixed JM tab preserves existing mixed tabs and appends at its default boundary`() {
        val manwa = BrowseTabStore.defaultTabs(ContentPlatform.MANWA)
        val home = BrowseTabStore.jmHomeTab()
        val existing = listOf(manwa[0], BrowseTabStore.jmAllHotTab(), manwa[1])
        val added = BrowseTabPolicy.addFixedJmTab(existing, home)
        assertNotNull(added)
        assertEquals(
            listOf(manwa[0].id, BrowseTabStore.jmAllHotTab().id, manwa[1].id, home.id),
            added!!.first.map { it.id },
        )
        assertNull(BrowseTabPolicy.addFixedJmTab(added.first, home))

        val afterDelete = added.first.filterNot { it.platformId == ContentPlatform.JM && it.id == home.id }
        assertNotNull(BrowseTabPolicy.addFixedJmTab(afterDelete, home))
    }

    @Test
    fun `Picacg home joins the shared order and retains selected identity`() {
        installMemoryPreferences()
        val manwa = BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first()
        val jm = BrowseTabStore.jmHomeTab()
        val existing = listOf(manwa, jm)
        val withPicacg = BrowseTabPolicy.addMissingPicacgHome(existing, BrowseTabStore.picacgHomeTab())

        assertEquals(
            listOf(manwa.id, jm.id, BrowseTabStore.ID_PICACG_HOME),
            withPicacg.map { it.id },
        )
        assertEquals(existing, BrowseTabPolicy.addMissingPicacgHome(withPicacg, BrowseTabStore.picacgHomeTab()).filterNot { it.id == BrowseTabStore.ID_PICACG_HOME })

        BrowseTabStore.saveAll(existing, selectedTabIndex = 1)
        val migrated = BrowseTabStore.loadAll()
        assertEquals(BrowseTabStore.ID_PICACG_HOME, migrated[migrated.lastIndex - 1].id)
        assertEquals(BrowseTabStore.ID_PICACG_CATEGORY, migrated.last().id)
        assertEquals(BrowseTabPolicy.identity(jm), BrowseTabPolicy.identity(migrated[BrowseTabStore.loadSelectedTab()]))

        val reordered = BrowseTabPolicy.reorder(migrated, migrated.reversed(), BrowseTabStore.loadSelectedTab())!!
        assertEquals(jm.id, reordered.tabs[reordered.selectedIndex].id)
    }

    @Test
    fun `Picacg ranking follows JM all hot and category follows ranking`() {
        val manwa = BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first()
        val allHot = BrowseTabStore.jmAllHotTab()
        val home = BrowseTabStore.picacgHomeTab()
        val jmHome = BrowseTabStore.jmHomeTab()
        val base = listOf(jmHome, allHot, home, manwa)
        val withRanking = BrowseTabPolicy.addFixedPicacgTab(base, BrowseTabStore.picacgRankingTab(), JmBrowsePolicy.ALL_HOT_TAB_ID)!!
        val withCategory = BrowseTabPolicy.addFixedPicacgTab(withRanking, BrowseTabStore.picacgCategoryTab(), BrowseTabStore.ID_PICACG_RANKING)!!

        assertEquals(
            listOf(jmHome.id, allHot.id, BrowseTabStore.ID_PICACG_RANKING, BrowseTabStore.ID_PICACG_CATEGORY, home.id, manwa.id),
            withCategory.map { it.id },
        )
        assertNull(BrowseTabPolicy.addFixedPicacgTab(withCategory, BrowseTabStore.picacgRankingTab(), JmBrowsePolicy.ALL_HOT_TAB_ID))
    }

    @Test
    fun `batch reorder retains selected tab identity and stored platform filters`() {
        installMemoryPreferences()
        val jmHome = BrowseTabStore.jmHomeTab()
        val jmHot = BrowseTabStore.jmAllHotTab()
        val manwa = BrowseTabStore.defaultTabs(ContentPlatform.MANWA).take(2)
        val current = listOf(jmHome, manwa[0], jmHot, manwa[1])
        val manwaFilter = FilterSnapshot(gender = 3, tags = listOf("冒险"), area = 4, st = 1)
        val jmFilters = JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, order = "mv_m", category = "doujin")
        BrowseTabStore.saveFilter(ContentPlatform.MANWA, manwa[0].id, manwaFilter)
        BrowseTabStore.saveJmFilters(jmHome.id, jmFilters)
        BrowseTabStore.saveAll(current, selectedTabIndex = 2)

        val draft = listOf(manwa[0], manwa[1], jmHome, jmHot)
        assertNull(BrowseTabPolicy.reorder(current, draft.dropLast(1), selectedIndex = 2))
        val result = BrowseTabPolicy.reorder(current, draft, selectedIndex = 2)
        assertNotNull(result)
        assertEquals(jmHot.id, result!!.tabs[result.selectedIndex].id)
        BrowseTabStore.saveAll(result.tabs, selectedTabIndex = result.selectedIndex)

        val loaded = BrowseTabStore.loadAll()
        assertEquals(
            (draft + listOf(BrowseTabStore.picacgRankingDefaultTab(), BrowseTabStore.picacgCategoryTab()))
                .map(BrowseTabPolicy::identity),
            loaded.map { BrowseTabPolicy.identity(it) },
        )
        assertEquals(BrowseTabPolicy.identity(jmHot), BrowseTabPolicy.identity(loaded[BrowseTabStore.loadSelectedTab()]))
        assertEquals(manwaFilter, BrowseTabStore.loadFilter(ContentPlatform.MANWA, manwa[0].id))
        assertEquals(jmFilters, BrowseTabStore.loadJmFilters(jmHome.id))
    }

    @Test
    fun `discarded reorder draft leaves order selection and custom labels untouched`() {
        installMemoryPreferences()
        val home = BrowseTabStore.jmHomeTab().copy(name = "我的首页")
        val manwa = BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first()
        val hot = BrowseTabStore.jmAllHotTab()
        val current = listOf(manwa, home, hot)
        BrowseTabStore.saveAll(current, selectedTabIndex = 1)
        // A dialog may preview any number of draft moves without writing the store.
        val draft = current.reversed()
        BrowseTabPolicy.reorder(current, draft, 1)
        assertEquals(
            (current + listOf(BrowseTabStore.picacgRankingDefaultTab(), BrowseTabStore.picacgCategoryTab()))
                .map(BrowseTabPolicy::identity),
            BrowseTabStore.loadAll().map { BrowseTabPolicy.identity(it) },
        )
        assertEquals(BrowseTabPolicy.identity(home), BrowseTabPolicy.identity(BrowseTabStore.loadAll()[BrowseTabStore.loadSelectedTab()]))
    }

    @Test
    fun `reorder rejects duplicate or changed platform identities and retains live metadata`() {
        val home = BrowseTabStore.jmHomeTab().copy(name = "我的首页")
        val manwa = home.copy(platformId = ContentPlatform.MANWA, name = "同ID不同平台", filter = FilterSnapshot(st = 1))
        val current = listOf(home, manwa)
        assertNull(BrowseTabPolicy.reorder(current, listOf(home, home), 1))
        assertNull(BrowseTabPolicy.reorder(current, listOf(home, manwa.copy(id = "changed")), 1))
        val result = BrowseTabPolicy.reorder(current, listOf(manwa.copy(name = "过期草稿"), home), 1)!!
        assertEquals(listOf(manwa, home), result.tabs)
        assertEquals(0, result.selectedIndex)
    }

    @Test
    fun `seeding missing JM defaults preserves selected Manwa identity`() {
        installMemoryPreferences()
        val manwa = BrowseTabStore.defaultTabs(ContentPlatform.MANWA).take(2)
        val original = listOf(manwa[0], manwa[1], BrowseTabStore.jmHomeTab())
        BrowseTabStore.saveAll(original, selectedTabIndex = 1)
        val migrated = BrowseTabStore.loadAll()
        assertEquals(manwa[1], migrated[BrowseTabStore.loadSelectedTab()])
        assertEquals(
            original.map { BrowseTabPolicy.identity(it) },
            migrated.filterNot { it.id == JmBrowsePolicy.ALL_HOT_TAB_ID || it.platformId == ContentPlatform.PICACG }
                .map { BrowseTabPolicy.identity(it) },
        )
    }

    @Test
    fun `JM custom tabs reload their own filters without overwriting Manwa or other JM tabs`() {
        installMemoryPreferences()
        val first = JmBrowsePolicy.Filters(mode = 1, category = "doujin", order = "mv_m")
        val second = JmBrowsePolicy.Filters(mode = 1, category = "hanman", order = "tf")
        val manwa = FilterSnapshot(gender = 3, tags = listOf("冒险"), st = 2)

        BrowseTabStore.saveFilter(ContentPlatform.MANWA, "first", manwa)
        BrowseTabStore.saveJmFilters("first", first)
        BrowseTabStore.saveJmFilters("second", second)

        assertEquals(first, BrowseTabStore.loadJmFilters("first"))
        assertEquals(second, BrowseTabStore.loadJmFilters("second"))
        assertEquals(manwa, BrowseTabStore.loadFilter(ContentPlatform.MANWA, "first"))
        BrowseTabStore.deleteJmFilters("first")
        assertNull(BrowseTabStore.loadJmFilters("first"))
        assertEquals(second, BrowseTabStore.loadJmFilters("second"))
        assertEquals(manwa, BrowseTabStore.loadFilter(ContentPlatform.MANWA, "first"))
    }

    @Test
    fun `JM tab JSON discards legacy Manwa snapshot while Manwa tab keeps its filters`() {
        val manwaSnapshot = FilterSnapshot(gender = 3, area = 2, st = 1)
        val legacyJm = BrowseTabItem(
            id = "jm-custom",
            name = "JM custom",
            kind = TabKind.FILTER,
            filter = manwaSnapshot,
            platformId = ContentPlatform.JM,
        )
        val persistedJm = legacyJm.toJson()
        assertFalse(persistedJm.has("filter"))
        val oldJson = JSONObject(persistedJm.toString()).put("filter", manwaSnapshot.toJson())
        assertNull(BrowseTabItem.fromJson(oldJson).filter)

        val manwaTab = legacyJm.copy(id = "manwa-custom", platformId = ContentPlatform.MANWA)
        assertEquals(manwaSnapshot, BrowseTabItem.fromJson(JSONObject(manwaTab.toJson().toString())).filter)
    }

    @Test
    fun `renaming legacy homepage does not reorder a customized list`() {
        installMemoryPreferences()
        val custom = BrowseTabItem(id = "my-theme", name = "自定义主题", kind = TabKind.FILTER, filter = FilterSnapshot())
        val current = listOf(custom, BrowseTabStore.picacgHomeTab(), BrowseTabStore.jmHomeTab())
        BrowseTabStore.saveAll(current, selectedTabIndex = 0)
        val loaded = BrowseTabStore.loadAll()
        assertEquals(custom, loaded.first())
        assertEquals(BrowseTabStore.PICACG_RANKING_NAME, loaded.first { it.id == BrowseTabStore.ID_PICACG_HOME }.name)
        assertEquals(current.map(BrowseTabPolicy::identity), loaded.filterNot { it.id == BrowseTabStore.ID_PICACG_CATEGORY || it.id == JmBrowsePolicy.ALL_HOT_TAB_ID }.map(BrowseTabPolicy::identity))
        assertEquals(custom, loaded[BrowseTabStore.loadSelectedTab()])
    }

    @Test
    fun `existing explicit ranking does not acquire a duplicate legacy ranking`() {
        installMemoryPreferences()
        val current = listOf(BrowseTabStore.jmHomeTab(), BrowseTabStore.jmAllHotTab(), BrowseTabStore.picacgRankingTab())
        BrowseTabStore.saveAll(current, selectedTabIndex = 2)
        val loaded = BrowseTabStore.loadAll()
        assertFalse(loaded.any { it.id == BrowseTabStore.ID_PICACG_HOME })
        assertEquals(1, loaded.count { it.id == BrowseTabStore.ID_PICACG_RANKING })
        assertEquals(BrowseTabStore.ID_PICACG_CATEGORY, loaded.last().id)
        assertEquals(loaded, BrowseTabStore.loadAll())
        assertEquals(BrowseTabStore.ID_PICACG_RANKING, loaded[BrowseTabStore.loadSelectedTab()].id)
    }

    private fun legacyDefaultTabs(): List<BrowseTabItem> {
        val manwa = BrowseTabStore.defaultTabs(ContentPlatform.MANWA).associateBy { it.id }
        return listOf(
            BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first { it.id == BrowseTabStore.ID_YUZONG },
            manwa.getValue(BrowseTabStore.ID_GENERAL).copy(name = "一般向热门"),
            manwa.getValue(BrowseTabStore.ID_JINMAN).copy(name = "禁漫向热门"),
            manwa.getValue(BrowseTabStore.ID_REXUE).copy(name = "热血热门"),
            BrowseTabStore.picacgHomeTab(),
            BrowseTabStore.jmHomeTab().copy(name = BrowseTabStore.PREVIOUS_JM_HOME_NAME),
            BrowseTabStore.jmAllHotTab().copy(name = "全部热门"),
        )
    }

    private fun defaultIdentityOrder(): List<BrowseTabPolicy.Identity> = listOf(
        BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first { it.id == BrowseTabStore.ID_YUZONG },
        BrowseTabStore.jmHomeTab(),
        BrowseTabStore.jmAllHotTab(),
        BrowseTabStore.picacgRankingDefaultTab(),
        BrowseTabStore.picacgCategoryTab(),
        BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first { it.id == BrowseTabStore.ID_GENERAL },
        BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first { it.id == BrowseTabStore.ID_JINMAN },
        BrowseTabStore.defaultTabs(ContentPlatform.MANWA).first { it.id == BrowseTabStore.ID_REXUE },
    ).map(BrowseTabPolicy::identity)

    private fun installMemoryPreferences() {
        val values = mutableMapOf<String, Any?>()
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
                "apply" -> null
                else -> null
            }
        }
        val preferences = Proxy.newProxyInstance(loader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args!![0] as String)
                "getString", "getInt", "getBoolean", "getLong", "getFloat", "getStringSet" ->
                    values[args!![0] as String] ?: args[1]
                else -> null
            }
        } as SharedPreferences
        val field = BrowseTabStore::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        field.set(null, preferences)
    }
}
