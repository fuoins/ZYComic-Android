package com.zycomic.app.picacg

import com.zycomic.app.data.dto.Manga

/** Process-only detail snapshots. No disk persistence, credentials, or cross-platform entries. */
internal data class PicacgDetailSnapshot(val manga: Manga, val state: PicacgComicState?)

internal object PicacgDetailCache {
    private const val MAX_ENTRIES = 20
    private var activeSession: Long? = null
    private val entries = LinkedHashMap<String, PicacgDetailSnapshot>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun activateSession(revision: Long, authenticated: Boolean) {
        val next = revision.takeIf { authenticated }
        if (activeSession != next) {
            entries.clear()
            activeSession = next
        }
    }

    @Synchronized
    fun get(revision: Long, id: String): PicacgDetailSnapshot? =
        if (activeSession == revision) entries[id] else null

    @Synchronized
    fun put(revision: Long, id: String, manga: Manga, state: PicacgComicState?): Boolean {
        if (activeSession != revision) return false
        entries[id] = PicacgDetailSnapshot(manga, state)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        return true
    }

    @Synchronized
    fun invalidateState(revision: Long, id: String) {
        if (activeSession != revision) return
        entries[id]?.let { entries[id] = it.copy(state = null) }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        activeSession = null
    }
}
