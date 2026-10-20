package com.zycomic.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Pure scan model and classifier. It deliberately knows nothing about Android, DNS, or route managers. */
internal object DeprecatedEndpointScanPolicy {
    enum class Kind { LINE, IMAGE }

    enum class State { FULLY_FAILED, HAS_SUCCESS, UNDETERMINED }

    enum class ProbeResult { SUCCESS, EXPLICIT_FAILURE, INCOMPLETE }

    enum class Reason {
        CONFIGURATION_CHANGED,
        MISSING_IP,
        MISSING_IMAGE_SAMPLE,
        INVALID_IP_MAPPING,
        INCOMPLETE_PROBE,
        ALL_FAILED,
        HAS_SUCCESS,
    }

    data class Target(
        val kind: Kind,
        val host: String,
        /** The complete, configured mapping for this host; never augmented with DNS results. */
        val configuredIps: List<String>,
        /** A reason a target must be skipped because its configuration/sample cannot be evaluated. */
        val blockedReason: Reason? = null,
    )

    data class TargetResult(
        val kind: Kind,
        val host: String,
        val state: State,
        val configuredIpCount: Int,
        val probedIpCount: Int,
        val successCount: Int,
        val failureCount: Int,
        val reason: Reason? = null,
    )

    data class Progress(val completedIps: Int, val totalIps: Int, val currentHost: String)

    /**
     * Scans exactly every configured IP. Any successful probe proves the target is not deprecated;
     * otherwise it is fully failed only when every configured IP explicitly failed. Cancellation
     * propagates, and a configuration change invalidates the entire result set.
     */
    suspend fun scan(
        targets: List<Target>,
        isConfigurationCurrent: () -> Boolean,
        probe: suspend (kind: Kind, host: String, ip: String) -> ProbeResult,
        onProgress: ((Progress) -> Unit)? = null,
    ): List<TargetResult> {
        val totalIps = targets.sumOf { it.configuredIps.size }
        var completedIps = 0
        val results = targets.map { target ->
            if (!isConfigurationCurrent()) return targets.map { undetermined(it, Reason.CONFIGURATION_CHANGED) }
            val blocked = target.blockedReason
            if (blocked != null) return@map undetermined(target, blocked)
            val ips = target.configuredIps
            if (ips.isEmpty()) return@map undetermined(target, Reason.MISSING_IP)

            var successes = 0
            var failures = 0
            var incompletes = 0
            ips.forEach { ip ->
                if (!isConfigurationCurrent()) return targets.map { undetermined(it, Reason.CONFIGURATION_CHANGED) }
                currentCoroutineContext().ensureActive()
                val probeResult = try {
                    probe(target.kind, target.host, ip)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    ProbeResult.INCOMPLETE
                }
                currentCoroutineContext().ensureActive()
                when (probeResult) {
                    ProbeResult.SUCCESS -> successes++
                    ProbeResult.EXPLICIT_FAILURE -> failures++
                    ProbeResult.INCOMPLETE -> incompletes++
                }
                completedIps++
                onProgress?.invoke(Progress(completedIps, totalIps, target.host))
            }
            if (!isConfigurationCurrent()) return targets.map { undetermined(it, Reason.CONFIGURATION_CHANGED) }
            val state = when {
                incompletes > 0 -> State.UNDETERMINED
                successes > 0 -> State.HAS_SUCCESS
                failures == ips.size -> State.FULLY_FAILED
                else -> State.UNDETERMINED
            }
            TargetResult(
                kind = target.kind,
                host = target.host,
                state = state,
                configuredIpCount = ips.size,
                probedIpCount = successes + failures + incompletes,
                successCount = successes,
                failureCount = failures,
                reason = when (state) {
                    State.FULLY_FAILED -> Reason.ALL_FAILED
                    State.HAS_SUCCESS -> Reason.HAS_SUCCESS
                    State.UNDETERMINED -> Reason.INCOMPLETE_PROBE
                },
            )
        }
        return if (isConfigurationCurrent()) results else targets.map { undetermined(it, Reason.CONFIGURATION_CHANGED) }
    }

    private fun undetermined(target: Target, reason: Reason) = TargetResult(
        kind = target.kind,
        host = target.host,
        state = State.UNDETERMINED,
        configuredIpCount = target.configuredIps.size,
        probedIpCount = 0,
        successCount = 0,
        failureCount = 0,
        reason = reason,
    )
}
