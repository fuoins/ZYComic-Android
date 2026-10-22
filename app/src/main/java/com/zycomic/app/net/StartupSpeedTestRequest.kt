package com.zycomic.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Startup waiters share one worker, including its result after completion, until its identity changes. */
internal class StartupSpeedTestRequest<K, T>(private val scope: CoroutineScope) {
    private class Entry<K, T>(var key: K, val job: Deferred<T>)

    private val lock = Any()
    private var entry: Entry<K, T>? = null

    fun invalidate() = synchronized(lock) {
        // Explicit retests own a new request; never cancel an existing waiter's worker.
        entry = null
    }

    suspend fun run(currentKey: () -> K, block: suspend () -> Pair<K, T>): T {
        val request = synchronized(lock) {
            val existing = entry
            if (existing != null && (!existing.job.isCompleted || existing.key == currentKey()) && !existing.job.isCancelled) {
                // The coordinator follows identity changes within an active startup worker.
                existing
            } else {
                lateinit var created: Entry<K, T>
                val job = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val (completedKey, result) = block()
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
                Entry(currentKey(), job).also {
                    created = it
                    entry = it
                }
            }
        }
        request.job.start()
        return request.job.await()
    }
}
