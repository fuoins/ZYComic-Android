package com.zycomic.app.picacg

fun main() {
    val condition = PicacgBrowseCacheCondition(7L, 3L, 5L, 11L, listOf("blocked"))
    fun key(page: Int = 1, mode: String = "browse", query: String = "", sort: String = "dd") =
        PicacgBrowseCacheKey(condition, "browse", mode, query, sort, listOf("category", "tag"), page)

    val cache = PicacgBrowseSnapshotCache<String>(maxEntries = 2, ttlMillis = 100L)
    cache.activate(condition)
    check(cache.get(key(), 1_000L) == null) { "an empty cache must miss" }
    check(cache.put(key(), "last-good", 1_000L))
    check(cache.get(key(), 1_099L) == PicacgBrowseCacheLookup.Fresh("last-good"))
    check(cache.get(key(), 1_100L) == PicacgBrowseCacheLookup.Stale("last-good")) { "TTL boundary must become stale" }

    // A failed network refresh never calls put, so the last successful value remains available.
    check(cache.get(key(), 1_101L) == PicacgBrowseCacheLookup.Stale("last-good"))
    check(cache.put(key(), "revalidated", 1_102L))
    check(cache.get(key(), 1_103L) == PicacgBrowseCacheLookup.Fresh("revalidated"))

    check(cache.put(key(page = 2), "page-two", 1_104L))
    cache.get(key(), 1_105L) // Accessing page one makes it most-recently used.
    check(cache.put(key(page = 3), "page-three", 1_106L))
    check(cache.get(key(), 1_107L) == PicacgBrowseCacheLookup.Fresh("revalidated"))
    check(cache.get(key(page = 2), 1_107L) == null) { "least-recently-used page must be evicted at capacity" }
    check(cache.size() == 2)

    // Every request dimension is independently part of the cache identity.
    check(cache.get(key(mode = "category"), 1_107L) == null)
    check(cache.get(key(query = "word"), 1_107L) == null)
    check(cache.get(key(sort = "vd"), 1_107L) == null)

    val nextSession = condition.copy(sessionRevision = 8L)
    cache.activate(nextSession)
    check(cache.get(key(), 1_107L) == null) { "a changed session must invalidate snapshots" }
    check(cache.size() == 0)
    val nextFilter = nextSession.copy(keywordRevision = 4L, blockedKeywords = listOf("other"))
    cache.activate(nextFilter)
    check(cache.get(key(), 1_107L) == null) { "a changed keyword filter must not cross conditions" }
    val nextConfig = nextFilter.copy(routeConfigRevision = 6L)
    cache.activate(nextConfig)
    check(cache.get(key(), 1_107L) == null) { "a changed route configuration must not cross conditions" }
    val nextClient = nextConfig.copy(configGeneration = 12L)
    cache.activate(nextClient)
    check(cache.put(PicacgBrowseCacheKey(nextClient, "search", "advanced", "secret query", "ld", listOf("cat"), 1), "ok", 2_000L))
    check(cache.size() == 1)

    println("Picacg browse snapshot cache policy: OK")
}
