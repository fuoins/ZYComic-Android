package com.zycomic.app.ui.components

import tachiyomi.domain.history.model.HistoryWithRelations
import java.util.Locale

internal object PicacgLocalHistoryPolicy {
    fun onlyPicacg(history: List<HistoryWithRelations>, sourceId: Long): List<HistoryWithRelations> =
        history.filter { it.coverData.sourceId == sourceId }

    fun newestFirst(history: List<HistoryWithRelations>): List<HistoryWithRelations> =
        history.sortedWith(
            compareByDescending<HistoryWithRelations> { it.readAt?.time ?: Long.MIN_VALUE }
                .thenByDescending { it.id },
        )

    fun comicId(url: String, sourceId: Long, picacgSourceId: Long): String? =
        if (sourceId == picacgSourceId) Regex("^/comic/([A-Za-z0-9_-]+)$").matchEntire(url)?.groupValues?.get(1) else null

    fun matchesSearch(
        query: String,
        title: String,
        author: String,
        categories: List<String>,
        tags: List<String>,
    ): Boolean {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) return true
        return sequenceOf(title, author)
            .plus(categories.asSequence())
            .plus(tags.asSequence())
            .any { it.lowercase(Locale.ROOT).contains(normalizedQuery) }
    }

    fun allVisibleIds(visibleIds: List<Long>): Set<Long> = visibleIds.toSet()

    fun lastReadPageNumber(lastPageRead: Long): Long =
        lastPageRead.coerceIn(0L, Int.MAX_VALUE.toLong() - 1L) + 1L
}
