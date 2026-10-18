package com.zycomic.app.ui.browse

import com.zycomic.app.data.dto.Manga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmBrowsePolicyTest {

    @Test
    fun `home hides bookshelf novels and duplicate serialization entry`() {
        assertFalse(JmBrowsePolicy.isVisibleHomeSection("1001", "library"))
        assertFalse(JmBrowsePolicy.isVisibleHomeSection("1002", "novels"))
        assertFalse(JmBrowsePolicy.isVisibleHomeSection("26", "promote"))
        assertTrue(JmBrowsePolicy.isVisibleHomeSection("26", "promote", includeSerialization = true))
        assertTrue(JmBrowsePolicy.isVisibleHomeSection("998", "not_in_category_id"))
    }

    @Test
    fun `weekly selection never also highlights the previous primary section`() {
        assertFalse(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_WEEK, "998", "998"))
        assertFalse(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_SERIALIZATION, "998", "998"))
        assertTrue(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_CATEGORY, "998", "998"))
        assertFalse(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_CATEGORY, "999", "998"))
    }

    @Test
    fun `all hot defaults to all categories and most liked order`() {
        assertEquals(
            JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, order = "tf", category = ""),
            JmBrowsePolicy.initialFilters(JmBrowsePolicy.ALL_HOT_TAB_ID),
        )
        assertEquals(JmBrowsePolicy.MODE_RECOMMENDATIONS, JmBrowsePolicy.initialFilters("jm_recommendations").mode)
    }

    @Test
    fun `JM home exposes weekly destinations and normalizes unsupported old modes`() {
        assertEquals(
            listOf("每周必看" to JmBrowsePolicy.MODE_WEEK, "每周连载更新" to JmBrowsePolicy.MODE_SERIALIZATION),
            JmBrowsePolicy.jmHomeModes(),
        )
        assertEquals(
            JmBrowsePolicy.MODE_RECOMMENDATIONS,
            JmBrowsePolicy.effectiveFilters("jm_recommendations", JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY)).mode,
        )
        assertEquals(
            JmBrowsePolicy.MODE_WEEK,
            JmBrowsePolicy.effectiveFilters("jm_recommendations", JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_WEEK)).mode,
        )
        assertEquals(
            JmBrowsePolicy.MODE_PROMOTE_LIST,
            JmBrowsePolicy.effectiveFilters(
                "jm_recommendations",
                JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_PROMOTE_LIST, promoteId = "29"),
            ).mode,
        )
        assertEquals(
            JmBrowsePolicy.MODE_UNAVAILABLE_SECTION,
            JmBrowsePolicy.effectiveFilters(
                "jm_recommendations",
                JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_UNAVAILABLE_SECTION, promoteId = "1002"),
            ).mode,
        )
    }

    @Test
    fun `all hot tab exposes category sorting without a mode picker`() {
        assertFalse(JmBrowsePolicy.showsModePicker(JmBrowsePolicy.ALL_HOT_TAB_ID))
        assertTrue(JmBrowsePolicy.showsModePicker("jm_recommendations"))
        assertTrue(JmBrowsePolicy.showsModePicker("custom_jm_1"))
    }

    @Test
    fun `stale all hot mode is normalized without changing recommendations`() {
        val stale = JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_WEEK, query = "legacy", order = "mv")
        assertEquals(
            JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, order = "mv"),
            JmBrowsePolicy.effectiveFilters(JmBrowsePolicy.ALL_HOT_TAB_ID, stale),
        )
        assertEquals(stale, JmBrowsePolicy.effectiveFilters("jm_recommendations", stale))
        assertEquals(
            JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, order = JmBrowsePolicy.ALL_HOT_ORDER),
            JmBrowsePolicy.effectiveFilters(JmBrowsePolicy.ALL_HOT_TAB_ID, JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_RECOMMENDATIONS)),
        )
    }

    @Test
    fun `option labels follow requested JM category and order contract without changing values`() {
        val categories = listOf(
            "Latest" to "",
            "Doujin" to "doujin",
            "Single" to "single",
            "Short" to "short",
            "Other" to "another",
            "Korean" to "hanman",
            "American" to "meiman",
            "Cosplay" to "another_cosplay",
            "3D" to "3D",
            "Group" to "禁漫漢化組",
        )
        val orders = listOf("New" to "mr", "Total" to "mv", "Month" to "mv_m", "Week" to "mv_w", "Day" to "mv_t", "Pictures" to "mp", "Likes" to "tf")

        assertEquals(
            listOf(
                "最新A漫" to JmBrowsePolicy.LATEST_A_CATEGORY,
                "同人" to "doujin",
                "單本" to "single",
                "短篇" to "short",
                "其他類" to "another",
                "韓漫" to "hanman",
                "美漫" to "meiman",
                "Cosplay" to "another_cosplay",
                "3D" to "3D",
                "禁漫漢化組" to "禁漫漢化組",
            ),
            JmBrowsePolicy.categoryOptions(categories),
        )
        val selections = JmBrowsePolicy.categorySelections(categories)
        assertEquals(11, selections.size)
        assertEquals("全部分类" to "", selections.first())
        assertEquals(11, selections.map { it.second }.distinct().size)
        assertEquals("", JmBrowsePolicy.apiCategory(JmBrowsePolicy.LATEST_A_CATEGORY))
        assertEquals("doujin", JmBrowsePolicy.apiCategory("doujin"))
        assertEquals(
            listOf("最新" to "mr", "总排行" to "mv", "月排行" to "mv_m", "周排行" to "mv_w", "日排行" to "mv_t", "最多圖片" to "mp", "最多喜歡" to "tf"),
            JmBrowsePolicy.orderOptions(orders),
        )
    }

    @Test
    fun `repeated page first id and empty or duplicate additions stop pagination`() {
        val existing = listOf(Manga(id = "1"), Manga(id = "2"))
        val repeatedPage = listOf(Manga(id = "1"), Manga(id = "3"))
        assertTrue(JmBrowsePolicy.shouldStopPaging(repeatedPage, previousFirstId = "1"))
        assertTrue(JmBrowsePolicy.shouldStopPaging(emptyList(), previousFirstId = "1"))
        assertEquals(listOf("1", "2", "3"), JmBrowsePolicy.mergeDistinct(existing, repeatedPage, reset = false).map { it.id })
        assertFalse(JmBrowsePolicy.shouldStopPaging(listOf(Manga(id = "3")), previousFirstId = "1"))
    }
}
