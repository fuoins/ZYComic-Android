package com.zycomic.app.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JmBrowseParametersTest {
    @Test
    fun `week filter starts at page one and never includes category order`() {
        assertEquals(
            mapOf("id" to "262", "type" to "manga", "page" to "1"),
            JmBrowseParameters.weekFilter("262", "manga", 0),
        )
        assertEquals("2", JmBrowseParameters.weekFilter("262", "hanman", 2)["page"])
    }

    @Test
    fun `serialization request contains only type date and one based page`() {
        assertEquals(
            mapOf("type" to "all", "date" to "7", "page" to "1"),
            JmBrowseParameters.serialization("all", 7, 0),
        )
        assertEquals("manga", JmBrowseParameters.serialization("manga", 0, 2)["type"])
        assertEquals("2", JmBrowseParameters.serialization("hanman", 3, 2)["page"])
    }

    @Test
    fun `promote list is zero based and section routing uses API metadata`() {
        assertEquals(mapOf("id" to "29", "page" to "0"), JmBrowseParameters.promoteList("29", -1))
        assertEquals(mapOf("id" to "30", "page" to "3"), JmBrowseParameters.promoteList("30", 3))

        val promote = JmBrowseParameters.promoteRoute("promote", "29", "", 1, 4)
        assertEquals("/promote_list", promote.path)
        assertEquals(mapOf("id" to "29", "page" to "0"), promote.params)
        assertEquals(27, promote.pageSize)

        val category = JmBrowseParameters.promoteRoute("category_id", "999", "hanman", 2, 4)
        assertEquals("/categories/filter", category.path)
        assertEquals(mapOf("c" to "hanman", "o" to "mr", "page" to "2"), category.params)
        val translatedGroup = JmBrowseParameters.promoteRoute("not_in_category_id", "998", "禁漫漢化組", 1, 4)
        assertEquals("禁漫漢化組", translatedGroup.params["c"])
        val weeklyUpdate = JmBrowseParameters.promoteRoute("promote", "26", "", 2, 4)
        assertEquals("/serialization", weeklyUpdate.path)
        assertEquals(mapOf("type" to "all", "date" to "4", "page" to "2"), weeklyUpdate.params)
        assertEquals(40, weeklyUpdate.pageSize)
        assertEquals("1", JmBrowseParameters.promoteRoute("promote", "29", "", 2, 4).params["page"])
        assertEquals(null, JmBrowseParameters.promoteRoute("library", "1001", "creator", 1, 4).path)
        assertEquals(null, JmBrowseParameters.promoteRoute("novels", "1002", "novels", 1, 4).path)
    }

    @Test
    fun `creator and novel previews are never decoded as manga`() {
        assertEquals(true, JmBrowseParameters.hasComicPreview("promote"))
        assertEquals(true, JmBrowseParameters.hasComicPreview("category_id"))
        assertEquals(true, JmBrowseParameters.hasComicPreview("not_in_category_id"))
        assertEquals(false, JmBrowseParameters.hasComicPreview("library"))
        assertEquals(false, JmBrowseParameters.hasComicPreview("novels"))
        assertEquals(false, JmBrowseParameters.hasComicPreview("unknown"))
    }
}
