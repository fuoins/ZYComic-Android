package com.zycomic.app.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Persisted JM measurements whose identity captures the complete endpoint and probe contract. */
internal object JmSpeedTestHistoryPolicy {
    const val API_PHASE = "api"
    const val IMAGE_PHASE = "image"
    const val RETEST_LIMIT = 5

    data class Identity(
        val phase: String,
        /** Exact request URL, including the configured API URL for API endpoints. */
        val request: String,
        val domain: String,
        val ip: String,
        val routeCondition: String,
        val probeCondition: String,
    ) {
        val endpoint: DomainIp get() = DomainIp(domain, ip)
    }

    data class Measurement(
        val identity: Identity,
        val delayMs: Long,
        val verdict: JmSpeedTester.ImgVerdict? = null,
        val measuredAtEpochMs: Long,
    )

    data class PhaseHistory(val remoteVersion: Int?, val measurements: List<Measurement> = emptyList())

    /** History is reusable only for an exact version; reconciliation requires a known, strictly newer version. */
    fun shouldReconcile(requested: Boolean, historyVersion: Int?, activeVersion: Int?): Boolean =
        requested && historyVersion != null && activeVersion != null && activeVersion > historyVersion

    /** Keep exact active identities and their original timestamp; only add measurements for newly discovered identities. */
    fun reconcile(
        previous: PhaseHistory,
        activeVersion: Int?,
        activeIdentities: Set<Identity>,
        fresh: List<Measurement>,
    ): PhaseHistory {
        val retained = previous.measurements.filter { it.identity in activeIdentities }.associateBy(Measurement::identity)
        val merged = buildMap {
            retained.forEach { (identity, measurement) -> put(identity, measurement) }
            fresh.forEach { measurement ->
                if (measurement.identity in activeIdentities && measurement.identity !in this) {
                    put(measurement.identity, measurement)
                }
            }
        }
        return PhaseHistory(activeVersion, sorted(merged.values.toList()))
    }

    /** Same-version quick runs refresh selected rows without comparing the active endpoint list. */
    fun mergeQuick(
        previous: PhaseHistory,
        activeVersion: Int?,
        fresh: List<Measurement>,
    ): PhaseHistory {
        if (previous.remoteVersion != activeVersion) return freshOnly(activeVersion, fresh)
        val freshByIdentity = fresh.associateBy(Measurement::identity)
        val merged = buildList {
            previous.measurements.forEach { old -> add(freshByIdentity[old.identity] ?: old) }
            fresh.forEach { item -> if (none { it.identity == item.identity }) add(item) }
        }
        return PhaseHistory(activeVersion, sorted(merged))
    }

    /** A complete non-reuse pass deliberately replaces the old history and ranking. */
    fun freshOnly(version: Int?, fresh: List<Measurement>): PhaseHistory = PhaseHistory(version, sorted(fresh.distinctBy(Measurement::identity)))

    fun sorted(measurements: List<Measurement>): List<Measurement> = measurements.sortedWith(
        compareBy<Measurement>(
            { it.delayMs },
            { it.identity.domain.lowercase(Locale.ROOT) },
            { it.identity.ip.lowercase(Locale.ROOT) },
            { it.identity.request },
            { it.identity.routeCondition },
            { it.identity.probeCondition },
        ),
    )

    fun selectTopFive(history: PhaseHistory): List<Measurement> = history.measurements
        .filter { it.delayMs < Long.MAX_VALUE && (it.identity.phase != IMAGE_PHASE || it.verdict == JmSpeedTester.ImgVerdict.OK) }
        .take(RETEST_LIMIT)
}

/** Histories are intentionally separate from the active speed snapshot, which config updates may invalidate. */
internal object JmSpeedTestHistoryStorage {
    private const val PREFS_NAME = "zycomic_jm_speed_test_history"
    private const val LINE_KEY = "line_history_v1"
    private const val IMAGE_KEY = "image_history_v1"

