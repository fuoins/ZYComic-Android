package com.zycomic.app.net

/** Process-local identity: persisted results can only bypass image probes, never startup lines. */
internal data class SpeedTestRunKey(
    val platformRevision: Long,
    val manwaConfigRevision: Long,
    val jmConfigRevision: Long,
    val networkIdentity: String,
)

internal object SpeedTestRunPolicy {
    fun canSkip(
        completed: SpeedTestRunKey?,
        current: SpeedTestRunKey,
        forceFresh: Boolean,
        platformOnly: Boolean,
        remoteConfigUpdated: Boolean = false,
    ): Boolean = !forceFresh && !remoteConfigUpdated && !platformOnly && completed == current

    fun canReuseImages(
        requested: Boolean,
        forceFresh: Boolean,
        initial: SpeedTestRunKey,
        current: SpeedTestRunKey,
        completed: SpeedTestRunKey?,
        remoteConfigUpdated: Boolean = false,
    ): Boolean = requested && !forceFresh && !remoteConfigUpdated && initial.networkIdentity == current.networkIdentity &&
        (completed == null || completed.networkIdentity == current.networkIdentity)
}
