package com.zycomic.app.picacg

fun main() {
    val condition = PicacgBrowseCacheCondition(7L, 3L, 5L, 11L, listOf("blocked"))
    fun key(
        page: Int = 1,
        operation: String = "browse",
        mode: String = "all",
        query: String = "",
        sort: String = "dd",
        filters: List<String> = listOf("category", "tag"),
        requestCondition: PicacgBrowseCacheCondition = condition,
    ) = PicacgBrowseCacheKey(requestCondition, operation, mode, query, sort, filters, page)

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
    check(cache.get(key(operation = "search"), 1_107L) == null)
    check(cache.get(key(mode = "category"), 1_107L) == null)
    check(cache.get(key(query = "word"), 1_107L) == null)
    check(cache.get(key(sort = "vd"), 1_107L) == null)
    check(cache.get(key(filters = listOf("different")), 1_107L) == null)
    check(cache.get(key(page = 3), 1_107L) == PicacgBrowseCacheLookup.Fresh("page-three"))
    check(cache.get(key(), 1_107L) == PicacgBrowseCacheLookup.Fresh("revalidated"))

    // A request started under an old condition cannot repopulate the active cache.
    val nextSession = condition.copy(sessionRevision = 8L)
    cache.activate(nextSession)
    check(cache.get(key(requestCondition = nextSession), 1_107L) == null) { "a changed session must invalidate snapshots" }
    check(cache.size() == 0)
    check(!cache.put(key(requestCondition = condition), "late-old-session", 1_108L))
    check(cache.get(key(requestCondition = nextSession), 1_108L) == null)

    val nextKeywordRevision = nextSession.copy(keywordRevision = 4L)
    cache.activate(nextKeywordRevision)
    check(cache.get(key(requestCondition = nextKeywordRevision), 1_109L) == null)
    val nextBlocklist = nextKeywordRevision.copy(blockedKeywords = listOf("other"))
    cache.activate(nextBlocklist)
    check(cache.get(key(requestCondition = nextBlocklist), 1_110L) == null) { "changed blocked keywords must invalidate snapshots" }
    val nextRoute = nextBlocklist.copy(routeConfigRevision = 6L)
    cache.activate(nextRoute)
    check(cache.get(key(requestCondition = nextRoute), 1_111L) == null) { "changed route configuration must invalidate snapshots" }
    val nextGeneration = nextRoute.copy(configGeneration = 12L)
    cache.activate(nextGeneration)
    check(cache.get(key(requestCondition = nextGeneration), 1_112L) == null) { "changed client/config generation must invalidate snapshots" }

    check(cache.put(key(operation = "search", mode = "advanced", query = "private query", sort = "ld", filters = listOf("cat"), requestCondition = nextGeneration), "ok", 2_000L))
    check(cache.size() == 1)

    println("Picacg browse snapshot cache policy: OK")
}
