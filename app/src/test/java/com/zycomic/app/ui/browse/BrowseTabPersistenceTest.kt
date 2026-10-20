package com.zycomic.app.ui.browse

import android.content.SharedPreferences
import com.zycomic.app.platform.ContentPlatform
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class BrowseTabPersistenceTest {
    @Test
    fun `legacy JM default names migrate but customized fixed tab names remain`() {
        installMemoryPreferences()
        BrowseTabStore.saveAll(
            listOf(
                BrowseTabStore.jmHomeTab().copy(name = BrowseTabStore.LEGACY_JM_HOME_NAME),
                BrowseTabStore.jmAllHotTab().copy(name = "JM全部热门"),
            ),
        )

        val migrated = BrowseTabStore.loadAll().associate { it.id to it.name }
        assertEquals(BrowseTabStore.JM_HOME_NAME, migrated[BrowseTabStore.ID_JM_HOME])
        assertEquals(BrowseTabStore.JM_ALL_HOT_NAME, migrated[JmBrowsePolicy.ALL_HOT_TAB_ID])

        BrowseTabStore.saveAll(
            listOf(
                BrowseTabStore.jmHomeTab().copy(name = "我自定义的首页"),
                BrowseTabStore.jmAllHotTab().copy(name = "我的热门"),
            ),
        )
        val customized = BrowseTabStore.loadAll().associate { it.id to it.name }
        assertEquals("我自定义的首页", customized[BrowseTabStore.ID_JM_HOME])
        assertEquals("我的热门", customized[JmBrowsePolicy.ALL_HOT_TAB_ID])
    }

    @Test
    fun `default shared tabs initialize Manwa then Picacg then JM and reset restores that order`() {
        installMemoryPreferences()
        val loaded = BrowseTabStore.loadAll()
        assertEquals(ContentPlatform.MANWA, loaded.first().platformId)
        assertEquals(listOf(ContentPlatform.MANWA, ContentPlatform.PICACG, ContentPlatform.JM), loaded.map { it.platformId }.distinctBy { it })
        assertEquals(2, loaded.count { it.platformId == ContentPlatform.JM })
        assertEquals(BrowseTabStore.ID_PICACG_HOME, loaded.first { it.platformId == ContentPlatform.PICACG }.id)

        val restored = BrowseTabStore.resetAll()
        assertEquals(loaded.map { BrowseTabPolicy.identity(it) }, restored.map { BrowseTabPolicy.identity(it) })
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
            listOf(manwa.id, BrowseTabStore.ID_PICACG_HOME, jm.id),
            withPicacg.map { it.id },
        )
        assertEquals(existing, BrowseTabPolicy.addMissingPicacgHome(withPicacg, BrowseTabStore.picacgHomeTab()).filterNot { it.id == BrowseTabStore.ID_PICACG_HOME })

        BrowseTabStore.saveAll(existing, selectedTabIndex = 1)
        val migrated = BrowseTabStore.loadAll()
        assertEquals(BrowseTabPolicy.identity(BrowseTabStore.picacgHomeTab()), BrowseTabPolicy.identity(migrated[1]))
        assertEquals(BrowseTabPolicy.identity(jm), BrowseTabPolicy.identity(migrated[BrowseTabStore.loadSelectedTab()]))

        val reordered = BrowseTabPolicy.reorder(migrated, migrated.reversed(), BrowseTabStore.loadSelectedTab())!!
        assertEquals(jm.id, reordered.tabs[reordered.selectedIndex].id)
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
            listOf(draft[0], draft[1], BrowseTabStore.picacgHomeTab(), draft[2], draft[3]).map { BrowseTabPolicy.identity(it) },
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
            listOf(current[0], BrowseTabStore.picacgHomeTab(), current[1], current[2]).map { BrowseTabPolicy.identity(it) },
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
