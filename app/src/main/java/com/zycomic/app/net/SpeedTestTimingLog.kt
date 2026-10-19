package com.zycomic.app.net

import android.content.Context
import org.json.JSONObject

/** Only fixed phase names and elapsed milliseconds enter this diagnostic path. */
internal object SpeedTestTimingLog {
    enum class Scope { STARTUP, CONFIG, MANWA, JM }

    private val latest = mutableMapOf<Scope, Map<SpeedTestTimings.Phase, Long>>()

    fun start(scope: Scope) = SpeedTestTimings { durations ->
        synchronized(this) {
            latest[scope] = durations.toMap()
            val json = JSONObject().apply {
                latest.forEach { (key, value) -> put(key.name, toJson(value)) }
            }
            DevConfig.appContext?.getSharedPreferences("zycomic_platform_speed_test", Context.MODE_PRIVATE)?.edit()
                ?.putString("last_phase_timings", json.toString())?.apply()
        }
        android.util.Log.i("PlatformSpeedTest", "timings ${scope.name} ${toJson(durations)}")
    }

    @Synchronized
    fun json(scope: Scope): JSONObject = toJson(latest[scope].orEmpty())

    private fun toJson(durations: Map<SpeedTestTimings.Phase, Long>) = JSONObject().apply {
        durations.forEach { (phase, elapsedMs) -> put(phase.name, elapsedMs) }
    }
}
