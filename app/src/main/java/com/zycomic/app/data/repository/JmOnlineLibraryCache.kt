package com.zycomic.app.data.repository

/** The list type is part of the key so cached favorites can never be confused with online history. */
internal enum class JmOnlineLibraryListType {
    FAVORITES,
    HISTORY,
}

internal data class JmOnlineLibraryCacheKey(
    val sessionRevision: Long,
    val identity: String,
    val listType: JmOnlineLibraryListType,
    val folderId: String = "",
    val sortOrder: String = "",
)

/** A process-memory snapshot of all pages loaded for one favorites or online-history query. */
internal data class JmOnlineLibraryCacheEntry(
    val items: List<JmOnlineLibraryItem>,
    val total: Int,
    val folders: List<JmFavoriteFolder>,
    val page: Int,
    val hasMore: Boolean,
    /** Raw history IDs include hidden chapter rows, matching [JmHistoryDisplayPolicy]'s paging cursor. */
    val historyLoadedIds: Set<String> = emptySet(),
)

/** State transitions for hit-first loading: revalidation and failure never discard a cached snapshot. */
internal data class JmOnlineLibraryLoadState(
    val snapshot: JmOnlineLibraryCacheEntry?,
    val loading: Boolean,
    val error: String? = null,
) {
    fun beginLoad(): JmOnlineLibraryLoadState = copy(loading = true, error = null)

    fun complete(entry: JmOnlineLibraryCacheEntry): JmOnlineLibraryLoadState =
        copy(snapshot = entry, loading = false, error = null)

    fun fail(message: String): JmOnlineLibraryLoadState = copy(loading = false, error = message)

    fun stopLoading(): JmOnlineLibraryLoadState = copy(loading = false)
}

/**
 * Process-local cache only. It is deliberately not persisted, and reads/writes are valid only for
 * the currently active authenticated session. Query dimensions live in [JmOnlineLibraryCacheKey].
 */
internal object JmOnlineLibraryCache {
    private data class SessionIdentity(val revision: Long, val identity: String)

    private var activeSession: SessionIdentity? = null
    private val entries = mutableMapOf<JmOnlineLibraryCacheKey, JmOnlineLibraryCacheEntry>()

    @Synchronized
    fun activateSession(sessionRevision: Long, identity: String, authenticated: Boolean) {
        val next = if (authenticated && identity.isNotBlank()) SessionIdentity(sessionRevision, identity) else null
        if (activeSession != next) {
            entries.clear()
            activeSession = next
        }
    }

    @Synchronized
    fun get(key: JmOnlineLibraryCacheKey): JmOnlineLibraryCacheEntry? =
        if (key.matches(activeSession)) entries[key] else null

    @Synchronized
    fun put(key: JmOnlineLibraryCacheKey, entry: JmOnlineLibraryCacheEntry): Boolean {
        if (!key.matches(activeSession)) return false
        entries[key] = entry.copy(
            items = entry.items.toList(),
            folders = entry.folders.toList(),
            historyLoadedIds = entry.historyLoadedIds.toSet(),
        )
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
        activeSession = null
    }

    private fun JmOnlineLibraryCacheKey.matches(session: SessionIdentity?): Boolean =
        session != null && sessionRevision == session.revision && identity == session.identity
}

internal object JmOnlineLibraryCachePolicy {
    /** Page refresh replaces the stale first-page snapshot; later pages append only unseen IDs. */
    fun replaceFirstPage(page: JmOnlineLibraryPage): List<JmOnlineLibraryItem> = distinctById(page.items)

    fun appendPage(
        existing: List<JmOnlineLibraryItem>,
        incoming: List<JmOnlineLibraryItem>,
    ): List<JmOnlineLibraryItem> =
        distinctById(existing + incoming)

    private fun distinctById(items: List<JmOnlineLibraryItem>): List<JmOnlineLibraryItem> = items.distinctBy { it.id }
}
