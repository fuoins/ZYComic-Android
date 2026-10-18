package com.zycomic.app.ui.browse

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmFilterSnapshotTest {

    @Test
    fun `custom JM tab JSON roundtrip keeps its category and order independent of Manwa snapshot`() {
        val customJmTabFilters = JmBrowsePolicy.Filters(
            mode = JmBrowsePolicy.MODE_CATEGORY,
            query = "",
            order = "mv_w",
            category = "doujin",
            weekId = "2025-06",
            weekType = "hanman",
        )

        val persistedJson = JmFilterSnapshot.fromFilters(customJmTabFilters).toJson().toString()
        val reloaded = JmFilterSnapshot.fromJson(JSONObject(persistedJson)).toFilters()
        val reloadedJson = JSONObject(persistedJson)

        assertEquals(customJmTabFilters, reloaded)
        assertEquals("doujin", reloaded.category)
        assertEquals("mv_w", reloaded.order)
        assertTrue(reloadedJson.has("category"))
        assertTrue(reloadedJson.has("order"))
        assertFalse(reloadedJson.has("filter"))
        assertFalse(reloadedJson.has("gender"))
        assertFalse(reloadedJson.has("tags"))
    }

    @Test
    fun `serialization defaults survive restart independently of weekly must read`() {
        val filters = JmBrowsePolicy.Filters(
            mode = JmBrowsePolicy.MODE_SERIALIZATION,
            serializationType = "hanman",
            serializationDate = 0,
        )
        assertEquals(filters, JmFilterSnapshot.fromJson(JmFilterSnapshot.fromFilters(filters).toJson()).toFilters())
    }

    @Test
    fun `old snapshots get manga all and todays weekday defaults`() {
        val filters = JmFilterSnapshot.fromJson(JSONObject("{}")).toFilters()
        assertEquals("manga", filters.weekType)
        assertEquals("all", filters.serializationType)
        assertTrue(filters.serializationDate in 1..7)
    }

    @Test
    fun `persisted exact legacy translation group slug is canonicalized but unknown value is retained`() {
        val legacy = JmFilterSnapshot.fromJson(JSONObject("""{"category":"禁漫汉化组","promoteId":"998"}""")).toFilters()
        assertEquals("禁漫漢化組", legacy.category)

        val unknown = JmFilterSnapshot.fromJson(JSONObject("""{"category":"jm_hanhua","promoteId":"998"}""")).toFilters()
        assertEquals("jm_hanhua", unknown.category)
        val rejectedNearMiss = JmFilterSnapshot.fromJson(JSONObject("""{"category":"禁漫漢化组","promoteId":"998"}""")).toFilters()
        assertEquals("禁漫漢化组", rejectedNearMiss.category)
        val numeric = JmFilterSnapshot.fromJson(JSONObject("""{"category":"998","promoteId":"998"}""")).toFilters()
        assertEquals("998", numeric.category)
    }

    @Test
    fun `homepage section id and title survive restart`() {
        val filters = JmBrowsePolicy.Filters(
            mode = JmBrowsePolicy.MODE_PROMOTE_LIST,
            promoteId = "29",
            promoteTitle = "C108&推薦本本",
        )
        assertEquals(filters, JmFilterSnapshot.fromJson(JmFilterSnapshot.fromFilters(filters).toJson()).toFilters())
    }

    @Test
    fun `old snapshot does not invent a homepage section`() {
        val filters = JmFilterSnapshot.fromJson(JSONObject("{}")).toFilters()
        assertEquals("", filters.promoteId)
        assertEquals("", filters.promoteTitle)
    }
}
