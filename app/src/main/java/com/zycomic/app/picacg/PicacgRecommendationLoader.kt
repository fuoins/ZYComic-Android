package com.zycomic.app.picacg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/** Loads at most ten recommendations with at most three concurrent lookups, retaining input order. */
internal suspend fun <T> loadPicacgRecommendations(
    ids: List<String>,
    load: suspend (String) -> T?,
): List<T> = coroutineScope {
    val selectedIds = ids.take(10)
    if (selectedIds.isEmpty()) return@coroutineScope emptyList()
    val semaphore = Semaphore(3)
    val results = selectedIds.map { id ->
        async {
            try {
                semaphore.withPermit { load(id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }.awaitAll().filterNotNull()
    if (results.isEmpty()) throw IOException("All Picacg recommendation lookups failed")
    results
}
