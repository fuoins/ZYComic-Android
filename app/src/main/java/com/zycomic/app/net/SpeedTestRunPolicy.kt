package com.zycomic.app.net

/** Process-local generation identity. This is distinct from persisted history eligibility. */
internal data class SpeedTestRunKey(
    val platformRevision: Long,
    val manwaConfigRevision: Long,
    val jmConfigRevision: Long,
    val networkIdentity: String,
    val picacgConfigRevision: Long = 0L,
    val remoteVersion: Int = 0,
)

internal object SpeedTestRunPolicy {
    fun canSkip(
        completed: SpeedTestRunKey?,
        current: SpeedTestRunKey,
        forceFresh: Boolean,
        platformOnly: Boolean,
        remoteConfigUpdated: Boolean = false,
    ): Boolean = !forceFresh && !platformOnly && !remoteConfigUpdated && completed == current

    /** Each tester validates its history's configuration version, not the current endpoint pool. */
    fun canFastTest(
        requested: Boolean,
        forceFresh: Boolean,
        remoteConfigUpdated: Boolean = false,
    ): Boolean = requested && !forceFresh && !remoteConfigUpdated

    // Retain the existing call contract for callers/tests outside the coordinator.
    @Suppress("UNUSED_PARAMETER")
    fun canReuseImages(
        requested: Boolean,
        forceFresh: Boolean,
        initial: SpeedTestRunKey,
        current: SpeedTestRunKey,
        completed: SpeedTestRunKey?,
        remoteConfigUpdated: Boolean = false,
    ): Boolean = canFastTest(requested, forceFresh, remoteConfigUpdated)
}
