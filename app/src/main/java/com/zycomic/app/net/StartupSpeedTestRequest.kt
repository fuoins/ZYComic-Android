package com.zycomic.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** The worker owns completion; a foreground waiter may leave after current-generation routes are ready. */
internal class StartupSpeedTestRequest<K, T>(
    private val scope: CoroutineScope,
    private val reuseCompleted: Boolean = true,
) {
    private data class Ready<K, T>(val key: K, val result: T)
    private class Entry<K, T>(var key: K, val job: Deferred<T>, val ready: MutableStateFlow<Ready<K, T>?>)

    private val lock = Any()
    private var entry: Entry<K, T>? = null

    fun invalidate() = synchronized(lock) {
        // Explicit retests own a new request; never cancel an existing waiter's worker.
        entry = null
    }

    suspend fun run(currentKey: () -> K, block: suspend () -> Pair<K, T>): T =
        runUntilReady(currentKey) { block() }

    suspend fun runUntilReady(
        currentKey: () -> K,
        block: suspend (publishReady: (K, T) -> Unit) -> Pair<K, T>,
    ): T {
        val request = synchronized(lock) {
            val existing = entry
            if (existing != null &&
                (!existing.job.isCompleted || (reuseCompleted && existing.key == currentKey())) &&
                !existing.job.isCancelled
            ) {
                existing
            } else {
                lateinit var created: Entry<K, T>
                val ready = MutableStateFlow<Ready<K, T>?>(null)
                val job = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val (completedKey, result) = block { key, result -> ready.value = Ready(key, result) }
                        synchronized(lock) {
                            if (entry === created) created.key = completedKey
                        }
                        result
                    } catch (error: Throwable) {
                        synchronized(lock) {
                            if (entry === created) entry = null
                        }
                        throw error
                    }
                }
                Entry(currentKey(), job, ready).also {
                    created = it
                    entry = it
                }
            }
        }
        request.job.start()
        return coroutineScope {
            val foreground = async {
                request.ready.filterNotNull().first { it.key == currentKey() }
            }
            try {
                // Completion stays an actual completion. Early readiness returns no fabricated final report.
                while (true) {
                    val result = select<Pair<K?, T>> {
                        request.job.onAwait { null to it }
                        foreground.onAwait { it.key to it.result }
                    }
                    if (result.first == null || result.first == currentKey()) return@coroutineScope result.second
                    // A generation changed between observing readiness and resuming this waiter.
                    return@coroutineScope runUntilReady(currentKey, block)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } finally {
                foreground.cancel()
            }
        }
    }
}
