package com.zycomic.app.ui.manga

/** Detail actions may only use the account belonging to their own source. */
internal object DetailLoginPolicy {
    fun isAuthenticated(isJm: Boolean, jmAuthenticated: Boolean, manwaAuthenticated: Boolean): Boolean =
        if (isJm) jmAuthenticated else manwaAuthenticated
}
