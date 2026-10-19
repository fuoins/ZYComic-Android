package com.zycomic.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Coordinates deferred, shared sample-path lookup after a fixed-cover image probe. */
internal object ManwaImageFallbackProbePolicy {
    data class Attempt<T>(val path: String, val result: T)

    suspend fun <T> probeFixedThenFallback(
        fixedPath: String,
        samplePath: suspend () -> String?,
        probe: suspend (String) -> T,
        isSuccess: (T) -> Boolean,
        hasHttpResponse: (T) -> Boolean,
    ): List<Attempt<T>> {
        val fixedResult = probe(fixedPath)
        val attempts = mutableListOf(Attempt(fixedPath, fixedResult))
        if (isSuccess(fixedResult) || !hasHttpResponse(fixedResult)) return attempts

        val fallbackPath = samplePath() ?: return attempts
        if (fallbackPath == fixedPath) return attempts

        attempts += Attempt(fallbackPath, probe(fallbackPath))
        return attempts
    }

    /** A deferred child that starts only on first await and is shared by all callers. */
    class SharedLazy<T> internal constructor(
        private val deferred: Deferred<T>,
    ) {
        suspend fun await(): T = deferred.await()

        fun cancel() {
            deferred.cancel()
        }
    }

    fun <T> sharedLazy(scope: CoroutineScope, provider: suspend () -> T): SharedLazy<T> =
        SharedLazy(scope.async(start = CoroutineStart.LAZY) { provider() })
}
