package com.zycomic.app.ui.components

/** A collection response belongs only to the account session that issued it. */
internal object PicacgCollectionPolicy {
    fun requiresLogin(history: Boolean, loggedIn: Boolean): Boolean = !history && !loggedIn

    fun acceptsResult(startedSession: Long, currentSession: Long, loggedIn: Boolean): Boolean =
        loggedIn && startedSession == currentSession

    fun <T> merge(existing: List<T>, incoming: List<T>, firstPage: Boolean, id: (T) -> String): List<T> =
        (if (firstPage) incoming else existing + incoming).distinctBy(id)

    fun matchesSearch(query: String?, title: String): Boolean =
        query.isNullOrBlank() || title.contains(query.trim(), ignoreCase = true)
}
