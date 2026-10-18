package com.zycomic.app.ui.browse

import com.zycomic.app.data.dto.Manga
import com.zycomic.app.reader.JmSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmBrowsePolicyTest {

    @Test
    fun `weekly default preserves existing home destinations and sections`() {
        assertEquals(
            listOf("每周必看" to JmBrowsePolicy.MODE_WEEK, "每周连载更新" to JmBrowsePolicy.MODE_SERIALIZATION),
            JmBrowsePolicy.jmHomeModes(),
        )
        assertEquals(JmBrowsePolicy.MODE_WEEK, JmBrowsePolicy.initialFilters("jm_recommendations").mode)
        assertEquals(
            JmBrowsePolicy.MODE_WEEK,
            JmBrowsePolicy.effectiveFilters("jm_recommendations", JmBrowsePolicy.Filters()).mode,
        )
        assertTrue(JmBrowsePolicy.isVisibleHomeSection("998", "not_in_category_id"))
        assertTrue(JmBrowsePolicy.isVisibleHomeSection("29", "promote"))
        assertTrue(JmBrowsePolicy.isVisibleHomeSection("26", "promote", includeSerialization = true))
        assertFalse(JmBrowsePolicy.isVisibleHomeSection("26", "promote"))
        assertFalse(JmBrowsePolicy.isVisibleHomeSection("1001", "library"))
        assertFalse(JmBrowsePolicy.isVisibleHomeSection("1002", "novels"))
    }

    @Test
    fun `verified comic sections are immediately available and live directory metadata wins`() {
        val immediate = JmBrowsePolicy.homeSectionOptions(emptyList())
        assertEquals(listOf("29", "30", "998", "999", "1000"), immediate.map { it.id })

        val live = JmSource.PromoteSection("29", "Updated C108 title", "promote", "", "29", emptyList())
        val dynamic = JmSource.PromoteSection("1200", "New comic block", "promote", "", "1200", emptyList())
        val library = JmSource.PromoteSection("1001", "书库", "library", "creator", "", emptyList())
        val novels = JmSource.PromoteSection("1002", "小说", "novels", "novels", "", emptyList())
        val merged = JmBrowsePolicy.homeSectionOptions(listOf(live, dynamic, library, novels))

        assertEquals(listOf("29", "30", "998", "999", "1000", "1200"), merged.map { it.id })
        assertEquals("Updated C108 title", merged.first().title)
        assertEquals("New comic block", merged.last().title)
        assertTrue(merged.all { it.type != "library" && it.type != "novels" })
    }

    @Test
    fun `weekly selection never also highlights the previous primary section`() {
        assertFalse(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_WEEK, "998", "998"))
        assertFalse(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_SERIALIZATION, "998", "998"))
        assertTrue(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_CATEGORY, "998", "998"))
        assertTrue(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_PROMOTE_LIST, "29", "29"))
        assertFalse(JmBrowsePolicy.isSelectedHomeSection(JmBrowsePolicy.MODE_CATEGORY, "999", "998"))
    }

    @Test
    fun `all hot defaults to all categories and most liked order`() {
        assertEquals(
            JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, order = "tf", category = ""),
            JmBrowsePolicy.initialFilters(JmBrowsePolicy.ALL_HOT_TAB_ID),
        )
        assertEquals(JmBrowsePolicy.MODE_WEEK, JmBrowsePolicy.initialFilters("jm_recommendations").mode)
    }

    @Test
    fun `JM home keeps weekly mode and legacy filters remain normalizable`() {
        assertEquals(
            JmBrowsePolicy.MODE_WEEK,
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
        assertFalse(JmBrowsePolicy.showsModePicker("custom_jm_1"))
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
    fun `weekly home defaults ignore stored modes but allow transient weekly browsing`() {
        val stored = JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_WEEK, weekId = "123")
        assertEquals(JmBrowsePolicy.initialFilters("jm_recommendations"), JmBrowsePolicy.defaultFilters("jm_recommendations", stored))
        assertEquals(stored, JmBrowsePolicy.effectiveFilters("jm_recommendations", stored))
    }

    @Test
    fun `home defaults to weekly while other tabs retain filters`() {
        val previousHome = JmBrowsePolicy.Filters(
            mode = JmBrowsePolicy.MODE_PROMOTE_LIST,
            category = "doujin",
            promoteId = "29",
            promoteTitle = "C108",
        )
        assertEquals(
            JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_WEEK),
            JmBrowsePolicy.defaultFilters("jm_recommendations", previousHome),
        )

        val otherTabFilters =
            JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_SERIALIZATION, serializationType = "hanman")
        assertEquals(
            otherTabFilters,
            JmBrowsePolicy.defaultFilters("other_jm_tab", otherTabFilters),
        )
    }

    @Test
    fun `custom JM tabs only select a category and default to all hot sorting`() {
        val custom = "custom_jm_123"
        val legacy = JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_WEEK, query = "ignored", category = "doujin", promoteId = "old", promoteTitle = "old")
        assertEquals(JmBrowsePolicy.MODE_CATEGORY, JmBrowsePolicy.effectiveFilters(custom, legacy).mode)
        assertEquals("", JmBrowsePolicy.effectiveFilters(custom, legacy).query)
        assertEquals("", JmBrowsePolicy.effectiveFilters(custom, legacy).promoteId)
        assertEquals("tf", JmBrowsePolicy.initialFilters(custom).order)
        assertFalse(JmBrowsePolicy.showsModePicker(custom))
        assertFalse(JmBrowsePolicy.showsCategoryPicker(custom))
        assertTrue(JmBrowsePolicy.showsCategoryPicker(JmBrowsePolicy.ALL_HOT_TAB_ID))
        assertEquals(
            JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, category = "doujin", order = "tf"),
            JmBrowsePolicy.customCategoryFilters(legacy.copy(order = "mr")),
        )
        assertEquals("doujin", JmBrowsePolicy.defaultFilters(custom, legacy).category)
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
