package com.zycomic.app.net

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest

/** Independent scheduling history: never written into the current healthy route pools. */
internal object AdaptiveSpeedHistory : AdaptiveSpeedProbe.Store {
    private const val PREFS = "adaptive_speed_probe_history_v1"
    private const val MAX_ENTRIES = 1_024
    private val lock = Any()
    private val explorationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val explorationLock = Any()
    private val explorationJobs = mutableSetOf<Job>()
    private val explorationMutex = Mutex()
    private fun preferences() = DevConfig.appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun digest(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 上一轮未完成时，它的开始时间；本轮所有 session 共享同一个补测截止点。 */
    @Volatile
    private var carryOverCutoffAt: Long? = null

    /**
     * 由协调器在整轮开始时调用一次：先读上一轮是否未完成，再落盘本轮开始时间。
     * 同一轮里多个平台各自 session() 时不会重置轮次起点。
     */
    fun prepareRound(
        networkIdentity: String,
        configRevision: Long,
        startedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        carryOverCutoffAt = SpeedTestRoundState.pendingCutoff(configRevision, networkIdentity)
        SpeedTestRoundState.begin(startedAtEpochMs, configRevision, networkIdentity)
    }

    fun session(networkIdentity: String, startedAtEpochMs: Long = System.currentTimeMillis()): AdaptiveSpeedProbe.Session {
        // Foreground startup/recovery always preempts low-priority work, without waiting for it.
        synchronized(explorationLock) {
            explorationJobs.toList().forEach { it.cancel() }
            explorationJobs.clear()
        }
        return AdaptiveSpeedProbe.Session(
            networkIdentity = networkIdentity,
            startedAtEpochMs = startedAtEpochMs,
            store = this,
            carryOverCutoffAt = carryOverCutoffAt,
        )
    }

    /** Fire only after foreground publication; never join this work from startup/readiness. */
    fun launchExploration(session: AdaptiveSpeedProbe.Session) {
        val tasks = session.takeDeferredExploration()
        if (tasks.isEmpty()) return
        val job = explorationScope.launch(start = CoroutineStart.LAZY) {
            explorationMutex.withLock {
                for (task in tasks) {
                    try {
                        task()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Best-effort scheduling hints must never invalidate the foreground result.
                    }
                }
            }
        }
        synchronized(explorationLock) { explorationJobs += job }
        job.invokeOnCompletion { synchronized(explorationLock) { explorationJobs -= job } }
        job.start()
    }

    override fun readExploration(key: String): AdaptiveSpeedProbe.ExplorationBudget? = synchronized(lock) {
        runCatching {
            val json = JSONObject(preferences()?.getString(digest(key), null) ?: return@synchronized null)
            AdaptiveSpeedProbe.ExplorationBudget(
                windowStartedAt = json.getLong("windowStartedAt"),
                attempts = json.getInt("attempts").coerceAtLeast(0),
                lastAttemptAt = json.getLong("lastAttemptAt"),
            )
        }.getOrNull()
    }

    override fun writeExploration(key: String, value: AdaptiveSpeedProbe.ExplorationBudget) = synchronized(lock) {
        val preferences = checkNotNull(preferences()) { "Exploration quota storage unavailable" }
        // Reserve before probing, including timeouts/cancellation, so a restart cannot reset the quota.
        check(
            preferences.edit().putString(
                digest(key),
                JSONObject().apply {
                    put("windowStartedAt", value.windowStartedAt)
                    put("attempts", value.attempts)
                    put("lastAttemptAt", value.lastAttemptAt)
                    put("lastMeasuredAt", value.lastAttemptAt)
                }.toString(),
            ).commit(),
        ) { "Unable to persist exploration quota" }
    }

    override fun read(key: String): AdaptiveSpeedProbe.History? = synchronized(lock) {
        runCatching {
            val encoded = preferences()?.getString(digest(key), null) ?: return@synchronized null
            val json = JSONObject(encoded)
            AdaptiveSpeedProbe.History(
                successes = json.optInt("successes", 0).coerceIn(0, 1_000),
                latencyMs = json.optLong("latencyMs", Long.MAX_VALUE).coerceAtLeast(0L),
                lastMeasuredAt = json.optLong("lastMeasuredAt", 0L),
                cooldownUntil = json.optLong("cooldownUntil", 0L),
                failures = json.optInt("failures", 0).coerceIn(0, 1_000),
                lastAttemptAt = json.optLong("lastAttemptAt", json.optLong("lastMeasuredAt", 0L)),
            )
        }.getOrNull()
    }

    override fun write(key: String, value: AdaptiveSpeedProbe.History) = synchronized(lock) {
        val preferences = preferences() ?: return@synchronized
        val encodedKey = digest(key)
        val editor = preferences.edit()
        if (!preferences.contains(encodedKey) && preferences.all.size >= MAX_ENTRIES) {
            // Quotas must survive a large endpoint pool; only evict endpoint scheduling history.
            val oldest = preferences.all.entries.filterNot { entry ->
                runCatching { JSONObject(entry.value as? String ?: "{}").has("windowStartedAt") }.getOrDefault(false)
            }.minByOrNull { entry ->
                runCatching { JSONObject(entry.value as? String ?: "{}").optLong("lastMeasuredAt", 0L) }.getOrDefault(0L)
            }
            oldest?.key?.let(editor::remove)
        }
        editor.putString(
            encodedKey,
            JSONObject().apply {
                put("successes", value.successes)
                put("latencyMs", value.latencyMs)
                put("lastMeasuredAt", value.lastMeasuredAt)
                put("cooldownUntil", value.cooldownUntil)
                put("failures", value.failures)
                put("lastAttemptAt", value.lastAttemptAt)
            }.toString(),
        ).apply()
    }
}

/**
 * 跨进程记录“上一轮测速有没有测完”。
 *
 * 只有三个用途：轮开始时落盘开始时间（进程被杀也能判定未完成）、整轮完整完成时标记 completed、
 * 下一次启动用 [pendingCutoff] 找出“上一轮没碰过”的端点交给快测优先补。
 * 配置代或网络身份变化后旧记录直接作废，避免拿过期候选补测。
 */
internal object SpeedTestRoundState {
    private const val ROUND_PREFS = "zycomic_speed_round_state"
    private const val KEY_STARTED_AT = "round_started_at"
    private const val KEY_COMPLETED = "round_completed"
    private const val KEY_CONFIG_REVISION = "round_config_revision"
    private const val KEY_NETWORK = "round_network"

