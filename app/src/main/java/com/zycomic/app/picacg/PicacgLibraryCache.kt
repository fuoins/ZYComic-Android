package com.zycomic.app.picacg

import com.zycomic.app.data.dto.Manga

/** Cache identity contains no credential material; sessionRevision changes on every login/logout. */
internal data class PicacgLibraryCacheKey(
    val sessionRevision: Long,
    val favouritesRevision: Long,
    val sort: String,
    val query: String,
)

/** An in-process-only copy of the currently loaded favorites pages. */
internal data class PicacgLibraryCacheEntry(
    val items: List<Manga>,
    val page: Int,
    val hasMore: Boolean,
)

/** Session-scoped memory snapshots; no Picacg token or account credential is retained here. */
internal object PicacgLibraryCache {
    private data class ActiveSession(val sessionRevision: Long, val favouritesRevision: Long)

    private var activeSession: ActiveSession? = null
    private val entries = mutableMapOf<PicacgLibraryCacheKey, PicacgLibraryCacheEntry>()

    @Synchronized
    fun activateSession(sessionRevision: Long, favouritesRevision: Long, authenticated: Boolean) {
        val next = if (authenticated) ActiveSession(sessionRevision, favouritesRevision) else null
        if (activeSession != next) {
            entries.clear()
            activeSession = next
        }
    }

    @Synchronized
    fun get(key: PicacgLibraryCacheKey): PicacgLibraryCacheEntry? =
        if (key.matches(activeSession)) entries[key]?.let { it.copy(items = it.items.toList()) } else null

    @Synchronized
    fun put(key: PicacgLibraryCacheKey, entry: PicacgLibraryCacheEntry): Boolean {
        if (!key.matches(activeSession)) return false
        entries[key] = entry.copy(items = entry.items.toList())
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
        activeSession = null
    }

    private fun PicacgLibraryCacheKey.matches(session: ActiveSession?): Boolean =
        session != null && sessionRevision == session.sessionRevision && favouritesRevision == session.favouritesRevision
}
