package com.zycomic.app.net

/** Process-local generation identity; never log the network identity or account data. */
data class JmSpeedTestRunIdentity(
    val runId: String,
    val platformRevision: Long,
    val configRevision: Long,
    val remoteVersion: Int?,
    val configFingerprint: String,
    val imageConfigFingerprint: String,
    val imageNetworkPolicy: String,
    val networkIdentity: String,
)

/** Completion means both the active snapshot and separated histories were accepted. */
data class JmSpeedTestSubmission(
    val identity: JmSpeedTestRunIdentity,
    val state: State,
    val snapshotSubmitted: Boolean = false,
    val historiesSaved: Boolean = false,
) {
    enum class State(val reason: String) {
        COMPLETE("结果与历史已完整提交"),
        CONFIG_WAIT_REJECTED("提交前配置检查未通过"),
        STALE_GENERATION("运行代次、配置或网络已变化"),
        SNAPSHOT_REJECTED("测速快照提交被拒绝或保存失败"),
        HISTORY_REJECTED("测速历史保存失败或被拒绝"),
        PUBLISHED_RUN_CHANGED("已发布结果不属于本轮"),
        CANCELLED("测速已取消"),
        FAILED("测速执行异常"),
    }

    fun isComplete(publishedRunId: String?, currentIdentity: JmSpeedTestRunIdentity?): Boolean =
        state == State.COMPLETE && snapshotSubmitted && historiesSaved &&
            publishedRunId == identity.runId && currentIdentity == identity

    companion object {
        /** Callbacks keep production submission ordering testable without Android or any probes. */
        fun commit(
            identity: JmSpeedTestRunIdentity,
            beforeApplyAccepted: Boolean,
            isCurrent: () -> Boolean,
            submitSnapshot: () -> Boolean,
            saveHistories: () -> Boolean,
            publishedRunId: () -> String?,
        ): JmSpeedTestSubmission {
            if (!beforeApplyAccepted) return JmSpeedTestSubmission(identity, State.CONFIG_WAIT_REJECTED)
            if (!isCurrent()) return JmSpeedTestSubmission(identity, State.STALE_GENERATION)
            if (!submitSnapshot()) {
                return JmSpeedTestSubmission(identity, if (isCurrent()) State.SNAPSHOT_REJECTED else State.STALE_GENERATION)
            }
            if (!isCurrent()) return JmSpeedTestSubmission(identity, State.STALE_GENERATION, snapshotSubmitted = true)
            if (!saveHistories()) {
                return JmSpeedTestSubmission(identity, if (isCurrent()) State.HISTORY_REJECTED else State.STALE_GENERATION, snapshotSubmitted = true)
            }
            val state = when {
                !isCurrent() -> State.STALE_GENERATION
                publishedRunId() != identity.runId -> State.PUBLISHED_RUN_CHANGED
                else -> State.COMPLETE
            }
            return JmSpeedTestSubmission(identity, state, snapshotSubmitted = true, historiesSaved = true)
        }
    }
}

/** Manual retests must use the returned platform report, not merely the absence of an exception. */
fun jmSpeedTestIsComplete(status: String?): Boolean = status == "tested"
