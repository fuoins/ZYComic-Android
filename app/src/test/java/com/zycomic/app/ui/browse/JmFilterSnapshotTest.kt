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
}
