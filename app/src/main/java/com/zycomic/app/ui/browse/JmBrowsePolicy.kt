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
    const val LATEST_A_CATEGORY = "__jm_latest_a__"

    data class Filters(
        val mode: Int = MODE_RECOMMENDATIONS,
        val query: String = "",
        val order: String = "",
        val category: String = "",
        val weekId: String = "",
        val weekType: String = "",
    )

    fun showsModePicker(tabId: String): Boolean = tabId != ALL_HOT_TAB_ID

    fun effectiveFilters(tabId: String, filters: Filters): Filters =
        if (tabId == ALL_HOT_TAB_ID) {
            filters.copy(
                mode = MODE_CATEGORY,
                query = "",
                order = filters.order.ifBlank { ALL_HOT_ORDER },
            )
        } else {
            filters
        }

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
                    "" -> "最新A漫"
                    "doujin" -> "同人"
                    "single" -> "單本"
                    "short" -> "短篇"
                    "another" -> "其他類"
                    "hanman" -> "韓漫"
                    "meiman" -> "美漫"
                    "another_cosplay" -> "Cosplay"
                    "3D" -> "3D"
                    "禁漫漢化組" -> "禁漫漢化組"
                    else -> label
                }
                ) to if (value.isEmpty()) LATEST_A_CATEGORY else value
        }

    fun categorySelections(source: List<Pair<String, String>>): List<Pair<String, String>> =
        listOf("全部分类" to "") + categoryOptions(source)

    /** The latest-A chip shares JM's empty API category with the all-category query. */
    fun apiCategory(category: String): String = if (category == LATEST_A_CATEGORY) "" else category

    fun orderOptions(source: List<Pair<String, String>>): List<Pair<String, String>> =
        source.map { (label, value) ->
            (
                when (value) {
                    "mr" -> "最新"
                    "mv" -> "总排行"
                    "mv_m" -> "月排行"
                    "mv_w" -> "周排行"
                    "mv_t" -> "日排行"
                    "mp" -> "最多圖片"
                    "tf" -> "最多喜歡"
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
