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
    @Suppress("UNUSED_PARAMETER")
    fun canSkip(
        completed: SpeedTestRunKey?,
        current: SpeedTestRunKey,
        forceFresh: Boolean,
        platformOnly: Boolean,
        remoteConfigUpdated: Boolean = false,
    ): Boolean = !forceFresh && !platformOnly && completed == current

    /**
     * 启用自适应快测；各 tester 自己按 endpoint identity 做配置增量核对。
     *
     * 远程配置更新不再强制全量：新增端点由 reconcile 补测、移除端点被剔除、
     * 身份一致的沿用历史。只有用户手动“完整重新测速”（forceFresh）才全量。
     */
    @Suppress("UNUSED_PARAMETER")
    fun canFastTest(
        requested: Boolean,
        forceFresh: Boolean,
        remoteConfigUpdated: Boolean = false,
    ): Boolean = requested && !forceFresh

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
