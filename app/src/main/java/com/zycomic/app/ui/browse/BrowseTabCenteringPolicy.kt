package com.zycomic.app.ui.browse

/** LazyListState clamps the remaining movement at either end of the actual content. */
internal object BrowseTabCenteringPolicy {
    fun scrollDelta(
        itemOffset: Int,
        itemSize: Int,
        viewportStart: Int,
        viewportEnd: Int,
        canScrollBackward: Boolean,
        canScrollForward: Boolean,
    ): Float {
        val delta = itemOffset + itemSize / 2f - (viewportStart + viewportEnd) / 2f
        return when {
            delta < 0 && !canScrollBackward -> 0f
            delta > 0 && !canScrollForward -> 0f
            else -> delta
        }
    }
}
