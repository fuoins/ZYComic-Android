package com.zycomic.app.ui.picacg

/** A successful GET only unlocks writes after an explicit refresh of the affected thread/page. */
internal data class PicacgCommentWriteLocation(val threadId: String?, val page: Int, val refresh: Int)

internal object PicacgCommentWritePolicy {
    fun resolves(blockedAt: PicacgCommentWriteLocation?, current: PicacgCommentWriteLocation): Boolean =
        blockedAt != null && blockedAt.threadId == current.threadId && blockedAt.page == current.page && current.refresh > blockedAt.refresh
}