    fun loadLines(): JmSpeedTestHistoryPolicy.PhaseHistory = load(LINE_KEY, JmSpeedTestHistoryPolicy.API_PHASE)

    fun loadImages(): JmSpeedTestHistoryPolicy.PhaseHistory = load(IMAGE_KEY, JmSpeedTestHistoryPolicy.IMAGE_PHASE)

    fun saveLines(history: JmSpeedTestHistoryPolicy.PhaseHistory) = save(LINE_KEY, history, JmSpeedTestHistoryPolicy.API_PHASE)

    fun saveImages(history: JmSpeedTestHistoryPolicy.PhaseHistory) = save(IMAGE_KEY, history, JmSpeedTestHistoryPolicy.IMAGE_PHASE)

    private fun preferences() = DevConfig.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(key: String, expectedPhase: String): JmSpeedTestHistoryPolicy.PhaseHistory {
        val raw = runCatching { preferences()?.getString(key, null) }.getOrNull()
            ?: return JmSpeedTestHistoryPolicy.PhaseHistory(null)
        return runCatching {
            val root = JSONObject(raw)
            val version = if (root.isNull("remoteVersion")) null else root.getInt("remoteVersion")
            val entries = root.optJSONArray("measurements") ?: JSONArray()
            val measurements = buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val identity = JmSpeedTestHistoryPolicy.Identity(
                        phase = item.optString("phase"),
                        request = item.optString("request"),
                        domain = item.optString("domain"),
                        ip = item.optString("ip"),
                        routeCondition = item.optString("routeCondition"),
                        probeCondition = item.optString("probeCondition"),
                    )
                    val delay = item.optLong("delayMs", -1L)
                    val measuredAt = item.optLong("measuredAtEpochMs", 0L)
                    val verdict = item.optString("verdict").takeIf(String::isNotEmpty)
                        ?.let { runCatching { JmSpeedTester.ImgVerdict.valueOf(it) }.getOrNull() }
                    if (identity.phase != expectedPhase || identity.request.isBlank() || identity.domain.isBlank() ||
                        identity.ip.isBlank() || identity.routeCondition.isBlank() || identity.probeCondition.isBlank() ||
                        delay < 0L || measuredAt <= 0L ||
                        (expectedPhase == JmSpeedTestHistoryPolicy.IMAGE_PHASE && verdict == null) ||
                        (expectedPhase == JmSpeedTestHistoryPolicy.API_PHASE && item.has("verdict") && !item.isNull("verdict"))
                    ) {
                        continue
                    }
                    add(JmSpeedTestHistoryPolicy.Measurement(identity, delay, verdict, measuredAt))
                }
            }
            JmSpeedTestHistoryPolicy.PhaseHistory(version, JmSpeedTestHistoryPolicy.sorted(measurements))
        }.getOrDefault(JmSpeedTestHistoryPolicy.PhaseHistory(null))
    }

    private fun save(key: String, history: JmSpeedTestHistoryPolicy.PhaseHistory, expectedPhase: String) {
        val root = JSONObject()
            .put("remoteVersion", history.remoteVersion ?: JSONObject.NULL)
            .put(
                "measurements",
                JSONArray().apply {
                    JmSpeedTestHistoryPolicy.sorted(history.measurements).forEach { measurement ->
                        val identity = measurement.identity
                        if (identity.phase != expectedPhase) return@forEach
                        put(
                            JSONObject()
                                .put("phase", identity.phase)
                                .put("request", identity.request)
                                .put("domain", identity.domain)
                                .put("ip", identity.ip)
                                .put("routeCondition", identity.routeCondition)
                                .put("probeCondition", identity.probeCondition)
                                .put("delayMs", measurement.delayMs)
                                .put("verdict", measurement.verdict?.name ?: JSONObject.NULL)
                                .put("measuredAtEpochMs", measurement.measuredAtEpochMs),
                        )
                    }
                },
            ).toString()
        runCatching { preferences()?.edit()?.putString(key, root)?.apply() }
    }
}
