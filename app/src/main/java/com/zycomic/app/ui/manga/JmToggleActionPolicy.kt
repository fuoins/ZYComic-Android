package com.zycomic.app.ui.manga

/** A toggle is safe only after the previous result has been read back and the user is authenticated. */
internal object JmToggleActionPolicy {
    fun canStart(busy: Boolean, authenticated: Boolean): Boolean = authenticated && !busy

    /** Only call after /like has returned a confirmed successful response. Unknown stays unknown. */
    fun likedAfterConfirmedSuccess(before: Boolean?): Boolean? = before?.not()

    /** An explicit detail state wins; a missing field must not erase a confirmed write result. */
    fun likedAfterRefresh(refreshed: Boolean?, confirmed: Boolean?): Boolean? = refreshed ?: confirmed
}
