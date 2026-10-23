package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AdaptiveSpeedProbeTest {
    private class MemoryStore : AdaptiveSpeedProbe.Store {
        val values = ConcurrentHashMap<String, AdaptiveSpeedProbe.History>()
        val writes = ConcurrentHashMap<String, AdaptiveSpeedProbe.History>()
        val budgets = ConcurrentHashMap<String, AdaptiveSpeedProbe.ExplorationBudget>()

        override fun readExploration(key: String) = budgets[key]

        override fun writeExploration(key: String, value: AdaptiveSpeedProbe.ExplorationBudget) {
            budgets[key] = value
        }

        override fun read(key: String): AdaptiveSpeedProbe.History? = values[key]

        override fun write(key: String, value: AdaptiveSpeedProbe.History) {
            values[key] = value
            writes[key] = value
        }
    }

    private fun session(
        store: MemoryStore = MemoryStore(),
        network: String = "wifi",
        startedAt: Long = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli(),
        now: () -> Long = { 1_000_000L },
        monotonic: () -> Long = { System.nanoTime() / 1_000_000L },
        budgetMs: Long = 5_000L,
        concurrency: Int = 1,
        timeoutMs: Long = 500L,
        cooldownMs: Long = 100L,
        maxCooldownMs: Long = 15 * 60_000L,
        zone: ZoneId = ZoneId.of("UTC"),
    ) = AdaptiveSpeedProbe.Session(
        networkIdentity = network,
        startedAtEpochMs = startedAt,
        store = store,
        budgetMs = budgetMs,
        concurrency = concurrency,
        attemptTimeoutMs = timeoutMs,
        cooldownMs = cooldownMs,
        maxCooldownMs = maxCooldownMs,
        now = now,
        monotonicMs = monotonic,
        zone = zone,
    )

    private fun historyKey(bucket: String, network: String, scope: String, endpoint: String): String =
        listOf(bucket, network, scope, endpoint).joinToString("") { "${it.length}:$it" }

    private fun aggregateKey(network: String, scope: String, endpoint: String): String =
        historyKey("all-times", network, scope, endpoint)

    @Test
    fun `time bucket honors local boundaries and midnight`() {
        val zone = ZoneId.of("America/New_York")
        fun bucket(time: String) = AdaptiveSpeedProbe.timeBucket(Instant.parse(time).toEpochMilli(), zone)

        assertEquals("18:20-06:20", bucket("2025-01-15T11:19:00Z")) // 06:19 local
        assertEquals("06:20-18:20", bucket("2025-01-15T11:20:00Z")) // 06:20 local
        assertEquals("06:20-18:20", bucket("2025-01-15T23:19:00Z")) // 18:19 local
        assertEquals("18:20-06:20", bucket("2025-01-15T23:20:00Z")) // 18:20 local
        assertEquals("18:20-06:20", bucket("2025-01-16T11:19:00Z")) // next-day 06:19
        assertEquals("06:20-18:20", bucket("2025-01-16T11:20:00Z")) // next-day 06:20 is day bucket
    }

    @Test
    fun `quick run orders by sufficiently sampled recent history but returns only this run`() = runBlocking {
        val store = MemoryStore()
        val clock = 1_000_000L
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val probe = session(store, startedAt = started, now = { clock })
        val candidates = listOf("slow-history", "fast-history", "one-sample", "never-seen")
        store.values[historyKey("06:20-18:20", "wifi", "phase", "slow-history")] = AdaptiveSpeedProbe.History(2, 80, clock)
        store.values[historyKey("06:20-18:20", "wifi", "phase", "fast-history")] = AdaptiveSpeedProbe.History(4, 10, clock)
        store.values[historyKey("06:20-18:20", "wifi", "phase", "one-sample")] = AdaptiveSpeedProbe.History(1, 1, clock)
        val startedOrder = mutableListOf<String>()

        val results = probe.run(
            scope = "phase",
            candidates = candidates,
            key = { it },
            quick = true,
            success = { it.startsWith("ok") },
            elapsedMs = { it.removePrefix("ok").toLong() },
        ) { endpoint ->
            startedOrder += endpoint
            "ok${if (endpoint == "fast-history") 5 else 10}"
        }

        assertEquals(listOf("fast-history", "slow-history"), startedOrder)
        assertEquals(setOf("fast-history", "slow-history"), results.map { it.first }.toSet())
        assertTrue(results.all { it.second.startsWith("ok") })
        assertEquals(5, store.values[historyKey("06:20-18:20", "wifi", "phase", "fast-history")]?.successes)
    }

    @Test
    fun `no history accepts two valid probes without a fixed global latency ceiling`() = runBlocking {
        val tested = mutableListOf<String>()
        val events = mutableListOf<String>()
        val results = session(concurrency = 1).run(
            scope = "cold-start",
            candidates = listOf("first", "second", "third", "fourth"),
            key = { it },
            quick = true,
            success = { it == "valid" },
            elapsedMs = { 2_500L },
            log = events::add,
        ) { candidate ->
            tested += candidate
            "valid"
        }

        assertEquals(listOf("first", "second"), tested)
        assertEquals(2, results.size)
        assertTrue(events.any { it.contains("fastThresholdMs=${Long.MAX_VALUE}") })
    }

    @Test
    fun `reliable history contributes to threshold and slow current pool expands`() = runBlocking {
        val store = MemoryStore()
        val clock = 1_000_000L
        store.values[historyKey("06:20-18:20", "wifi", "baseline", "known")] =
            AdaptiveSpeedProbe.History(successes = 5, latencyMs = 600L, lastMeasuredAt = clock)
        val tested = mutableListOf<String>()
        val events = mutableListOf<String>()

        session(store, now = { clock }, concurrency = 1).run(
            scope = "baseline",
            candidates = listOf("known", "backup", "extra"),
            key = { it },
            quick = true,
            success = { it == "valid" },
            elapsedMs = { 1_300L },
            log = events::add,
        ) { candidate ->
            tested += candidate
            "valid"
        }

        assertEquals(listOf("known", "backup", "extra"), tested)
        assertTrue(events.any { it.contains("baselineMs=600; fastThresholdMs=1200") })
    }

    @Test
    fun `fewer than two samples fall back to configured candidate order`() = runBlocking {
        val store = MemoryStore()
        val clock = 10_000L
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        store.values[historyKey("06:20-18:20", "wifi", "main", "configured-first")] = AdaptiveSpeedProbe.History(1, 1, clock)
        store.values[historyKey("06:20-18:20", "wifi", "main", "configured-second")] = AdaptiveSpeedProbe.History(1, 2, clock)
        val order = mutableListOf<String>()

        session(store, startedAt = started, now = { clock }).run(
            scope = "main",
            candidates = listOf("configured-first", "configured-second", "would-be-fast"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
        ) { candidate ->
            order += candidate
            "current"
        }

        assertEquals(listOf("configured-first", "configured-second"), order)
    }

    @Test
    fun `slow and failed endpoints do not stop exploration after more than five candidates`() = runBlocking {
        val store = MemoryStore()
        val clock = 1_000_000L
        store.values[historyKey("06:20-18:20", "wifi", "all", "ep0")] =
            AdaptiveSpeedProbe.History(successes = 3, latencyMs = 100L, lastMeasuredAt = clock)
        store.values[historyKey("06:20-18:20", "wifi", "all", "ep1")] =
            AdaptiveSpeedProbe.History(successes = 3, latencyMs = 120L, lastMeasuredAt = clock)
        val tried = mutableListOf<Int>()
        val candidates = (0..6).toList()

        val results = session(store, now = { clock }).run(
            scope = "all",
            candidates = candidates,
            key = { "ep$it" },
            quick = true,
            success = { it != "failed" },
            elapsedMs = { if (it == "slow") 2_000L else 20L },
        ) { candidate ->
            tried += candidate
            when (candidate) {
                0, 2, 4, 5 -> "slow"
                1, 3 -> "failed"
                else -> "fast"
            }
        }

        assertEquals(candidates, tried)
        assertEquals(candidates.size, results.size)
        assertEquals(7, results.size, "non-null failures remain observable without terminating exploration")
    }

    @Test
    fun `bounded workers refill after a completion without exceeding concurrency`() = runBlocking {
        val store = MemoryStore()
        val clock = 1_000_000L
        store.values[historyKey("06:20-18:20", "wifi", "parallel", "ep0")] =
            AdaptiveSpeedProbe.History(successes = 3, latencyMs = 100L, lastMeasuredAt = clock)
        store.values[historyKey("06:20-18:20", "wifi", "parallel", "ep1")] =
            AdaptiveSpeedProbe.History(successes = 3, latencyMs = 120L, lastMeasuredAt = clock)
        val entered = ConcurrentHashMap.newKeySet<Int>()
        val gates = (0..7).associateWith { CompletableDeferred<String>() }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val run = async {
            session(store, now = { clock }, concurrency = 3).run(
                scope = "parallel",
                candidates = (0..7).toList(),
                key = { "ep$it" },
                quick = true,
                success = { true },
                elapsedMs = { if (it == "slow") 2_000L else 5L },
            ) { candidate ->
                entered += candidate
                val current = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, current) }
                try {
                    gates.getValue(candidate).await()
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        withTimeout(1_000L) { while (entered.size < 2) delay(1L) }
        gates.getValue(0).complete("slow")
        withTimeout(1_000L) { while (entered.none { it >= 2 }) delay(1L) }
        for (id in entered.toList()) gates[id]?.complete("ok")
        withTimeout(1_000L) {
            while (entered.any { gates.getValue(it).isActive }) {
                entered.forEach { gates.getValue(it).complete("ok") }
                delay(1L)
            }
        }
        val results = withTimeout(1_000L) { run.await() }

        assertTrue(entered.size >= 3, "a completed worker should refill from the remaining queue")
        assertTrue(peak.get() <= 3, "observed parallel probes must honor the configured bound")
        assertTrue(peak.get() >= 2, "the test must actually exercise overlapping probes")
        assertEquals(entered.size, results.size)
    }

    @Test
    fun `cancelled probe is not persisted as an endpoint failure`() = runBlocking {
        val store = MemoryStore()
        val entered = CompletableDeferred<Unit>()
        val job = async {
            session(store, concurrency = 1).run(
                scope = "cancel",
                candidates = listOf("cancel-me", "untouched"),
                key = { it },
                quick = true,
                success = { true },
                elapsedMs = { 5L },
            ) {
                entered.complete(Unit)
                CompletableDeferred<String>().await()
            }
        }
        entered.await()
        job.cancelAndJoin()

        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `invalid sample is visible to caller but does not create cooldown history`() = runBlocking {
        val store = MemoryStore()
        val results = session(store).run(
            scope = "anomaly",
            candidates = listOf("unstable"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
            sampleInvalid = { it == "bad-sample" },
        ) { "bad-sample" }

        assertEquals(listOf("bad-sample"), results.map { it.second })
        val history = store.values[historyKey("06:20-18:20", "wifi", "anomaly", "unstable")]
        assertEquals(0, history?.failures)
        assertEquals(0, history?.successes)
        assertEquals(0L, history?.cooldownUntil)
        assertEquals(0L, history?.lastMeasuredAt)
    }

    @Test
    fun `timeout cools down only timed out endpoint and allows other candidate`() = runBlocking {
        val store = MemoryStore()
        val clock = 1_000L
        val results = session(store, now = { clock }, concurrency = 2, timeoutMs = 15L, cooldownMs = 80L).run(
            scope = "timeout",
            candidates = listOf("hang", "works"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
        ) { candidate ->
            if (candidate == "hang") CompletableDeferred<String>().await() else "ok"
        }

        assertEquals(listOf("works"), results.map { it.first })
        val timeoutEntry = store.values[historyKey("06:20-18:20", "wifi", "timeout", "hang")]!!
        assertEquals(1, timeoutEntry.failures)
        assertEquals(clock + 80L, timeoutEntry.cooldownUntil)
    }

    @Test
    fun `probe exception records cooldown and exploration continues to next endpoint`() = runBlocking {
        val store = MemoryStore()
        val clock = 4_000L
        val events = mutableListOf<String>()
        val visited = mutableListOf<String>()

        val results = session(store, now = { clock }, cooldownMs = 75L).run(
            scope = "exception",
            candidates = listOf("throws", "healthy"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
            log = events::add,
        ) { candidate ->
            visited += candidate
            if (candidate == "throws") throw IllegalStateException("synthetic probe failure")
            "ok"
        }

        assertEquals(listOf("throws", "healthy"), visited)
        assertEquals(listOf("healthy"), results.map { it.first })
        val failed = store.values[historyKey("06:20-18:20", "wifi", "exception", "throws")]!!
        assertEquals(clock + 75L, failed.cooldownUntil)
        assertTrue(events.any { it.contains("exception-IllegalStateException") })
        assertEquals(0, store.values[historyKey("06:20-18:20", "wifi", "exception", "healthy")]?.failures)
    }

    @Test
    fun `endpoint becomes probeable again after cooldown and is marked recovery`() = runBlocking {
        val store = MemoryStore()
        val clock = System.currentTimeMillis()
        val first = session(store, now = { System.currentTimeMillis() }, timeoutMs = 5L, cooldownMs = 30L)
        first.run(
            scope = "recover",
            candidates = listOf("recover-me"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 1L },
        ) { CompletableDeferred<String>().await() }
        val afterTimeout = store.values[historyKey("06:20-18:20", "wifi", "recover", "recover-me")]!!
        assertEquals(1, afterTimeout.failures)
        assertTrue(afterTimeout.cooldownUntil >= clock)
        delay(40L)
        val events = mutableListOf<String>()

        val result = session(store, now = { System.currentTimeMillis() }).run(
            scope = "recover",
            candidates = listOf("recover-me"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 5L },
            log = events::add,
        ) { "ok" }

        assertEquals(listOf("recover-me"), result.map { it.first })
        assertTrue(events.any { it.contains("reason=recovery-after-cooldown") })
        assertEquals(0, store.values[historyKey("06:20-18:20", "wifi", "recover", "recover-me")]?.failures)
    }

    @Test
    fun `history is isolated by network scope endpoint and time bucket`() = runBlocking {
        val store = MemoryStore()
        val startedDay = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val startedNight = Instant.parse("2025-01-01T23:00:00Z").toEpochMilli()
        suspend fun record(network: String, scope: String, endpoint: String, started: Long) {
            session(store, network = network, startedAt = started).run(
                scope = scope,
                candidates = listOf(endpoint),
                key = { it },
                quick = false,
                success = { true },
                elapsedMs = { 10L },
            ) { "ok" }
        }
        record("wifi", "api", "same-ip", startedDay)
        record("mobile", "api", "same-ip", startedDay)
        record("wifi", "images", "same-ip", startedDay)
        record("wifi", "api", "other-ip", startedDay)
        record("wifi", "api", "same-ip", startedNight)

        assertEquals(9, store.writes.size)
        val keys = store.writes.keys
        assertTrue(keys.all { it.contains("wifi") || it.contains("mobile") })
        assertEquals(9, keys.toSet().size)
    }

    @Test
    fun `quick run budget is shared across calls on one session`() = runBlocking {
        var tick = 0L
        val probe = session(
            budgetMs = 25L,
            timeoutMs = 100L,
            monotonic = { tick },
        )
        var calls = 0
        val first = probe.run(
            scope = "first",
            candidates = listOf("slow"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
        ) {
            calls++
            tick = 26L
            "late"
        }
        val second = probe.run(
            scope = "second",
            candidates = listOf("must-not-run"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 1L },
        ) {
            calls++
            "ok"
        }

        assertEquals(listOf("late"), first.map { it.second })
        assertTrue(second.isEmpty(), "the second stage shares the session's already exhausted budget")
        assertEquals(1, calls)
    }

    @Test
    fun `two quick successes stop without dispatching an exploration candidate`() = runBlocking {
        val tried = mutableListOf<String>()
        val results = session(concurrency = 1).run(
            scope = "explore-after-fast",
            candidates = listOf("fast-a", "fast-b", "explore-me"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
        ) { candidate ->
            tried += candidate
            "current-$candidate"
        }

        assertEquals(listOf("fast-a", "fast-b"), tried)
        assertEquals(tried.toSet(), results.map { it.first }.toSet())
        assertTrue(results.none { it.second.contains("untested") })
    }

    @Test
    fun `separate low frequency runs rotate unmeasured candidates across sessions`() = runBlocking {
        val store = MemoryStore()
        var clock = 1_000_000L
        val candidates = listOf("rank-a", "rank-b", "unmeasured-a", "unmeasured-b")
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        for (endpoint in listOf("rank-a", "rank-b")) {
            store.values[historyKey("06:20-18:20", "wifi", "rotate", endpoint)] = AdaptiveSpeedProbe.History(2, 10L, clock)
        }
        suspend fun runOnce(): List<String> {
            val tried = mutableListOf<String>()
            session(store, startedAt = started, now = { clock }).run(
                scope = "rotate",
                explorationOnly = true,
                candidates = candidates,
                key = { it },
                quick = true,
                success = { true },
                elapsedMs = { 10L },
            ) { candidate ->
                tried += candidate
                "ok"
            }
            return tried
        }

        assertEquals(listOf("unmeasured-a"), runOnce())
        clock += 3_600_000L
        assertEquals(listOf("unmeasured-b"), runOnce())
        assertEquals(1, store.values[historyKey("06:20-18:20", "wifi", "rotate", "unmeasured-a")]?.successes)
        assertEquals(1, store.values[historyKey("06:20-18:20", "wifi", "rotate", "unmeasured-b")]?.successes)
    }

    @Test
    fun `fixed clock restart does not repeat low priority exploration`() = runBlocking {
        val store = MemoryStore()
        val now = 2_000_000L
        val probe = session(
            store,
            startedAt = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli(),
            now = { now },
        )
        val candidates = listOf("primary-a", "primary-b", "cold-a", "cold-b")
        suspend fun runOnce(): List<String> {
            val tried = mutableListOf<String>()
            probe.run(
                scope = "fixed-clock",
                explorationOnly = true,
                candidates = candidates,
                key = { it },
                quick = true,
                success = { true },
                elapsedMs = { 5L },
            ) { candidate ->
                tried += candidate
                "ok"
            }
            return tried
        }

        assertEquals(listOf("primary-a"), runOnce())
        assertTrue(runOnce().isEmpty())
    }

    @Test
    fun `expired failure cooldown does not force a probe with two healthy peers`() = runBlocking {
        val store = MemoryStore()
        val now = 50_000L
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        fun seed(endpoint: String, attempted: Long) {
            store.values[historyKey("06:20-18:20", "wifi", "recover-priority", endpoint)] =
                AdaptiveSpeedProbe.History(failures = 2, lastAttemptAt = attempted, cooldownUntil = now - 1L)
        }
        seed("oldest-retry", 100L)
        seed("recent-retry", 200L)
        for (endpoint in listOf("ranked-a", "ranked-b")) {
            store.values[historyKey("06:20-18:20", "wifi", "recover-priority", endpoint)] = AdaptiveSpeedProbe.History(2, 10L, now)
        }
        val tried = mutableListOf<String>()

        session(store, startedAt = started, now = { now }).run(
            scope = "recover-priority",
            candidates = listOf("ranked-a", "ranked-b", "recent-retry", "oldest-retry"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
        ) { endpoint ->
            tried += endpoint
            "ok"
        }

        assertEquals(listOf("ranked-a", "ranked-b"), tried)
    }

    @Test
    fun `exploration cannot dispatch beyond exhausted quick budget`() = runBlocking {
        var tick = 0L
        var calls = 0
        val results = session(budgetMs = 10L, concurrency = 1, monotonic = { tick }).run(
            scope = "budget-exploration",
            candidates = listOf("fast-a", "fast-b", "must-not-overrun"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 5L },
        ) {
            calls++
            tick = 10L
            "ok"
        }

        assertEquals(1, calls)
        assertEquals(1, results.size)
    }

    @Test
    fun `consecutive failures back off exponentially to cap and success resets the streak`() = runBlocking {
        val store = MemoryStore()
        var clock = 10_000L
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val probe = session(
            store,
            startedAt = started,
            now = { clock },
            cooldownMs = 100L,
            maxCooldownMs = 350L,
        )
        suspend fun attempt(value: String): AdaptiveSpeedProbe.History {
            probe.run(
                scope = "backoff",
                candidates = listOf("endpoint"),
                key = { it },
                quick = true,
                success = { it == "ok" },
                elapsedMs = { 10L },
            ) { value }
            return store.values[historyKey("06:20-18:20", "wifi", "backoff", "endpoint")]!!
        }

        assertEquals(clock + 100L, attempt("bad").cooldownUntil)
        clock += 1_000L
        assertEquals(clock + 200L, attempt("bad").cooldownUntil)
        clock += 1_000L
        assertEquals(clock + 350L, attempt("bad").cooldownUntil)
        clock += 1_000L
        assertEquals(clock + 350L, attempt("bad").cooldownUntil)
        clock += 1_000L
        val afterSuccess = attempt("ok")
        assertEquals(0, afterSuccess.failures)
        assertEquals(0L, afterSuccess.cooldownUntil)
        clock += 1_000L
        val afterResetFailure = attempt("bad")
        assertEquals(1, afterResetFailure.failures)
        assertEquals(clock + 100L, afterResetFailure.cooldownUntil)
    }

    @Test
    fun `sample anomaly and cancellation preserve prior failure streak`() = runBlocking {
        val store = MemoryStore()
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val endpointKey = historyKey("06:20-18:20", "wifi", "preserve", "endpoint")
        val original = AdaptiveSpeedProbe.History(
            successes = 2,
            latencyMs = 25L,
            lastMeasuredAt = 900L,
            cooldownUntil = 900L,
            failures = 3,
            lastAttemptAt = 850L,
        )
        store.values[endpointKey] = original
        session(store, startedAt = started, now = { 1_000L }).run(
            scope = "preserve",
            candidates = listOf("endpoint"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
            sampleInvalid = { true },
        ) { "anomalous" }
        assertEquals(3, store.values[endpointKey]?.failures)
        assertEquals(2, store.values[endpointKey]?.successes)
        assertEquals(900L, store.values[endpointKey]?.lastMeasuredAt)

        val entered = CompletableDeferred<Unit>()
        val job = async {
            session(store, startedAt = started, now = { 1_000L }).run(
                scope = "preserve",
                candidates = listOf("endpoint"),
                key = { it },
                quick = true,
                success = { true },
                elapsedMs = { 10L },
            ) {
                entered.complete(Unit)
                CompletableDeferred<String>().await()
            }
        }
        entered.await()
        job.cancelAndJoin()

        assertEquals(3, store.values[endpointKey]?.failures)
        assertEquals(2, store.values[endpointKey]?.successes)
    }

    @Test
    fun `failures in different time buckets accumulate in the all-times history`() = runBlocking {
        val store = MemoryStore()
        val day = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val night = Instant.parse("2025-01-01T23:00:00Z").toEpochMilli()
        var clock = 10_000L
        suspend fun fail(started: Long) {
            session(store, startedAt = started, now = { clock }).run(
                scope = "cross-bucket",
                candidates = listOf("same-endpoint"),
                key = { it },
                quick = true,
                success = { false },
                elapsedMs = { 5L },
            ) { "unreachable" }
        }
        fail(day)
        clock += 100L
        fail(night)

        assertEquals(1, store.values[historyKey("06:20-18:20", "wifi", "cross-bucket", "same-endpoint")]?.failures)
        assertEquals(2, store.values[historyKey("18:20-06:20", "wifi", "cross-bucket", "same-endpoint")]?.failures)
        val aggregate = store.values[aggregateKey("wifi", "cross-bucket", "same-endpoint")]!!
        assertEquals(2, aggregate.failures)
        assertEquals(10_300L, aggregate.cooldownUntil)
    }

    @Test
    fun `legacy bucket migration uses latest measured health not anomalous attempt time`() = runBlocking {
        val store = MemoryStore()
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val endpoint = "legacy-endpoint"
        store.values[historyKey("18:20-06:20", "wifi", "legacy-merge", endpoint)] = AdaptiveSpeedProbe.History(
            successes = 1,
            latencyMs = 30L,
            lastMeasuredAt = 2_000L,
            cooldownUntil = 5_000L,
            failures = 2,
            lastAttemptAt = 2_100L,
        )
        store.values[historyKey("06:20-18:20", "wifi", "legacy-merge", endpoint)] = AdaptiveSpeedProbe.History(
            successes = 1,
            latencyMs = 20L,
            lastMeasuredAt = 1_000L,
            cooldownUntil = 0L,
            failures = 0,
            lastAttemptAt = 3_000L,
        )

        session(store, startedAt = started, now = { 4_000L }).run(
            scope = "legacy-merge",
            candidates = listOf(endpoint),
            key = { it },
            quick = false,
            success = { true },
            elapsedMs = { 10L },
            sampleInvalid = { true },
        ) { "anomaly" }

        val aggregate = store.values[aggregateKey("wifi", "legacy-merge", endpoint)]!!
        assertEquals(2, aggregate.failures, "an anomalous newer attempt must not reset migrated failures")
        assertEquals(5_000L, aggregate.cooldownUntil, "the active failure cooldown must survive migration")
        assertEquals(2_000L, aggregate.lastMeasuredAt, "lastAttemptAt is not a substitute for valid lastMeasuredAt")
        assertTrue(aggregate.lastAttemptAt > aggregate.lastMeasuredAt)
    }

    @Test
    fun `concurrent sessions merge failure streaks without losing read modify write`() = runBlocking {
        val store = MemoryStore()
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val clock = 20_000L
        val startedCount = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        suspend fun runSession() = session(store, startedAt = started, now = { clock }).run(
            scope = "concurrent-merge",
            candidates = listOf("shared-endpoint"),
            key = { it },
            quick = true,
            success = { false },
            elapsedMs = { 5L },
        ) {
            if (startedCount.incrementAndGet() == 2) bothStarted.complete(Unit)
            release.await()
        }
        val first = async { runSession() }
        val second = async { runSession() }
        withTimeout(1_000L) { bothStarted.await() }
        release.complete("failed")
        withTimeout(1_000L) { first.await() }
        withTimeout(1_000L) { second.await() }

        val local = store.values[historyKey("06:20-18:20", "wifi", "concurrent-merge", "shared-endpoint")]!!
        val aggregate = store.values[aggregateKey("wifi", "concurrent-merge", "shared-endpoint")]!!
        assertEquals(2, local.failures)
        assertEquals(2, aggregate.failures)
        assertEquals(clock + 200L, aggregate.cooldownUntil)
    }

    @Test
    fun `all-times history only orders candidates when current bucket is unsampled`() = runBlocking {
        val store = MemoryStore()
        val clock = 1_000_000L
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        store.values[aggregateKey("wifi", "aggregate-fallback", "old-winner")] =
            AdaptiveSpeedProbe.History(successes = 4, latencyMs = 2L, lastMeasuredAt = clock)
        store.values[aggregateKey("wifi", "aggregate-fallback", "other")] =
            AdaptiveSpeedProbe.History(successes = 3, latencyMs = 50L, lastMeasuredAt = clock)
        val tried = mutableListOf<String>()
        val results = session(store, startedAt = started, now = { clock }).run(
            scope = "aggregate-fallback",
            candidates = listOf("other", "old-winner", "never-measured"),
            key = { it },
            quick = true,
            success = { it.startsWith("fresh:") },
            elapsedMs = { 5L },
        ) { endpoint ->
            tried += endpoint
            "fresh:$endpoint"
        }

        assertEquals(listOf("old-winner", "other"), tried)
        assertEquals(tried.toSet(), results.map { it.first }.toSet())
        assertTrue(results.all { it.second.startsWith("fresh:") }, "aggregate latency must never be returned as a measured result")
        assertEquals(1, store.values[historyKey("06:20-18:20", "wifi", "aggregate-fallback", "old-winner")]?.successes)
    }

    @Test
    fun `current bucket sample outranks a faster aggregate fallback`() = runBlocking {
        val store = MemoryStore()
        val clock = 1_000_000L
        val started = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        store.values[historyKey("06:20-18:20", "wifi", "local-priority", "local")] =
            AdaptiveSpeedProbe.History(successes = 2, latencyMs = 500L, lastMeasuredAt = clock)
        store.values[aggregateKey("wifi", "local-priority", "fallback")] =
            AdaptiveSpeedProbe.History(successes = 10, latencyMs = 1L, lastMeasuredAt = clock)
        val tried = mutableListOf<String>()

        session(store, startedAt = started, now = { clock }).run(
            scope = "local-priority",
            candidates = listOf("fallback", "local", "unseen"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 10L },
        ) { endpoint ->
            tried += endpoint
            "ok"
        }

        assertEquals("local", tried.first())
        assertEquals("fallback", tried[1])
    }

    @Test
    fun `history keys isolate network scope endpoint and preserve separate local buckets`() = runBlocking {
        val store = MemoryStore()
        val startedDay = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val startedNight = Instant.parse("2025-01-01T23:00:00Z").toEpochMilli()
        suspend fun record(network: String, scope: String, endpoint: String, started: Long) {
            session(store, network = network, startedAt = started).run(
                scope = scope,
                candidates = listOf(endpoint),
                key = { it },
                quick = false,
                success = { true },
                elapsedMs = { 10L },
            ) { "ok" }
        }
        record("wifi", "api", "same-ip", startedDay)
        record("mobile", "api", "same-ip", startedDay)
        record("wifi", "images", "same-ip", startedDay)
        record("wifi", "api", "other-ip", startedDay)
        record("wifi", "api", "same-ip", startedNight)

        assertEquals(9, store.writes.size)
        assertEquals(
            5,
            listOf(
                historyKey("06:20-18:20", "wifi", "api", "same-ip"),
                historyKey("06:20-18:20", "mobile", "api", "same-ip"),
                historyKey("06:20-18:20", "wifi", "images", "same-ip"),
                historyKey("06:20-18:20", "wifi", "api", "other-ip"),
                historyKey("18:20-06:20", "wifi", "api", "same-ip"),
            ).count { store.writes.containsKey(it) },
        )
        assertEquals(
            4,
            listOf(
                aggregateKey("wifi", "api", "same-ip"),
                aggregateKey("mobile", "api", "same-ip"),
                aggregateKey("wifi", "images", "same-ip"),
                aggregateKey("wifi", "api", "other-ip"),
            ).count { store.writes.containsKey(it) },
        )
    }

    @Test
    fun `foreign network business or exact IP history cannot reorder actual probes`() = runBlocking {
        val endpoint = "service.test[192.0.2.1]"
        val clock = 1_000_000L
        val foreignKeys = listOf(
            aggregateKey("mobile", "api", endpoint),
            aggregateKey("wifi", "cover", endpoint),
            aggregateKey("wifi", "api", "service.test[192.0.2.2]"),
        )
        for (foreignKey in foreignKeys) {
            val store = MemoryStore()
            store.values[foreignKey] = AdaptiveSpeedProbe.History(20, 1L, clock)
            val tried = mutableListOf<String>()
            val configured = listOf("configured-first", endpoint, "explorer")
            session(store, now = { clock }).run(
                scope = "api",
                candidates = configured,
                key = { it },
                quick = true,
                success = { true },
                elapsedMs = { 5L },
            ) {
                tried += it
                "fresh"
            }
            assertEquals(configured.take(2), tried, "A foreign identity must not promote an endpoint: $foreignKey")
        }
    }

    @Test
    fun `sparse current bucket ranks by merged legacy all-times samples not a lucky single sample`() = runBlocking {
        val store = MemoryStore()
        val clock = 1_000_000L
        store.values[historyKey("06:20-18:20", "wifi", "sparse", "one-sample-fluke")] =
            AdaptiveSpeedProbe.History(1, 1L, clock)
        store.values[historyKey("18:20-06:20", "wifi", "sparse", "one-sample-fluke")] =
            AdaptiveSpeedProbe.History(3, 300L, clock - 100L)
        store.values[historyKey("18:20-06:20", "wifi", "sparse", "aggregate-preferred")] =
            AdaptiveSpeedProbe.History(2, 50L, clock - 100L)
        val tried = mutableListOf<String>()
        val results = session(store, now = { clock }).run(
            scope = "sparse",
            candidates = listOf("one-sample-fluke", "aggregate-preferred", "explorer"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 5L },
        ) {
            tried += it
            "fresh:$it"
        }
        assertEquals(listOf("aggregate-preferred", "one-sample-fluke"), tried)
        assertTrue(results.all { it.second.startsWith("fresh:") })
    }

    @Test
    fun `full run probes every candidate without quick-mode early stop`() = runBlocking {
        val tried = mutableListOf<Int>()
        val candidates = (0..5).toList()

        session(concurrency = 2).run(
            scope = "full",
            candidates = candidates,
            key = { "candidate-$it" },
            quick = false,
            success = { true },
            elapsedMs = { 1L },
        ) { candidate ->
            tried += candidate
            "ok"
        }

        assertEquals(candidates.toSet(), tried.toSet())
        assertEquals(candidates.size, tried.size)
    }

    @Test
    fun `preparation and probes share the same deadline without penalizing endpoints`() = runBlocking {
        val store = MemoryStore()
        val probe = session(store, budgetMs = 30L)
        val prepared = probe.prepare(quick = true) {
            delay(500L)
            listOf("late-dns")
        }
        assertEquals(null, prepared)
        val result = probe.run(
            scope = "after-preparation",
            candidates = listOf("unmeasured"),
            key = { it },
            quick = true,
            success = { true },
            elapsedMs = { 1L },
        ) { error("Preparation already exhausted the run budget") }
        assertTrue(result.isEmpty())
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `blocking preparation does not hold the run hostage to uninterruptible DNS`() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val probe = session(budgetMs = 100L)
        try {
            val result = withTimeout(1_000L) {
                probe.prepareBlocking(quick = true) {
                    entered.countDown()
                    while (gate.count > 0L) {
                        try {
                            gate.await()
                        } catch (_: InterruptedException) {
                            // Simulate the OS resolver ignoring thread interruption.
                        }
                    }
                    "dns-result"
                }
            }
            assertEquals(0L, entered.count)
            assertEquals(null, result)
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `full preparation is not restricted by the quick run budget`() = runBlocking {
        var tick = 0L
        val probe = session(budgetMs = 1L, monotonic = { tick })
        tick = 100L
        assertEquals("full", probe.prepare(quick = false) { "full" })
        assertEquals("blocking-full", probe.prepareBlocking(quick = false) { "blocking-full" })
    }
}
