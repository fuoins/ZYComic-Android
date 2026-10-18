package com.zycomic.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** OS DNS may ignore interruption; bounded daemon workers keep it outside the startup coroutine. */
internal class JmProbeDnsResolver(
    private val timeoutMs: Long = 5_000L,
    private val lookup: (String) -> List<String> = { host ->
        InetAddress.getAllByName(host).map { it.hostAddress.substringBefore('%') }.distinct()
    },
) {
    suspend fun resolve(host: String): List<String> = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            try {
                val future = executor.submit {
                    val addresses = runCatching { lookup(host) }.getOrDefault(emptyList())
                    if (continuation.isActive) continuation.resume(addresses)
                }
                continuation.invokeOnCancellation { future.cancel(true) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                continuation.resume(emptyList())
            }
        }
    } ?: emptyList()

    private companion object {
        val executor = ThreadPoolExecutor(
            4,
            4,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(24),
            { task -> Thread(task, "jm-probe-dns").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
