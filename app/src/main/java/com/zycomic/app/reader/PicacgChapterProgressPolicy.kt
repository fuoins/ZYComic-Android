package com.zycomic.app.reader

internal data class PicacgSavedChapterProgress(
    val id: Long = -1L,
    val read: Boolean = false,
    val lastPageRead: Long = 0L,
)

/** Picacg chapter identity is comic ID plus episode order, not the episode's wire ID. */
internal object PicacgChapterProgressPolicy {
    fun chapterUrl(comicId: String, order: Int): String {
        require(comicId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Picacg comic identifier" }
        require(order > 0) { "Picacg chapter order must be positive" }
        return "/chapter/$comicId/$order"
    }

    fun preserve(url: String, existing: Map<String, PicacgSavedChapterProgress>): PicacgSavedChapterProgress =
        existing[url]?.let { it.copy(lastPageRead = it.lastPageRead.coerceAtLeast(0L)) } ?: PicacgSavedChapterProgress()
}
