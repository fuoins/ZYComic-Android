package com.zycomic.app.reader

internal data class JmPromoteRoute(
    val path: String?,
    val params: Map<String, String> = emptyMap(),
    val pageSize: Int = 0,
    val zeroBasedPage: Boolean = false,
)

/** Exact query contracts for JM list endpoints that do not accept the category order parameter. */
internal object JmBrowseParameters {
    const val PROMOTE_LIST_PAGE_SIZE = 27

    // JMComic 首页板块与更多相关 API 实测报告 §2/§3 confirms this exact Chinese slug.
    const val TRANSLATION_GROUP_SLUG = "禁漫漢化組"
    private val confirmedCategorySlugs = setOf(
        "doujin", "single", "short", "another", "hanman", "meiman", "another_cosplay", "3D",
        TRANSLATION_GROUP_SLUG,
    )

    fun confirmedPromoteCategory(slug: String): String? {
        val value = slug.trim()
        return value.takeIf { it in confirmedCategorySlugs }
    }

    fun hasComicPreview(type: String): Boolean = type == "promote" || type.contains("category_id")

    fun promoteList(id: String, page: Int): Map<String, String> = mapOf(
        "id" to id,
        "page" to page.coerceAtLeast(0).toString(),
    )

    /** Routes a /promote block by API metadata, never by its display title. */
    fun promoteRoute(type: String, id: String, slug: String, page: Int, today: Int): JmPromoteRoute = when {
        id == "26" -> JmPromoteRoute(
            path = "/serialization",
            params = serialization("all", today.coerceIn(1, 7), page.coerceAtLeast(1)),
            pageSize = 40,
        )
        type == "promote" -> JmPromoteRoute(
            path = "/promote_list",
            params = promoteList(id, page - 1),
            pageSize = PROMOTE_LIST_PAGE_SIZE,
            zeroBasedPage = true,
        )
        type.contains("category_id") && confirmedPromoteCategory(slug) != null -> JmPromoteRoute(
            path = "/categories/filter",
            params = mapOf("c" to confirmedPromoteCategory(slug)!!, "o" to "mr", "page" to page.coerceAtLeast(1).toString()),
            pageSize = 80,
        )
        type == "library" || type == "novels" -> JmPromoteRoute(path = null)
        else -> JmPromoteRoute(path = null)
    }

    fun weekFilter(id: String, type: String, page: Int): Map<String, String> = mapOf(
        "id" to id,
        "type" to type,
        "page" to page.coerceAtLeast(1).toString(),
    )

    fun serialization(type: String, date: Int, page: Int): Map<String, String> = mapOf(
        "type" to type,
        "date" to date.toString(),
        "page" to page.coerceAtLeast(1).toString(),
    )
}
