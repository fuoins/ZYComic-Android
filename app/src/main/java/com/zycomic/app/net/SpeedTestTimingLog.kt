package com.zycomic.app.net

import android.content.Context
import org.json.JSONObject

/** Only fixed phase names and elapsed milliseconds enter this diagnostic path. */
internal object SpeedTestTimingLog {
    enum class Scope { STARTUP, CONFIG, MANWA, PICACG, JM }

    private val latest = mutableMapOf<Scope, Map<SpeedTestTimings.Phase, Long>>()
    private val queueWaitMs = mutableMapOf<Scope, Long>()

    fun start(scope: Scope): SpeedTestTimings {
        synchronized(this) { queueWaitMs[scope] = 0L }
        return SpeedTestTimings { durations ->
            val reported = synchronized(this) {
                val withQueueWait = durations.toMap() +
                    (SpeedTestTimings.Phase.PROBE_QUEUE_WAIT to (queueWaitMs[scope] ?: 0L))
                latest[scope] = withQueueWait
                val json = JSONObject().apply {
                    latest.forEach { (key, value) -> put(key.name, toJson(value)) }
                }
                DevConfig.appContext?.getSharedPreferences("zycomic_platform_speed_test", Context.MODE_PRIVATE)?.edit()
                    ?.putString("last_phase_timings", json.toString())?.apply()
                withQueueWait
            }
            android.util.Log.i("PlatformSpeedTest", "timings ${scope.name} ${toJson(reported)}")
        }
    }

    /** Queue delay is accumulated separately from each endpoint's network probe duration. */
    @Synchronized
    fun recordQueueWait(scope: Scope, elapsedMs: Long) {
        if (elapsedMs > 0L) queueWaitMs[scope] = (queueWaitMs[scope] ?: 0L) + elapsedMs
    }

    @Synchronized
    fun json(scope: Scope): JSONObject = toJson(latest[scope].orEmpty())

    /**
     * 最近一轮各阶段耗时的可读摘要（内存态）。用于“首次启动慢在哪”的定位：
     * 只展示真实采到的阶段，不推算、不把重叠阶段相加。
     */
    @Synchronized
    fun summary(): String {
        val scopes = listOf(
            Scope.STARTUP to "启动",
            Scope.MANWA to "漫蛙",
            Scope.JM to "禁漫",
            Scope.PICACG to "哔咔",
        )
        val phaseLabels = listOf(
            SpeedTestTimings.Phase.TOTAL to "总",
            SpeedTestTimings.Phase.CONFIG_CHECK to "配置检查",
            SpeedTestTimings.Phase.LINE_PREPARATION to "线路准备",
            SpeedTestTimings.Phase.LINES to "测线路",
            SpeedTestTimings.Phase.CONFIG_WAIT to "等配置",
            SpeedTestTimings.Phase.IMAGE_PREPARATION to "图源准备",
            SpeedTestTimings.Phase.IMAGES to "测图源",
            SpeedTestTimings.Phase.PICACG_LINES to "API",
            SpeedTestTimings.Phase.PICACG_IMAGES to "图源",
            SpeedTestTimings.Phase.PROBE_QUEUE_WAIT to "排队",
        )
        return scopes.mapNotNull { (scope, label) ->
            val data = latest[scope].orEmpty()
            val parts = phaseLabels.mapNotNull { (phase, phaseLabel) ->
                data[phase]?.takeIf { it > 0L }?.let { "$phaseLabel ${it}ms" }
            }
            if (parts.isEmpty()) null else "$label：" + parts.joinToString(" · ")
        }.joinToString("\n")
    }

    private fun toJson(durations: Map<SpeedTestTimings.Phase, Long>) = JSONObject().apply {
        durations.forEach { (phase, elapsedMs) -> put(phase.name, elapsedMs) }
    }
}
