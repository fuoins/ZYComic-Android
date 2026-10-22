package com.zycomic.app.reader

/** Directory positions are zero-based; an incomplete prefix is never a chapter boundary. */
object ReaderDirectoryPolicy {
    fun resolveTarget(target: Int, fetched: Int, complete: Boolean): Int? = when {
        fetched <= 0 -> null
        target < fetched -> target.coerceAtLeast(0)
        complete -> fetched - 1
        else -> null
    }

    fun totalPages(fetched: Int, complete: Boolean): Int = if (complete) fetched else -1

    fun isLastPage(index: Int, fetched: Int, complete: Boolean): Boolean = complete && fetched > 0 && index == fetched - 1
}

/** Scoped to one fresh loader, never a persisted/full-directory cache. */
class ReaderPageSnapshots<T>(private val sameEntry: (T, T) -> Boolean) {
    private var previous = emptyList<T>()

    fun accept(fresh: List<T>): List<T> = fresh.mapIndexed { index, item ->
        previous.getOrNull(index)?.takeIf { sameEntry(it, item) } ?: item
    }.also { previous = it }
}
