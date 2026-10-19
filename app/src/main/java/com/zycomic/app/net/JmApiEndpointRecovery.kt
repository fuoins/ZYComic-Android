package com.zycomic.app.net

import java.util.concurrent.ConcurrentHashMap

/** Coordinates one read-only endpoint validation at a time and suppresses repeated probes. */
internal class JmApiEndpointRecovery<T>(
    cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val consecutiveFailures = ConcurrentHashMap<DomainIp, Int>()

    internal data class Candidate<T>(
        val endpoint: DomainIp,
        val state: T,
    )

    private val gate = RuntimeRecoveryPolicy.Gate(cooldownMs)

    /** Returns true only after three consecutive actual transport failures for this endpoint. */
    fun recordConnectionFailure(endpoint: DomainIp): Boolean {
        val key = endpoint.normalizedForComparison()
        val failures = consecutiveFailures.compute(key) { _, previous ->
            ((previous ?: 0) + 1).coerceAtMost(FAILURE_THRESHOLD)
        } ?: 0
        return failures >= FAILURE_THRESHOLD
    }

    /** An HTTP response proves connectivity and breaks the connection-failure streak. */
    fun recordResponse(endpoint: DomainIp) {
        consecutiveFailures.remove(endpoint.normalizedForComparison())
    }

    fun hasFailureThreshold(endpoint: DomainIp): Boolean =
        (consecutiveFailures[endpoint.normalizedForComparison()] ?: 0) >= FAILURE_THRESHOLD

    fun failureCount(endpoint: DomainIp): Int =
        consecutiveFailures[endpoint.normalizedForComparison()] ?: 0

    fun clearFailures() = consecutiveFailures.clear()

    /** Validates exactly the captured endpoint; [commit] must atomically recheck its state token. */
    fun tryRecover(
        capture: () -> Candidate<T>?,
        validate: (DomainIp) -> Boolean,
        commit: (Candidate<T>) -> Boolean,
    ): Boolean {
        if (!gate.tryAcquire(nowMs())) return false
        try {
            val candidate = capture() ?: return false
            if (!validate(candidate.endpoint)) return false
            return commit(candidate)
        } finally {
            gate.release(nowMs())
        }
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val FAILURE_THRESHOLD = 3
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
