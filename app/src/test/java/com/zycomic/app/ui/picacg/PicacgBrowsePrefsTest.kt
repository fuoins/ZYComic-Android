package com.zycomic.app.ui.picacg

import com.zycomic.app.picacg.PicacgCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgBrowsePrefsTest {
    @Test
    fun `browse filter normalization preserves API fields and restricts sort codes`() {
        val normalized = PicacgBrowseFilter(
            category = " 动作 ",
            tag = " 冒险 ",
            author = " 作者 ",
            creatorId = " 123 ",
            chineseTeam = " 小组 ",
            sort = "unsupported",
        ).normalized()

        assertEquals(PicacgBrowseFilter("动作", "冒险", "作者", "123", "小组", "dd"), normalized)
        assertEquals(setOf("dd", "da", "ld", "vd"), PicacgBrowseFilter.SORT_CODES)
    }

    @Test
    fun `leaderboard presets cover requested windows using repository wire values`() {
        assertEquals(
            listOf("24小时" to "H24", "7天" to "D7", "30天" to "D30"),
            PicacgBrowsePolicy.leaderboards.map { it.name to it.timeframe },
        )
    }

    @Test
    fun `static Picacg themes contain the configured forty unique titles`() {
        assertEquals(40, PicacgThemes.titles.size)
        assertEquals(40, PicacgThemes.titles.toSet().size)
        assertEquals("大家都在看", PicacgThemes.titles.first())
        assertEquals("重口地帶", PicacgThemes.titles.last())
        assertEquals(setOf("dd", "da", "ld", "vd"), PicacgBrowseFilter.SORT_CODES)
    }

    @Test
    fun `nonactive web categories are filtered and duplicate titles collapse`() {
        val visible = PicacgBrowsePolicy.visibleCategories(
            listOf(
                PicacgCategory("a", "动作"),
                PicacgCategory("b", "动作"),
                PicacgCategory("c", "成人", active = false),
                PicacgCategory("d", "网页", isWeb = true),
            ),
        )

        assertEquals(listOf(PicacgCategory("a", "动作")), visible)
    }

    @Test
    fun `custom presets save locally with unique names and can be deleted`() {
        val original = PicacgBrowsePreset("one", "精选", PicacgBrowseFilter(tag = "热血"))
        val saved = PicacgBrowsePrefsPolicy.save(listOf(original), "日更", PicacgBrowseFilter(sort = "ld"))

        assertEquals(2, saved!!.size)
        assertNull(PicacgBrowsePrefsPolicy.save(saved, " 精选 ", PicacgBrowseFilter()))
        assertNull(PicacgBrowsePrefsPolicy.save(saved, "  ", PicacgBrowseFilter()))
        assertEquals(listOf(original), PicacgBrowsePrefsPolicy.delete(saved, saved.last().id))
    }

    @Test
    fun `preset sanitization drops invalid and duplicate entries but keeps first definition`() {
        val first = PicacgBrowsePreset("a", "Favorites", PicacgBrowseFilter(tag = "精选"))
        val sanitized = PicacgBrowsePrefsPolicy.sanitize(
            listOf(
                first,
                first.copy(name = "overwritten"),
                first.copy(id = "b", name = "favorites"),
                first.copy(id = "c", name = " "),
            ),
        )

        assertEquals(listOf(first), sanitized)
        assertFalse(sanitized.any { it.id == "b" || it.id == "c" })
        assertTrue(sanitized.single().filter.tag == "精选")
    }
}
