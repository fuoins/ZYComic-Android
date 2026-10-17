package com.zycomic.app.ui.browse

import android.content.SharedPreferences
import com.zycomic.app.platform.ContentPlatform
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class BrowseTabPersistenceTest {
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
