package com.zycomic.app.ui.manga

/** A toggle is safe only after the previous result has been read back and the user is authenticated. */
internal object JmToggleActionPolicy {
    fun canStart(busy: Boolean, authenticated: Boolean): Boolean = authenticated && !busy
}