    private fun prefs(): android.content.SharedPreferences? =
        DevConfig.appContext?.getSharedPreferences(ROUND_PREFS, Context.MODE_PRIVATE)

    fun begin(now: Long, configRevision: Long, networkIdentity: String) {
        // 只使用字符串写入：离线测试的 Android stub 不一定提供 putLong/putBoolean。
        prefs()?.edit()
            ?.putString(KEY_STARTED_AT, now.toString())
            ?.putString(KEY_COMPLETED, "false")
            ?.putString(KEY_CONFIG_REVISION, configRevision.toString())
            ?.putString(KEY_NETWORK, networkIdentity)
            ?.commit()
    }

    fun complete() {
        prefs()?.edit()?.putString(KEY_COMPLETED, "true")?.apply()
    }

    /**
     * 上一轮未完成且配置/网络未变时，返回它的开始时间；
     * 端点的 lastAttemptAt 小于该值即“上轮没测过”。
     */
    fun pendingCutoff(configRevision: Long, networkIdentity: String): Long? {
        val p = prefs() ?: return null
        if (p.getString(KEY_COMPLETED, "true") == "true") return null
        if (p.getString(KEY_CONFIG_REVISION, null)?.toLongOrNull() != configRevision) return null
        if (p.getString(KEY_NETWORK, null) != networkIdentity) return null
        return p.getString(KEY_STARTED_AT, null)?.toLongOrNull()?.takeIf { it > 0L }
    }
}
