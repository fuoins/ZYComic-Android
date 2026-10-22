package com.zycomic.app.picacg

/** A cache identity contains no credential material; sessionRevision changes whenever the account changes. */
internal data class PicacgBrowseCacheCondition(
    val sessionRevision: Long,
    val keywordRevision: Long,
    val routeConfigRevision: Long,
    val configGeneration: Long,
    val blockedKeywords: List<String>,
)

/** Full request identity for a successful browse, rank, category, or advanced-search result page. */
internal data class PicacgBrowseCacheKey(
    val condition: PicacgBrowseCacheCondition,
    val operation: String,
    val mode: String,
    val query: String,
    val sort: String,
    val filters: List<String>,
    val page: Int,
)

internal data class PicacgBrowseSnapshot<T>(val value: T, val savedAtMillis: Long)

internal sealed interface PicacgBrowseCacheLookup<out T> {
    data class Fresh<T>(val value: T) : PicacgBrowseCacheLookup<T>
    data class Stale<T>(val value: T) : PicacgBrowseCacheLookup<T>
}

/**
 * Bounded, process-memory-only successful-result snapshots. Refresh failures never replace the last
 * success, and a condition change drops prior snapshots before any request under the new condition.
 */
internal class PicacgBrowseSnapshotCache<T>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val entries = object : LinkedHashMap<PicacgBrowseCacheKey, PicacgBrowseSnapshot<T>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PicacgBrowseCacheKey, PicacgBrowseSnapshot<T>>?) = size > maxEntries
    }
    private var activeCondition: PicacgBrowseCacheCondition? = null

    init {
        require(maxEntries > 0)
        require(ttlMillis > 0)
    }

    @Synchronized
    fun activate(condition: PicacgBrowseCacheCondition) {
        if (activeCondition != condition) {
            entries.clear()
            activeCondition = condition.copy(blockedKeywords = condition.blockedKeywords.toList())
        }
    }

    @Synchronized
    fun get(key: PicacgBrowseCacheKey, nowMillis: Long): PicacgBrowseCacheLookup<T>? {
        if (key.condition != activeCondition) return null
        val snapshot = entries[key] ?: return null
        return if (nowMillis - snapshot.savedAtMillis < ttlMillis) {
            PicacgBrowseCacheLookup.Fresh(snapshot.value)
        } else {
            PicacgBrowseCacheLookup.Stale(snapshot.value)
        }
    }

    /** Returns false for results from a session/filter/configuration that is no longer active. */
    @Synchronized
    fun put(key: PicacgBrowseCacheKey, value: T, nowMillis: Long): Boolean {
        if (key.condition != activeCondition) return false
        entries[key] = PicacgBrowseSnapshot(value, nowMillis)
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
        activeCondition = null
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L
    }
}
