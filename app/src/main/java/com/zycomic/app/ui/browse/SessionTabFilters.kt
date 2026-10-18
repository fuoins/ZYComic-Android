package com.zycomic.app.ui.browse

/** In-memory overrides only; persisted defaults are read once per tab in this run. */
internal class SessionTabFilters<T> {
    private val values = mutableMapOf<String, T>()

    fun get(tabIdentity: String, default: () -> T): T = values.getOrPut(tabIdentity, default)

    fun set(tabIdentity: String, value: T) {
        values[tabIdentity] = value
    }

    fun remove(tabIdentity: String) {
        values.remove(tabIdentity)
    }

    fun clear() = values.clear()
}
