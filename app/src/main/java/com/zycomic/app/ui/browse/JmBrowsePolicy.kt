package com.zycomic.app.ui.browse

import com.zycomic.app.data.dto.Manga
import com.zycomic.app.reader.JmSource

/** JM-only browse defaults and pagination rules; kept independent from Manwa state. */
internal object JmBrowsePolicy {
    const val MODE_RECOMMENDATIONS = 0
    const val MODE_CATEGORY = 1
    const val MODE_WEEK = 2
    const val MODE_SEARCH = 3
    const val MODE_SERIALIZATION = 4
    const val MODE_PROMOTE_LIST = 5
    const val MODE_UNAVAILABLE_SECTION = 6
    const val ALL_HOT_TAB_ID = "jm_all_hot"
    const val ALL_HOT_ORDER = "tf"
    const val LATEST_A_CATEGORY = "__jm_latest_a__"

    data class Filters(
        val mode: Int = MODE_RECOMMENDATIONS,
        val query: String = "",
        val order: String = "",
        val category: String = "",
        val weekId: String = "",
        val weekType: String = "manga",
        val serializationType: String = "all",
        val serializationDate: Int = today(),
        val promoteId: String = "",
        val promoteTitle: String = "",
    )

    fun isVisibleHomeSection(id: String, type: String, includeSerialization: Boolean = false): Boolean =
        (includeSerialization || id != "26") && type !in setOf("library", "novels") && id !in setOf("1001", "1002")

    /**
     * Known comic sections are available before /promote finishes loading; the live catalogue
     * remains authoritative for metadata and may add further verified sections.
     */
    fun homeSectionOptions(source: List<JmSource.PromoteSection>): List<JmSource.PromoteSection> {
        val known = listOf(
            JmSource.PromoteSection("29", "C108&推薦本本", "promote", "", "29", emptyList()),
            JmSource.PromoteSection("30", "禁漫去碼&全彩化", "promote", "", "30", emptyList()),
            JmSource.PromoteSection("998", "禁漫漢化組", "not_in_category_id", "禁漫漢化組", "", emptyList()),
            JmSource.PromoteSection("999", "韓漫更新", "category_id", "hanman", "5", emptyList()),
            JmSource.PromoteSection("1000", "其他更新", "category_id", "another", "4", emptyList()),
        )
        val merged = linkedMapOf<String, JmSource.PromoteSection>()
        known.forEach { merged[it.id] = it }
        source.forEach { merged[it.id] = it }
        return merged.values.filter { isVisibleHomeSection(it.id, it.type) }
    }

    fun isSelectedHomeSection(mode: Int, selectedId: String, sectionId: String): Boolean =
        mode in setOf(MODE_CATEGORY, MODE_PROMOTE_LIST, MODE_UNAVAILABLE_SECTION) && selectedId == sectionId

    fun today(): Int = ((java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7) + 1

    fun isCustomCategoryTab(tabId: String): Boolean = tabId.startsWith("custom_jm_")

    fun showsModePicker(tabId: String): Boolean = tabId != ALL_HOT_TAB_ID && !isCustomCategoryTab(tabId)

    fun showsCategoryPicker(tabId: String): Boolean = true

    /** Home defaults to weekly; other destinations remain selectable for this session. */
    fun defaultFilters(tabId: String, stored: Filters?): Filters =
        if (tabId == "jm_recommendations") initialFilters(tabId) else effectiveFilters(tabId, stored ?: initialFilters(tabId))

    fun customCategoryFilters(filters: Filters = Filters()): Filters = Filters(
        mode = MODE_CATEGORY,
        category = filters.category,
        order = filters.order.ifBlank { ALL_HOT_ORDER },
    )

    fun jmHomeModes(): List<Pair<String, Int>> = listOf(
        "每周必看" to MODE_WEEK,
        "每周连载更新" to MODE_SERIALIZATION,
    )

    fun effectiveFilters(tabId: String, filters: Filters): Filters =
        if (tabId == ALL_HOT_TAB_ID || isCustomCategoryTab(tabId)) {
            filters.copy(
                mode = MODE_CATEGORY,
                query = "",
                order = filters.order.ifBlank { ALL_HOT_ORDER },
                promoteId = "",
                promoteTitle = "",
            )
        } else if (
            tabId == "jm_recommendations" &&
            (
                filters.mode !in setOf(
                    MODE_SEARCH,
                    MODE_WEEK,
                    MODE_SERIALIZATION,
                    MODE_PROMOTE_LIST,
                    MODE_UNAVAILABLE_SECTION,
                ) && !(filters.mode == MODE_CATEGORY && filters.promoteId.isNotBlank())
                )
        ) {
            filters.copy(mode = MODE_WEEK, query = "")
        } else {
            if (filters.mode == MODE_CATEGORY && filters.order.isBlank()) filters.copy(order = "mr") else filters
        }

    fun initialFilters(tabId: String): Filters =
        if (tabId == ALL_HOT_TAB_ID || isCustomCategoryTab(tabId)) {
            Filters(mode = MODE_CATEGORY, order = ALL_HOT_ORDER)
        } else if (tabId == "jm_recommendations") {
            Filters(mode = MODE_WEEK)
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
