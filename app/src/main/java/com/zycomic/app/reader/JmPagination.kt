package com.zycomic.app.reader

/** Pure pagination rules for the JM endpoints; page numbers are one-based at this boundary. */
internal object JmPagination {
    const val ITEMS_PER_PAGE = 80
    const val MAX_LIST_PAGES = 125

    fun searchHasMore(page: Int, total: Int): Boolean =
        page.coerceAtLeast(1) < pageCount(total)

    fun categoryHasMore(page: Int, total: Int, firstId: String?, previousFirstId: String?): Boolean =
        page.coerceAtLeast(1) < pageCount(total).coerceAtMost(MAX_LIST_PAGES) &&
            firstId != null && firstId != previousFirstId

    fun cappedListHasMore(page: Int, itemCount: Int, firstId: String?, previousFirstId: String?): Boolean =
        page.coerceAtLeast(1) < MAX_LIST_PAGES && itemCount > 0 && firstId != previousFirstId

    private fun pageCount(total: Int): Int =
        if (total <= 0) 0 else (total - 1) / ITEMS_PER_PAGE + 1
}
