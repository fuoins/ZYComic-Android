package com.zycomic.app.ui.browse

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Shares a successful promote snapshot, including an empty pool; failed loads remain retryable. */
internal class JmPromoteCache<T>(
    private val lifetimeMillis: Long = 5 * 60 * 1000L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var snapshot: T? = null
    private var loadedAt = 0L

    suspend fun get(forceRefresh: Boolean = false, load: suspend () -> T): T = mutex.withLock {
        val cached = snapshot
        if (!forceRefresh && cached != null && nowMillis() - loadedAt < lifetimeMillis) return@withLock cached
        load().also {
            snapshot = it
            loadedAt = nowMillis()
        }
    }
}

internal object JmRandomAlbumPolicy {
    const val SERIES_UPDATE_SECTION_ID = "26"

    fun choose(ids: List<String>, previousId: String? = null, random: Random = Random.Default): String? {
        val valid = ids.mapNotNull(JmAlbumJumpPolicy::albumId).distinct()
        val candidates = valid.filterNot { it == previousId }.ifEmpty { valid }
        return candidates.randomOrNull(random)
    }
}
