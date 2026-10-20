package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** JM endpoint probes participate in the same fair platform scheduler and shared cap. */
internal class JmEndpointProbeRunner {
    suspend fun <T, R> run(
        targets: List<T>,
        probe: suspend (T) -> R,
        onStarted: (T) -> Unit = {},
        onResult: (T, R) -> Unit = { _, _ -> },
    ): List<Pair<T, R>> = coroutineScope {
        targets.map { target ->
            async {
                SpeedTestProbeBudget.scheduler.withPermit(
                    ContentPlatform.JM,
                    { SpeedTestTimingLog.recordQueueWait(SpeedTestTimingLog.Scope.JM, it) },
                ) {
                    onStarted(target)
                    val result = probe(target)
                    onResult(target, result)
                    target to result
                }
            }
        }.awaitAll()
    }
}

data class JmSpeedTestProgress(
    val phase: Phase = Phase.PREPARING_LINES,
    val completed: Int = 0,
    val total: Int = 0,
    val successful: Int = 0,
    val failed: Int = 0,
    // Per-endpoint details remain available to diagnostics; never include them in ordinary progress text.
    val endpoint: String? = null,
    val endpointSuccessful: Boolean? = null,
    val endpointElapsedMs: Long? = null,
    val elapsedMs: Long = 0L,
) {
    enum class Phase { PREPARING_LINES, TESTING_LINES, PREPARING_IMAGES, TESTING_IMAGES, REUSING_IMAGES, COMPLETE, FAILED }

    fun message(): String {
        val stage = when (phase) {
            Phase.PREPARING_LINES -> "解析线路 IP"
            Phase.TESTING_LINES -> "线路测速"
            Phase.PREPARING_IMAGES -> "解析图源 IP"
            Phase.TESTING_IMAGES -> "图源测速"
            Phase.REUSING_IMAGES -> "复用图源结果"
            Phase.COMPLETE -> "测速完成"
            Phase.FAILED -> "测速未完成"
        }
        return buildString {
            append("禁漫天堂 · $stage $completed/$total · 成功 $successful / 失败 $failed · 已用 ${elapsedMs / 1000}秒")
        }
    }
}
