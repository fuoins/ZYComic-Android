package com.zycomic.app.net

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

/** Pure runtime recovery decisions; deliberately independent from speed-test rankings. */
internal object RuntimeRecoveryPolicy {
    private const val WINDOW_MS = 10_000L

    enum class Platform { MANWA, JM, PICACG }
    enum class SampleKind { COVER, VISIBLE_READER_PAGE }
    enum class FailureKind { CANCELLED, CONNECTION, RATE_LIMIT, AUTH, NOT_FOUND, SERVER, HTTP, DECODE_OR_CACHE }

    fun classifyFailure(error: Throwable? = null, httpCode: Int? = null, cancelled: Boolean = false): FailureKind {
        val causes = generateSequence(error) { it.cause }.take(32).toList()
        if (cancelled || causes.any {
                it is CancellationException || (it is InterruptedIOException && it !is SocketTimeoutException)
            }
        ) {
            return FailureKind.CANCELLED
        }
        if (httpCode != null) {
            return when (httpCode) {
                429 -> FailureKind.RATE_LIMIT
                401, 403 -> FailureKind.AUTH
                404 -> FailureKind.NOT_FOUND
                in 500..599 -> FailureKind.SERVER
                else -> FailureKind.HTTP
            }
        }
        return if (error != null && isTransportFailure(error)) FailureKind.CONNECTION else FailureKind.DECODE_OR_CACHE
    }

    data class Sample(
        val platform: Platform,
        val source: String,
        val resource: String,
        val kind: SampleKind,
        val failed: Boolean,
        val atMs: Long,
    )

    /** Status errors and content/decode errors are not transport failures. */
    fun isConnectionFailure(error: IOException): Boolean =
        error is ConnectException || error is UnknownHostException ||
            error is NoRouteToHostException || error is SocketTimeoutException || error is SocketException ||
            error is EOFException

    /** Only explicit connection/socket failures count; generic IO, HTTP, decode and cancellation do not. */
    fun isTransportFailure(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.take(32).toList()
        if (causes.any { it is CancellationException || (it is InterruptedIOException && it !is SocketTimeoutException) }) return false
        return causes.any { it is IOException && isConnectionFailure(it) }
    }

    /** Non-idempotent writes are never eligible for replay or connection-triggered route recovery. */
    fun mayRecoverApi(method: String, error: IOException): Boolean =
        (method.equals("GET", true) || method.equals("HEAD", true)) && isConnectionFailure(error)

    class Window {
        private val events = ArrayDeque<Sample>()

        @Synchronized
        fun record(sample: Sample): Boolean {
            events.addLast(sample)
            val cutoff = sample.atMs - WINDOW_MS
            while (events.isNotEmpty() && events.first().atMs < cutoff) events.removeFirst()
            val group = events.filter {
                it.platform == sample.platform && it.source.equals(sample.source, true) && it.kind == sample.kind
            }.groupBy { it.resource }.values.map { resourceEvents -> resourceEvents.last() }
            return when (sample.kind) {
                SampleKind.COVER -> group.count { it.failed } >= 3 && group.count { it.failed } * 2 > group.size
                SampleKind.VISIBLE_READER_PAGE -> group.count { it.failed } >= 2
            }
        }

        @Synchronized
        fun clear() = events.clear()

        @Synchronized
        fun clear(platform: Platform) = events.removeAll { it.platform == platform }
    }

    /** Keeps only the latest retry callback per failed resource for one recovery window. */
    class RetryCallbacks(private val windowMs: Long = WINDOW_MS) {
        private data class Key(val platform: Platform, val source: String)
        private data class Entry(val atMs: Long, val retry: () -> Unit)

        private val groups = mutableMapOf<Key, MutableMap<String, Entry>>()

        @Synchronized
        fun record(platform: Platform, source: String, resource: String, atMs: Long, retry: () -> Unit) {
            val key = Key(platform, source.lowercase())
            prune(key, atMs)
            groups.getOrPut(key) { linkedMapOf() }[resource] = Entry(atMs, retry)
        }

        @Synchronized
        fun take(platform: Platform, source: String, atMs: Long): List<() -> Unit> {
            val key = Key(platform, source.lowercase())
            prune(key, atMs)
            return groups.remove(key)?.values?.map { it.retry }.orEmpty()
        }

        @Synchronized
        fun clear(platform: Platform, source: String) {
            groups.remove(Key(platform, source.lowercase()))
        }

        @Synchronized
        fun clear(platform: Platform) {
            groups.keys.removeAll { it.platform == platform }
        }

        @Synchronized
        fun expire(atMs: Long) {
            groups.keys.toList().forEach { prune(it, atMs) }
        }

        private fun prune(key: Key, atMs: Long) {
            val group = groups[key] ?: return
            val cutoff = atMs - windowMs
            group.entries.removeAll { it.value.atMs < cutoff }
            if (group.isEmpty()) groups.remove(key)
        }
    }

    fun mayRetryVisiblePage(
        expectedGeneration: Int,
        currentGeneration: Int,
        visible: Boolean,
        attemptActive: Boolean,
        failed: Boolean,
        sourceChanged: Boolean,
    ): Boolean = expectedGeneration == currentGeneration && visible && !attemptActive && failed && sourceChanged

    /** A monotonic cooldown gate also provides single-flight ownership. */
    class Gate(private val cooldownMs: Long) {
        private var inFlight = false
        private var nextAllowedAt = Long.MIN_VALUE

        @Synchronized
        fun tryAcquire(nowMs: Long): Boolean {
            if (inFlight || nowMs < nextAllowedAt) return false
            inFlight = true
            return true
        }

        @Synchronized
        fun release(nowMs: Long) {
            inFlight = false
            nextAllowedAt = nowMs + cooldownMs
        }
    }
}
