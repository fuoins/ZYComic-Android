package com.zycomic.app.data.repository

/** Presentation-only filtering: never deletes remote records or touches local reading progress. */
internal object JmHistoryDisplayPolicy {
    private val chapterSuffix = Regex("第\\s*[0-9０-９一二三四五六七八九十百千零〇两]+\\s*[话話章回节節]\\s*$")

    fun isStandaloneChapter(title: String): Boolean = chapterSuffix.containsMatchIn(title.trim())

    /** Count raw IDs, including hidden rows, so totals and repeated-page detection stay server-based. */
    suspend fun loadVisiblePage(
        previousPage: Int,
        previouslyLoadedIds: Set<String>,
        fetch: suspend (Int) -> JmOnlineLibraryPage,
    ): JmHistoryVisiblePage {
        val loadedIds = previouslyLoadedIds.toMutableSet()
        val visible = mutableListOf<JmOnlineLibraryItem>()
        var page = previousPage
        var total: Int
        var hasMore: Boolean
        do {
            val result = fetch(page + 1)
            page++
            total = result.total
            hasMore = JmOnlineLibraryPolicy.hasMore(total, loadedIds, result.items.map { it.id })
            result.items.forEach { item ->
                if (loadedIds.add(item.id) && !isStandaloneChapter(item.name)) visible += item
            }
        } while (hasMore && visible.size < JmOnlineLibraryPolicy.PAGE_SIZE)
        return JmHistoryVisiblePage(visible, loadedIds, page, total, hasMore)
    }
}

internal data class JmHistoryVisiblePage(
    val items: List<JmOnlineLibraryItem>,
    val loadedIds: Set<String>,
    val lastPage: Int,
    val total: Int,
    val hasMore: Boolean,
)
