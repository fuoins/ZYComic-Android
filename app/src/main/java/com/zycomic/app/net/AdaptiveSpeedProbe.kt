package com.zycomic.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** History is a scheduling hint, never an eligible route or a synthetic probe result. */
internal object AdaptiveSpeedProbe {
    const val DAY_START_MINUTE = 6 * 60 + 20
    const val DAY_END_MINUTE = 18 * 60 + 20
    private const val DAY_BUCKET = "06:20-18:20"
    private const val NIGHT_BUCKET = "18:20-06:20"
    private const val ALL_TIMES = "all-times"

    /**
     * 完整测速的“可用”上限：首个成功端点只要不超过 2s 就先放行进入，
     * 更慢的候选不抢放行资格，交给后台继续测完后再替换。
     */
    private const val FULL_RELEASE_CEILING_MS = 2_000L

    /**
     * 单轮并发上限（快测与完整测速共用）。
     * 之前快测只有 3、完整测速放行后尾部降到 2，导致几十条线路要串行几分钟；
     * 统一为 24，让首轮就覆盖足够多候选，并且放行后仍按 24 继续测完。
     */
    private const val MAX_IN_FLIGHT = 24

    // OS DNS can ignore interruption. Daemon workers bound both caller wait and abandoned work.
    private val preparationExecutor = ThreadPoolExecutor(
        4,
        4,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(24),
        { task -> Thread(task, "adaptive-probe-preparation").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    fun timeBucket(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        val minute = local.hour * 60 + local.minute
        return if (minute in DAY_START_MINUTE until DAY_END_MINUTE) DAY_BUCKET else NIGHT_BUCKET
    }

    data class History(
        val successes: Int = 0,
        val latencyMs: Long = Long.MAX_VALUE,
        val lastMeasuredAt: Long = 0L,
        val cooldownUntil: Long = 0L,
        val failures: Int = 0,
        // Monotonic attempt-order stamp (wall-clock seeded); includes anomalies, never implies endpoint health.
        val lastAttemptAt: Long = 0L,
    )

    data class ExplorationBudget(
        val windowStartedAt: Long,
        val attempts: Int,
        val lastAttemptAt: Long,
    )

    interface Store {
        fun read(key: String): History?
        fun write(key: String, value: History)
        fun readExploration(key: String): ExplorationBudget? = null
        fun writeExploration(key: String, value: ExplorationBudget): Unit =
            throw UnsupportedOperationException("Exploration requires a persistent quota store")
    }

    class Session(
        private val networkIdentity: String,
        private val startedAtEpochMs: Long,
        private val store: Store,
        private val budgetMs: Long = 30_000L,
        private val concurrency: Int = MAX_IN_FLIGHT,
        private val fullConcurrency: Int = MAX_IN_FLIGHT,
        private val attemptTimeoutMs: Long = 6_000L,
        private val cooldownMs: Long = 60_000L,
        private val now: () -> Long = System::currentTimeMillis,
        private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
        private val zone: ZoneId = ZoneId.systemDefault(),
        private val maxCooldownMs: Long = 15 * 60_000L,
        private val explorationIntervalMs: Long = 60 * 60_000L,
        private val explorationLimit: Int = 2,
        /** 上一轮未完成时，该轮开始时间；lastAttemptAt 小于它的端点优先补测。 */
        private val carryOverCutoffAt: Long? = null,
    ) {
        private val startedTick = monotonicMs()
        private val runId = UUID.randomUUID().toString()
        private val bucket = timeBucket(startedAtEpochMs, zone)
        private val pendingExploration = mutableListOf<suspend () -> Unit>()

        /** Schedule only after publication; callbacks must not mutate foreground progress or route pools. */
        fun <T, R> deferExploration(
            scope: String,
            candidates: List<T>,
            key: (T) -> String,
            success: (R) -> Boolean,
            elapsedMs: (R) -> Long,
            sampleInvalid: (R) -> Boolean = { false },
            log: (String) -> Unit = {},
            probe: suspend (T) -> R?,
        ) {
            synchronized(pendingExploration) {
                pendingExploration += {
                    Session(
                        networkIdentity, now(), store,
                        budgetMs = attemptTimeoutMs, concurrency = 1, attemptTimeoutMs = attemptTimeoutMs,
                        cooldownMs = cooldownMs, maxCooldownMs = maxCooldownMs, now = now,
                        monotonicMs = monotonicMs, zone = zone,
                        explorationIntervalMs = explorationIntervalMs, explorationLimit = explorationLimit,
                    ).run(
                        scope, candidates, key, quick = true, success = success, elapsedMs = elapsedMs,
                        sampleInvalid = sampleInvalid, log = log, explorationOnly = true, probe = probe,
                    )
                }
            }
        }

        fun takeDeferredExploration(): List<suspend () -> Unit> = synchronized(pendingExploration) {
            pendingExploration.toList().also { pendingExploration.clear() }
        }

        private fun explorationWindow(epochMs: Long): Long {
            val local = Instant.ofEpochMilli(epochMs).atZone(zone)
            val minute = local.hour * 60 + local.minute
            val date = if (minute < DAY_START_MINUTE) local.toLocalDate().minusDays(1) else local.toLocalDate()
            val start = if (minute in DAY_START_MINUTE until DAY_END_MINUTE) DAY_START_MINUTE else DAY_END_MINUTE
            return date.atTime(start / 60, start % 60).atZone(zone).toInstant().toEpochMilli()
        }

        private fun reserveExploration(scope: String): Boolean = synchronized(store) {
            val at = now()
            val window = explorationWindow(at)
            // Separate namespace: an endpoint with an arbitrary name cannot collide with quota state.
            val key = "exploration-budget:" + historyKey(scope, "quota", timeBucket(at, zone))
            val old = store.readExploration(key)
            val count = if (old?.windowStartedAt == window) old.attempts else 0
            if (count >= explorationLimit || (old != null && (at < old.lastAttemptAt || at - old.lastAttemptAt < explorationIntervalMs))) {
                false
            } else {
                store.writeExploration(key, ExplorationBudget(window, count + 1, at))
                true
            }
        }

        init {
            require(budgetMs > 0 && concurrency > 0 && fullConcurrency > 0 && attemptTimeoutMs > 0)
            require(cooldownMs >= 0 && maxCooldownMs >= cooldownMs)
            require(explorationIntervalMs >= 0 && explorationLimit > 0)
        }

        private fun historyKey(scope: String, endpoint: String, time: String = bucket): String =
            listOf(time, networkIdentity, scope, endpoint).joinToString("") { "${it.length}:$it" }

        private fun lastAttempt(history: History?): Long =
            maxOf(history?.lastAttemptAt ?: 0L, history?.lastMeasuredAt ?: 0L)

        /** Seed all-time statistics from existing buckets without changing their identities or counting twice. */
        private fun allTimeHistory(scope: String, endpoint: String, local: History?): History? {
            store.read(historyKey(scope, endpoint, ALL_TIMES))?.let { return it }
            val other = store.read(historyKey(scope, endpoint, if (bucket == DAY_BUCKET) NIGHT_BUCKET else DAY_BUCKET))
            val samples = listOfNotNull(local, other)
            // An anomalous attempt is not a new health outcome and cannot reset the other bucket's streak.
            val latest = samples.maxByOrNull { it.lastMeasuredAt } ?: return null
            val successful = samples.filter { it.successes > 0 && it.latencyMs in 0 until Long.MAX_VALUE }
            val count = successful.sumOf { it.successes.toLong() }
            return latest.copy(
                successes = count.coerceAtMost(1_000L).toInt(),
                latencyMs = if (count > 0L) {
                    successful.sumOf { it.latencyMs.toDouble() * it.successes / count }.toLong()
                } else {
                    Long.MAX_VALUE
                },
                lastMeasuredAt = samples.maxOf { it.lastMeasuredAt },
                lastAttemptAt = samples.maxOf(::lastAttempt),
            )
        }

        private fun confidence(history: History?): Boolean =
            (history?.successes ?: 0) >= 2 && history!!.latencyMs in 0 until Long.MAX_VALUE &&
                now() - history.lastMeasuredAt in 0..86_400_000L

        /** Saturating exponential backoff: base, 2*base, 4*base ... cap; no shifts or arithmetic overflow. */
        private fun cooldownFor(failures: Int): Long {
            var duration = cooldownMs
            repeat((failures - 1).coerceIn(0, 63)) {
                if (duration >= maxCooldownMs || duration > maxCooldownMs / 2) return maxCooldownMs
                duration *= 2
            }
            return duration.coerceAtMost(maxCooldownMs)
        }

        private fun remainingBudgetMs(): Long = budgetMs - (monotonicMs() - startedTick)

        /** Preparation shares the probe deadline but is never counted as an endpoint failure. */
        suspend fun <T> prepare(quick: Boolean, block: suspend () -> T): T? {
            if (!quick) return block()
            val remaining = remainingBudgetMs()
            if (remaining <= 0L) return null
            return withTimeoutOrNull(remaining) { block() }
        }

        /** Preserve the existing resolver/transport policy without waiting on uninterruptible DNS. */
        suspend fun <T> prepareBlocking(quick: Boolean, block: () -> T): T? = prepare(quick) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val future = preparationExecutor.submit {
                        try {
                            val result = block()
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                    continuation.invokeOnCancellation { future.cancel(true) }
                } catch (_: RejectedExecutionException) {
                    continuation.resumeWithException(IllegalStateException("Probe preparation queue is full"))
                }
            }
        }

        suspend fun <T, R> run(
            scope: String,
            candidates: List<T>,
            key: (T) -> String,
            quick: Boolean,
            success: (R) -> Boolean,
            elapsedMs: (R) -> Long,
            sampleInvalid: (R) -> Boolean = { false },
            log: (String) -> Unit = {},
            preferredKeys: Set<String> = emptySet(),
            independenceKey: (T) -> String = key,
            explorationOnly: Boolean = false,
            onUsable: (suspend (T, R) -> Unit)? = null,
            onQualified: (suspend (T, R) -> Unit)? = null,
            probe: suspend (T) -> R?,
        ): List<Pair<T, R>> = coroutineScope {
            data class Candidate<T>(
                val target: T,
                val endpoint: String,
                val history: History?,
                val allTimes: History?,
                val ranking: History?,
                val confidence: Int,
                val index: Int,
                val tier: Int,
                val carryOver: Boolean,
            )
            fun emit(text: String) = log(
                "run=$runId; startedAt=$startedAtEpochMs; network=$networkIdentity; bucket=$bucket; " +
                    "scope=$scope; mode=${if (quick) "adaptive-quick" else "full"}; $text",
            )
            val ordered = candidates.distinctBy(key).mapIndexed { index, target ->
                val endpoint = key(target)
                val local = store.read(historyKey(scope, endpoint))
                val allTimes = allTimeHistory(scope, endpoint, local)
                val level = when {
                    confidence(local) -> 0
                    confidence(allTimes) -> 1
                    else -> 2
                }
                // 上一轮没测完的端点：即使没有任何历史，也排到“近期可靠”这一档，配合排序键优先补测。
                val carryOver = carryOverCutoffAt != null && lastAttempt(allTimes) < carryOverCutoffAt
                val tier = when {
                    endpoint in preferredKeys -> 0
                    carryOver -> 1
                    (allTimes?.failures ?: 0) > 0 -> 4
                    level < 2 -> 1
                    (allTimes?.successes ?: 0) > 0 -> 2
                    else -> 3
                }
                Candidate(
                    target, endpoint, local, allTimes, if (level == 0) local else allTimes, level, index, tier, carryOver,
                )
            }.sortedWith(
                compareBy<Candidate<T>>(
                    { if (quick && (it.allTimes?.cooldownUntil ?: 0L) > now()) 1 else 0 },
                    { if (quick) it.tier else 0 },
                    // 同一档内先补上轮没测过的，再按历史置信度/延迟。
                    { if (quick && it.carryOver) 0 else 1 },
                    { if (quick) it.confidence else 0 },
                    { if (quick && it.confidence < 2) it.ranking?.latencyMs else Long.MAX_VALUE },
                    { it.index },
                ),
            ).toMutableList()
            fun eligible(candidate: Candidate<T>) = (candidate.allTimes?.cooldownUntil ?: 0L) <= now()
            // Use recent successful history even when it is strong; confidence controls ranking, not baseline eligibility.
            // With no same-scope history, two valid current results are enough; never invent a global 1.5s ceiling.
            val baseline = ordered.asSequence()
                .mapNotNull { it.allTimes }
                .filter {
                    it.successes > 0 && it.failures == 0 && it.latencyMs in 0 until Long.MAX_VALUE &&
                        now() - it.lastMeasuredAt in 0..86_400_000L
                }
                .map { it.latencyMs }
                .sorted()
                .take(2)
                .maxOrNull()
            val fastThresholdMs = baseline?.let { maxOf(200L, it.coerceAtMost(Long.MAX_VALUE / 2) * 2) } ?: Long.MAX_VALUE
            // 完整测速不追求“最快”，只要求“能用”：沿用快测阈值会让弱网下第一个能打开的线路
            // 也判为不合格，只能等 30s 兜底才放行。这里给完整测速一个更宽的放行阈值（上限 2s），
            // 先进入，再在后台用本轮全量结果替换成真正最快的端点。
            val releaseThresholdMs = if (!quick && baseline == null) {
                // 首次没有历史：用固定 2s 作为“可用”标准，既不把 4~5s 的慢节点放进来，
                // 也不会像快测那样对任何成功都放行。
                FULL_RELEASE_CEILING_MS
            } else {
                fastThresholdMs
            }
            if (explorationOnly) {
                // One low-priority peer, outside the recent reliable pool; no cooldown waiting.
                val target = ordered.filter { eligible(it) && it.endpoint !in preferredKeys && it.tier >= 2 }
                    .minWithOrNull(compareBy<Candidate<T>>({ lastAttempt(it.allTimes) }, { it.index }))
                ordered.clear()
                if (target != null && remainingBudgetMs() > 0L && reserveExploration(scope)) ordered += target
            }
            emit("candidates=${ordered.size}; history=ordering-only; selection=current-round-valid-only")
            ordered.forEach {
                emit(
                    "candidate=${it.endpoint}; historicalSamples=${it.history?.successes ?: 0}; " +
                        "historySource=${when (it.confidence) {
                            0 -> "current-bucket"
                            1 -> "all-times-low-confidence"
                            else -> "configured-order"
                        }}; " +
                        "allTimeSamples=${it.allTimes?.successes ?: 0}; lastAttemptAt=${lastAttempt(it.allTimes)}; " +
                        "consecutiveFailures=${it.allTimes?.failures ?: 0}; cooldownUntil=${it.allTimes?.cooldownUntil ?: 0L}",
                )
            }
            emit(
                "exploration=optional-deferred; explorationOnly=$explorationOnly; baselineMs=$baseline; " +
                    "fastThresholdMs=$fastThresholdMs; releaseThresholdMs=$releaseThresholdMs",
            )
            val guard = Any()
            val results = mutableListOf<Pair<T, R>>()
            var bestUsable: Pair<T, R>? = null
            var publishedElapsed = Long.MAX_VALUE
            var foregroundReleased = false
            val publishLock = Mutex()
            var latestAttemptStamp = ordered.maxOfOrNull { lastAttempt(it.allTimes) } ?: 0L
            var good = 0
            var dispatched = 0
            var completed = 0
            var inFlight = 0
            val usedGroups = mutableSetOf<String>()
            var changed = CompletableDeferred<Unit>()
            fun remaining(): Long = remainingBudgetMs()
            suspend fun publishBest(force: Boolean) {
                val callback = onUsable ?: return
                publishLock.withLock {
                    val best = synchronized(guard) { bestUsable }
                    if (best != null) {
                        val elapsed = elapsedMs(best.second).coerceAtLeast(0L)
                        if (elapsed < publishedElapsed &&
                            (force || elapsed <= releaseThresholdMs || remaining() <= 0L)
                        ) {
                            publishedElapsed = elapsed
                            synchronized(guard) { foregroundReleased = true }
                            callback(best.first, best.second)
                        }
                    }
                }
            }
            val stageTimer = if (onUsable != null) {
                launch {
                    delay(remaining().coerceAtLeast(0L))
                    publishBest(force = true)
                    synchronized(guard) {
                        changed.complete(Unit)
                        changed = CompletableDeferred()
                    }
                }
            } else {
                null
            }
            val workers = List(minOf(if (quick) concurrency else fullConcurrency, ordered.size)) {
                launch {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        var waitMs = 0L
                        var waitFor: CompletableDeferred<Unit>? = null
                        val candidate = synchronized(guard) {
                            if (ordered.isEmpty() || (onUsable == null && quick && (remaining() <= 0L || good >= 2))) {
                                null
                            } else if (
                                onUsable != null &&
                                (foregroundReleased || remaining() <= 0L) &&
                                inFlight >= (if (quick) concurrency else fullConcurrency)
                            ) {
                                // Foreground attempts finish, then a bounded tail keeps draining the rest:
                                // quick stays strictly serial (its budget is already tight), while a full
                                // pass keeps two probes in flight so replacement is not delayed by minutes.
                                waitFor = changed
                                null
                            } else {
                                val next = if (!quick) {
                                    0
                                } else {
                                    val first = ordered.indexOfFirst(::eligible)
                                    if (first < 0 || explorationOnly) {
                                        first
                                    } else {
                                        ordered.indexOfFirst {
                                            eligible(it) && it.tier == ordered[first].tier && independenceKey(it.target) !in usedGroups
                                        }.takeIf { it >= 0 } ?: first
                                    }
                                }
                                if (next >= 0) {
                                    dispatched++
                                    inFlight++
                                    ordered.removeAt(next).also { usedGroups += independenceKey(it.target) }
                                } else if (!explorationOnly && inFlight == 0) {
                                    // Legacy recovery defers cooled peers at the budget; two-stage runs drain them.
                                    waitMs = (ordered.minOf { it.allTimes?.cooldownUntil ?: 0L } - now()).coerceAtLeast(1L)
                                    null
                                } else {
                                    if (inFlight > 0) waitFor = changed
                                    null
                                }
                            }
                        }
                        if (candidate == null) {
                            if (waitFor != null) {
                                if (onUsable != null) {
                                    waitFor!!.await()
                                } else {
                                    withTimeoutOrNull(remaining().coerceAtLeast(1L)) { waitFor!!.await() }
                                }
                                continue
                            }
                            if (waitMs > 0L && (onUsable != null || remaining() > waitMs)) {
                                emit("recovery=cooldown-wait; waitMs=$waitMs")
                                delay(waitMs)
                                continue
                            }
                            break
                        }
                        val reason = when {
                            explorationOnly -> "low-frequency-exploration"
                            (candidate.allTimes?.failures ?: 0) > 0 -> "recovery-after-cooldown"
                            candidate.endpoint in preferredKeys -> "current-in-use"
                            candidate.carryOver -> "carry-over-unfinished-round"
                            candidate.tier == 1 -> "recent-reliable"
                            else -> "pool-insufficient-or-slow"
                        }
                        emit("probe=${candidate.endpoint}; reason=$reason; source=current-round")
                        var timedOut = false
                        var exceptionName: String? = null
                        val result = try {
                            if (quick && onUsable == null) {
                                val timeout = minOf(attemptTimeoutMs, remaining()).coerceAtLeast(1L)
                                withTimeoutOrNull(timeout) {
                                    // A null result means an invalidated run; do not count it as failure.
                                    probe(candidate.target) to true
                                }.also { if (it == null) timedOut = true }?.first
                            } else if (onUsable != null && quick) {
                                withTimeoutOrNull(attemptTimeoutMs) {
                                    probe(candidate.target) to true
                                }.also { if (it == null) timedOut = true }?.first
                            } else {
                                probe(candidate.target)
                            }
                        } catch (cancelled: CancellationException) {
                            emit("probe=${candidate.endpoint}; cancelled=true; failureRecorded=false")
                            throw cancelled
                        } catch (error: Exception) {
                            exceptionName = error.javaClass.simpleName
                            null
                        }
                        currentCoroutineContext().ensureActive()
                        val measuredAt = now()
                        // A coarse or backwards wall clock must not continually select the same exploration peer.
                        val attemptStamp = synchronized(guard) {
                            maxOf(measuredAt, if (latestAttemptStamp < Long.MAX_VALUE) latestAttemptStamp + 1L else Long.MAX_VALUE)
                                .also { latestAttemptStamp = it }
                        }
                        val old = candidate.history ?: History()
                        val oldAllTimes = candidate.allTimes ?: History()
                        val validSample = result != null && !sampleInvalid(result)
                        val ok = validSample && success(result!!)
                        val endpointFailure = !ok && (validSample || timedOut || exceptionName != null)
                        var failures = oldAllTimes.failures
                        var backoff = 0L
                        var cooldownUntil = oldAllTimes.cooldownUntil
                        if (result != null || timedOut || exceptionName != null) {
                            // Serialize the pair's read/modify/write across sessions sharing this store.
                            // A candidate's scheduling snapshot is not authoritative when a probe completes.
                            synchronized(store) {
                                val latestLocal = store.read(historyKey(scope, candidate.endpoint)) ?: old
                                val latestAllTimes = allTimeHistory(scope, candidate.endpoint, latestLocal) ?: History()
                                failures = when {
                                    ok -> 0
                                    endpointFailure -> latestAllTimes.failures.coerceIn(0, 999) + 1
                                    else -> latestAllTimes.failures
                                }
                                backoff = if (endpointFailure) cooldownFor(failures) else 0L
                                cooldownUntil = when {
                                    ok -> 0L
                                    !endpointFailure -> latestAllTimes.cooldownUntil
                                    measuredAt > Long.MAX_VALUE - backoff -> Long.MAX_VALUE
                                    else -> measuredAt + backoff
                                }
                                val previousAttempt = lastAttempt(latestAllTimes)
                                val stamp = maxOf(attemptStamp, if (previousAttempt < Long.MAX_VALUE) previousAttempt + 1L else Long.MAX_VALUE)
                                fun update(history: History): History = when {
                                    ok -> {
                                        val measured = elapsedMs(result!!).coerceAtLeast(0L)
                                        history.copy(
                                            successes = history.successes.coerceIn(0, 999) + 1,
                                            latencyMs = if (history.successes > 0 && history.latencyMs < Long.MAX_VALUE) history.latencyMs + (measured - history.latencyMs) / 2 else measured,
                                            lastMeasuredAt = measuredAt,
                                            lastAttemptAt = stamp,
                                            cooldownUntil = 0L,
                                            failures = 0,
                                        )
                                    }
                                    endpointFailure -> history.copy(
                                        lastMeasuredAt = measuredAt,
                                        lastAttemptAt = stamp,
                                        cooldownUntil = cooldownUntil,
                                        failures = failures,
                                    )
                                    // Copy unchanged global health state, but do not count an anomalous sample.
                                    else -> history.copy(lastAttemptAt = stamp, failures = failures, cooldownUntil = cooldownUntil)
                                }
                                store.write(historyKey(scope, candidate.endpoint), update(latestLocal))
                                store.write(historyKey(scope, candidate.endpoint, ALL_TIMES), update(latestAllTimes))
                            }
                        }
                        synchronized(guard) {
                            completed++
                            inFlight--
                            if (result != null) results += candidate.target to result
                            if (ok && elapsedMs(result!!) in 0L..fastThresholdMs) good++
                            if (ok) {
                                val currentBest = bestUsable
                                if (currentBest == null || elapsedMs(result!!) < elapsedMs(currentBest.second)) {
                                    bestUsable = candidate.target to result!!
                                }
                            }
                            changed.complete(Unit)
                            changed = CompletableDeferred()
                        }
                        if (ok && onUsable != null) publishBest(force = elapsedMs(result!!) <= releaseThresholdMs)
                        if (ok && elapsedMs(result!!) <= releaseThresholdMs) {
                            // Qualification is distinct from forced/deadline usability publication.
                            // This executes only for this real, successful in-round sample and only
                            // after any onUsable route-application callback has returned.
                            onQualified?.invoke(candidate.target, result!!)
                        }
                        emit(
                            "probe=${candidate.endpoint}; result=${when {
                                timedOut -> "timeout"
                                exceptionName != null -> "exception-$exceptionName"
                                result == null -> "invalidated-not-failure"
                                !validSample -> "sample-anomaly-not-endpoint-failure"
                                ok -> "valid-success"
                                else -> "endpoint-failure"
                            }}; elapsedMs=${result?.let(elapsedMs)}; consecutiveFailures=$failures; " +
                                "cooldown=$endpointFailure; cooldownMs=$backoff; cooldownUntil=$cooldownUntil; " +
                                "exploration=${if (ok && elapsedMs(result!!) <= releaseThresholdMs) "completion-refill" else "slow-or-failed-continue"}",
                        )
                    }
                }
            }
            workers.forEach { it.join() }
            if (onUsable != null) {
                // If all candidates finish before the deadline, there is no later result to wait for.
                publishBest(force = true)
                stageTimer?.cancelAndJoin()
            }
            val valid = results.filter { !sampleInvalid(it.second) && success(it.second) }
            val selected = valid.minByOrNull { elapsedMs(it.second) }
            emit(
                "dispatched=$dispatched; completed=$completed; untested=${ordered.size}; explorationOnly=$explorationOnly; stop=${when {
                    onUsable != null -> "two-stage-complete"
                    !quick -> "full-complete"
                    remaining() <= 0L -> "run-budget"
                    good >= 2 -> "enough-current-successes"
                    ordered.isNotEmpty() -> "cooldown-deferred-to-next-run"
                    else -> "pool-exhausted"
                }}; finalBasis=current-round-valid-only; selectedCandidate=${selected?.first?.let(key)}; historyApplied=false",
            )
            results.toList()
        }
    }
}
