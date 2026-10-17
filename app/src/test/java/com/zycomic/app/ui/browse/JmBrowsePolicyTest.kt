package com.zycomic.app.ui.browse

import com.zycomic.app.data.dto.Manga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmBrowsePolicyTest {

    @Test
    fun `all hot defaults to all categories and most liked order`() {
        assertEquals(
            JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, order = "tf"),
            JmBrowsePolicy.initialFilters(JmBrowsePolicy.ALL_HOT_TAB_ID),
        )
        assertEquals(JmBrowsePolicy.MODE_RECOMMENDATIONS, JmBrowsePolicy.initialFilters("jm_recommendations").mode)
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
                "最新A漫（不传分类）" to "",
                "同人" to "doujin",
                "单本" to "single",
                "短篇" to "short",
                "其他类" to "another",
                "韩漫" to "hanman",
                "美漫" to "meiman",
                "Cosplay" to "another_cosplay",
                "3D" to "3D",
                "禁漫漢化組" to "禁漫漢化組",
            ),
            JmBrowsePolicy.categoryOptions(categories),
        )
        assertEquals(
            listOf("最新" to "mr", "总排行" to "mv", "月排行" to "mv_m", "周排行" to "mv_w", "日排行" to "mv_t", "最多图片" to "mp", "最多喜欢" to "tf"),
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
