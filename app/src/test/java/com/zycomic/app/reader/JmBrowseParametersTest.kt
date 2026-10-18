package com.zycomic.app.reader

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JmBrowseParametersTest {
    @Test
    fun `translation group accepts only its evidence confirmed exact slug`() {
        val route = JmBrowseParameters.promoteRoute("not_in_category_id", "998", "禁漫漢化組", 1, 4)
        assertEquals("/categories/filter", route.path)
        assertEquals("禁漫漢化組", route.params["c"])
        val legacyRoute = JmBrowseParameters.promoteRoute("not_in_category_id", "998", "禁漫汉化组", 1, 4)
        assertEquals(mapOf("c" to "禁漫漢化組", "o" to "mr", "page" to "1"), legacyRoute.params)
        assertEquals(null, JmBrowseParameters.confirmedPromoteCategory("禁漫漢化组"))
        listOf(" 禁漫漢化組", "禁漫漢化組 ", "禁漫 汉化組", "禁漫漢化组", "998", "unknown").forEach { slug ->
            assertEquals(null, JmBrowseParameters.confirmedPromoteCategory(slug))
            assertEquals(null, JmBrowseParameters.promoteRoute("not_in_category_id", "998", slug, 1, 4).path)
        }
        listOf("禁漫汉化组 ", "禁漫漢化组", "998", "unknown").forEach { filterValue ->
            assertEquals(null, JmBrowseParameters.promoteRoute("not_in_category_id", "998", "", 1, 4, filterValue).path)
        }
    }

    @Test
    fun `actual 998 promote section parses and routes with its confirmed slug`() {
        val section = JmSource.parsePromoteSections(
            JSONArray(
                """[{"id":998,"title":"禁漫漢化組","type":"not_in_category_id","slug":"禁漫汉化组","filter_val":null,"content":[]}]""",
            ),
        ).single()

        assertEquals("998", section.id)
        assertEquals("禁漫汉化组", section.slug)
        val route = JmBrowseParameters.promoteRoute(
            type = section.type,
            id = section.id,
            slug = section.slug,
            page = 1,
            today = 4,
            filterValue = section.filterValue,
        )
        assertEquals("/categories/filter", route.path)
        assertEquals(mapOf("c" to "禁漫漢化組", "o" to "mr", "page" to "1"), route.params)
    }

    @Test
    fun `section title and numeric id are never category route fallbacks`() {
        val section = JmSource.parsePromoteSections(
            JSONArray(
                """[{"id":998,"title":"禁漫漢化組","type":"not_in_category_id","slug":"","filter_val":"998","content":[]}]""",
            ),
        ).single()
        val route = JmBrowseParameters.promoteRoute(
            section.type,
            section.id,
            section.slug,
            1,
            4,
            section.filterValue,
        )
        assertEquals(null, route.path)
    }

    @Test
    fun `confirmed filter value can supply a category only when slug is absent`() {
        val route = JmBrowseParameters.promoteRoute("category_id", "999", "", 1, 4, "hanman")
        assertEquals(mapOf("c" to "hanman", "o" to "mr", "page" to "1"), route.params)
    }

    @Test
    fun `numeric blank and unknown category slugs never generate a request route`() {
        listOf("998", "5", "0", "unknown", "", "jm_hanhua", "禁漫漢化组").forEach { slug ->
            val route = JmBrowseParameters.promoteRoute("not_in_category_id", "998", slug, 1, 4)
            assertEquals(null, route.path)
            assertEquals(emptyMap<String, String>(), route.params)
        }
    }

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
