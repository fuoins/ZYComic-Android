package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One queued/running full test; callers arriving before it finishes share its outcome. */
internal class FullSpeedTestRequest<T> {
    private val lock = Mutex()
    private var pending: CompletableDeferred<T>? = null

    suspend fun run(block: suspend () -> T): T {
        val (request, owner) = lock.withLock {
            val existing = pending
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<T>().also { pending = it } to true
            }
        }
        if (!owner) return request.await()
        try {
            return block().also { request.complete(it) }
        } catch (error: Throwable) {
            request.completeExceptionally(error)
            throw error
        } finally {
            // The owner may be cancelled; clearing the reference must not suspend.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                lock.withLock { if (pending === request) pending = null }
            }
        }
    }
}
