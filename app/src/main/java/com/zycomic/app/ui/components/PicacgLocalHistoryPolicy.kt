package com.zycomic.app.ui.components

import tachiyomi.domain.history.model.HistoryWithRelations

internal object PicacgLocalHistoryPolicy {
    fun onlyPicacg(history: List<HistoryWithRelations>, sourceId: Long): List<HistoryWithRelations> =
        history.filter { it.coverData.sourceId == sourceId }
    fun comicId(url: String, sourceId: Long, picacgSourceId: Long): String? =
        if (sourceId == picacgSourceId) Regex("^/comic/([A-Za-z0-9_-]+)$").matchEntire(url)?.groupValues?.get(1) else null
}
