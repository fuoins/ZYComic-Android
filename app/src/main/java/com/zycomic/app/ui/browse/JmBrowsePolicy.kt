package com.zycomic.app.ui.browse

import com.zycomic.app.data.dto.Manga

/** JM-only browse defaults and pagination rules; kept independent from Manwa state. */
internal object JmBrowsePolicy {
    const val MODE_RECOMMENDATIONS = 0
    const val MODE_CATEGORY = 1
    const val MODE_WEEK = 2
    const val MODE_SEARCH = 3
    const val ALL_HOT_TAB_ID = "jm_all_hot"
    const val ALL_HOT_ORDER = "tf"

    data class Filters(
        val mode: Int = MODE_RECOMMENDATIONS,
        val query: String = "",
        val order: String = "",
        val category: String = "",
        val weekId: String = "",
        val weekType: String = "",
    )

    fun initialFilters(tabId: String): Filters =
        if (tabId == ALL_HOT_TAB_ID) {
            Filters(mode = MODE_CATEGORY, order = ALL_HOT_ORDER)
        } else {
            Filters()
        }

    fun categoryOptions(source: List<Pair<String, String>>): List<Pair<String, String>> =
        source.map { (label, value) ->
            (
                when (value) {
                    "" -> "最新A漫（不传分类）"
                    "doujin" -> "同人"
                    "single" -> "单本"
                    "short" -> "短篇"
                    "another" -> "其他类"
                    "hanman" -> "韩漫"
                    "meiman" -> "美漫"
                    "another_cosplay" -> "Cosplay"
                    "3D" -> "3D"
                    "禁漫漢化組" -> "禁漫漢化組"
                    else -> label
                }
                ) to value
        }

    fun orderOptions(source: List<Pair<String, String>>): List<Pair<String, String>> =
        source.map { (label, value) ->
            (
                when (value) {
                    "mr" -> "最新"
                    "mv" -> "总排行"
                    "mv_m" -> "月排行"
                    "mv_w" -> "周排行"
                    "mv_t" -> "日排行"
                    "mp" -> "最多图片"
                    "tf" -> "最多喜欢"
                    else -> label
                }
                ) to value
        }

    fun mergeDistinct(existing: List<Manga>, incoming: List<Manga>, reset: Boolean): List<Manga> =
        (if (reset) incoming else existing + incoming).distinctBy { it.id }

    /** A repeated page-first ID or an empty page is the only reliable category exhaustion signal. */
    fun shouldStopPaging(incoming: List<Manga>, previousFirstId: String?): Boolean =
        incoming.isEmpty() || (previousFirstId != null && incoming.firstOrNull()?.id == previousFirstId)
}
