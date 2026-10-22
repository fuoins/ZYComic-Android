package com.zycomic.app.reader

/** Converts the reader's database URL, not its local row ID, to the app's detail route. */
internal object ReaderDetailRoute {
    const val ACTION = "com.zycomic.app.action.OPEN_READER_DETAIL"
    const val EXTRA_BOOK_ID = "reader_detail_book_id"

    fun bookId(sourceId: Long, mangaUrl: String): String? {
        val (path, prefix) = when (sourceId) {
            ManwaSource.id -> "manga" to ""
            JmSource.id -> "album" to "jm:"
            PicacgSource.id -> "comic" to "picacg:"
            else -> return null
        }
        val id = Regex("^/$path/([A-Za-z0-9_-]+)/?(?:[?#].*)?$")
            .matchEntire(mangaUrl)?.groupValues?.get(1) ?: return null
        return prefix + id
    }
}
