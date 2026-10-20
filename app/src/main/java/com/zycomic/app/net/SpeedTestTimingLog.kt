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

    private fun toJson(durations: Map<SpeedTestTimings.Phase, Long>) = JSONObject().apply {
        durations.forEach { (phase, elapsedMs) -> put(phase.name, elapsedMs) }
    }
}
