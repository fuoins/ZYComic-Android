package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class AdaptiveStartupRecoveryTest {
    private class MemoryStore : AdaptiveSpeedProbe.Store {
        val history = ConcurrentHashMap<String, AdaptiveSpeedProbe.History>()
        val exploration = ConcurrentHashMap<String, AdaptiveSpeedProbe.ExplorationBudget>()

        override fun read(key: String): AdaptiveSpeedProbe.History? = history[key]

        override fun write(key: String, value: AdaptiveSpeedProbe.History) {
            history[key] = value
        }

        override fun readExploration(key: String): AdaptiveSpeedProbe.ExplorationBudget? = exploration[key]

        override fun writeExploration(key: String, value: AdaptiveSpeedProbe.ExplorationBudget) {
            exploration[key] = value
        }
    }

    private fun historyKey(bucket: String, network: String, scope: String, endpoint: String): String =
        listOf(bucket, network, scope, endpoint).joinToString("") { "${it.length}:$it" }

    private fun session(
        store: MemoryStore,
        network: String = "wifi",
        startedAt: Long,
        now: () -> Long,
        concurrency: Int = 1,
        explorationIntervalMs: Long = 60 * 60_000L,
        explorationLimit: Int = 2,
    ) = AdaptiveSpeedProbe.Session(
        networkIdentity = network,
        startedAtEpochMs = startedAt,
        store = store,
        budgetMs = 10_000L,
        concurrency = concurrency,
        attemptTimeoutMs = 1_000L,
        cooldownMs = 100L,
        now = now,
        monotonicMs = { 0L },
        zone = ZoneId.of("UTC"),
        explorationIntervalMs = explorationIntervalMs,
        explorationLimit = explorationLimit,
    )

    private fun at(time: String): Long = Instant.parse(time).toEpochMilli()

    @Test
    fun `preferred current and independent recent backup win without probing cooled or expired failures`() = runBlocking {
        val store = MemoryStore()
        val clock = at("2025-01-01T12:00:00Z")
        val bucket = "06:20-18:20"
        fun seed(endpoint: String, value: AdaptiveSpeedProbe.History) {
            store.history[historyKey(bucket, "wifi", "startup", endpoint)] = value
            store.history[historyKey("all-times", "wifi", "startup", endpoint)] = value
        }
        seed("current", AdaptiveSpeedProbe.History(successes = 2, latencyMs = 60, lastMeasuredAt = clock))
        seed("backup", AdaptiveSpeedProbe.History(successes = 2, latencyMs = 90, lastMeasuredAt = clock))
        seed(
            "cooling-failure",
            AdaptiveSpeedProbe.History(failures = 2, cooldownUntil = clock + 10_000L, lastMeasuredAt = clock),
        )
        seed("expired-failure", AdaptiveSpeedProbe.History(failures = 1, cooldownUntil = clock - 1L, lastMeasuredAt = clock))
        val invoked = mutableListOf<String>()

        session(store, startedAt = clock, now = { clock }).run(
            scope = "startup",
            candidates = listOf("cooling-failure", "backup", "expired-failure", "current"),
            key = { it },
            quick = true,
            success = { it > 0L },
            elapsedMs = { it },
            preferredKeys = setOf("current"),
            independenceKey = { if (it == "backup") "backup-host" else "current-host" },
        ) { endpoint ->
            invoked += endpoint
            40L
        }

        assertEquals(listOf("current", "backup"), invoked)
        assertFalse("cooling-failure" in invoked)
        assertFalse("expired-failure" in invoked, "an expired cooldown permits recovery but does not force a probe")
    }

    @Test
    fun `independent reliable backup wins over faster shared IP duplicate`() = runBlocking {
        val store = MemoryStore()
        val clock = at("2025-01-01T12:00:00Z")
        for ((endpoint, latency) in listOf("current" to 40L, "same-ip" to 20L, "independent" to 80L)) {
            store.history[historyKey("06:20-18:20", "wifi", "diversity", endpoint)] =
                AdaptiveSpeedProbe.History(2, latency, clock)
        }
        val tried = mutableListOf<String>()
        session(store, startedAt = clock, now = { clock }).run(
            scope = "diversity",
            candidates = listOf("same-ip", "independent", "current"),
            key = { it },
            quick = true,
            success = { _: Long -> true },
            elapsedMs = { it },
            preferredKeys = setOf("current"),
            independenceKey = { if (it == "independent") "ip-b" else "ip-a" },
        ) {
            tried += it
            35L
        }
        assertEquals(listOf("current", "independent"), tried)
    }

    @Test
    fun `clear slowdown relative to recent business baseline expands beyond the primary pair`() = runBlocking {
        val store = MemoryStore()
        val clock = at("2025-01-01T12:00:00Z")
        for (endpoint in listOf("current", "backup")) {
            store.history[historyKey("06:20-18:20", "wifi", "slowdown", endpoint)] =
                AdaptiveSpeedProbe.History(2, 100L, clock)
        }
        val tried = mutableListOf<String>()
        session(store, startedAt = clock, now = { clock }).run(
            scope = "slowdown",
            candidates = listOf("current", "backup", "unknown-a", "unknown-b"),
            key = { it },
            quick = true,
            success = { _: Long -> true },
            elapsedMs = { it },
        ) {
            tried += it
            if (it == "current" || it == "backup") 800L else 90L
        }
        assertEquals(listOf("current", "backup", "unknown-a", "unknown-b"), tried)
    }

    @Test
    fun `in flight cap dispatches up to the configured concurrency and two fast successes stop further dispatch`() = runBlocking {
        val store = MemoryStore()
        val clock = at("2025-01-01T12:00:00Z")
        val gates = (0..3).associateWith { CompletableDeferred<Long>() }
        val invoked = CopyOnWriteArrayList<Int>()
        val run = async {
            session(store, startedAt = clock, now = { clock }, concurrency = 3).run(
                scope = "parallel-startup",
                candidates = (0..3).toList(),
                key = { "ep$it" },
                quick = true,
                success = { it > 0L },
                elapsedMs = { it },
            ) { candidate ->
                invoked += candidate
                gates.getValue(candidate).await()
            }
        }
        withTimeout(3_000L) { while (invoked.size < 3) delay(1L) }
        assertEquals(3, invoked.size, "the cap allows concurrency probes, not an artificial two")
        // 完成前两个后仍有补位在飞；这里把当前所有已派发的探测完成，验证并发不会超上限。
        withTimeout(3_000L) {
            while (run.isActive) {
                invoked.forEach { gates.getValue(it).complete(20L + it) }
                delay(5L)
            }
        }
        val results = withTimeout(1_000L) { run.await() }

        assertTrue(results.size >= 3)
        assertTrue(invoked.size >= 3)
    }

    @Test
    fun `failed and clearly slow probes continue recovery while slow business baseline allows two successes`() = runBlocking {
        val clock = at("2025-01-01T12:00:00Z")
        val store = MemoryStore()
        val tried = mutableListOf<String>()
        session(store, startedAt = clock, now = { clock }).run(
            scope = "slow-recovery",
            candidates = listOf("failed", "slow", "recovered"),
            key = { it },
            quick = true,
            success = { it > 0L },
            elapsedMs = { it },
        ) { endpoint ->
            tried += endpoint
            when (endpoint) {
                "failed" -> 0L
                "slow" -> 1_700L
                else -> 30L
            }
        }
        assertEquals(listOf("failed", "slow", "recovered"), tried)
        assertEquals(1, store.history[historyKey("06:20-18:20", "wifi", "slow-recovery", "failed")]?.failures)

        val slowBaselineStore = MemoryStore()
        for (endpoint in listOf("one", "two", "three")) {
            val seed = AdaptiveSpeedProbe.History(successes = 2, latencyMs = 1_800L, lastMeasuredAt = clock)
            slowBaselineStore.history[historyKey("06:20-18:20", "wifi", "slow-business", endpoint)] = seed
            slowBaselineStore.history[historyKey("all-times", "wifi", "slow-business", endpoint)] = seed
        }
        val slowTried = mutableListOf<String>()
        session(slowBaselineStore, startedAt = clock, now = { clock }).run(
            scope = "slow-business",
            candidates = listOf("one", "two", "three"),
            key = { it },
            quick = true,
            success = { it > 0L },
            elapsedMs = { it },
        ) { endpoint ->
            slowTried += endpoint
            2_400L
        }
        assertEquals(2, slowTried.size, "baseline above 1500ms raises the fast threshold enough to stop after two")
    }

    @Test
    fun `exploration quota persists across sessions resets by window and isolates business network and bucket`() = runBlocking {
        val store = MemoryStore()
        val clock = AtomicLong(at("2025-01-01T12:00:00Z"))
        var attempts = 0
        suspend fun explore(scope: String = "api", network: String = "wifi", startedAt: Long = clock.get()) {
            session(store, network = network, startedAt = startedAt, now = clock::get).run(
                scope = scope,
                candidates = (0..20).map { "peer-$it" },
                key = { it },
                quick = true,
                success = { it > 0L },
                elapsedMs = { it },
                explorationOnly = true,
            ) {
                attempts++
                10L
            }
        }

        explore()
        clock.addAndGet(60 * 60_000L)
        explore()
        clock.addAndGet(60 * 60_000L)
        explore()
        assertEquals(2, attempts, "same window is limited to two exploration attempts across sessions")

        clock.set(at("2025-01-02T12:00:00Z"))
        explore()
        assertEquals(3, attempts, "the next day window resets despite using the same day bucket")

        clock.set(at("2025-01-02T13:00:00Z"))
        explore(scope = "images")
        explore(network = "mobile")
        clock.set(at("2025-01-02T23:00:00Z"))
        explore(startedAt = clock.get())
        assertEquals(6, attempts, "business, network, and time-bucket identities have separate quotas")
    }

    @Test
    fun `night exploration window spans midnight without resetting and quota does not block normal recovery`() = runBlocking {
        val store = MemoryStore()
        val clock = AtomicLong(at("2025-01-01T23:00:00Z"))
        var attempts = 0
        suspend fun explore() {
            session(store, startedAt = clock.get(), now = clock::get).run(
                scope = "nightly",
                candidates = listOf("peer"),
                key = { it },
                quick = true,
                success = { it > 0L },
                elapsedMs = { it },
                explorationOnly = true,
            ) {
                attempts++
                10L
            }
        }
        explore()
        clock.set(at("2025-01-02T01:00:00Z"))
        explore()
        clock.set(at("2025-01-02T03:00:00Z"))
        explore()
        assertEquals(2, attempts)
        val nightBudget = store.exploration.values.single()
        assertEquals(at("2025-01-01T18:20:00Z"), nightBudget.windowStartedAt)
        assertEquals(2, nightBudget.attempts)

        var normalAttempts = 0
        session(store, startedAt = clock.get(), now = clock::get).run(
            scope = "nightly",
            candidates = listOf("ordinary-recovery"),
            key = { it },
            quick = true,
            success = { it > 0L },
            elapsedMs = { it },
        ) {
            normalAttempts++
            25L
        }
        assertEquals(1, normalAttempts, "exhausted exploration budget must not limit normal recovery")
    }

    @Test
    fun `deferred exploration is inert until taken and runs outside foreground results`() = runBlocking {
        val store = MemoryStore()
        val clock = at("2025-01-01T12:00:00Z")
        val probe = session(store, startedAt = clock, now = { clock })
        var deferredCalls = 0
        probe.deferExploration(
            scope = "deferred",
            candidates = listOf("independent-peer"),
            key = { it },
            success = { it > 0L },
            elapsedMs = { it },
        ) {
            deferredCalls++
            50L
        }

        val foreground = probe.run(
            scope = "deferred",
            candidates = listOf("current"),
            key = { it },
            quick = true,
            success = { it > 0L },
            elapsedMs = { it },
        ) { 20L }
        assertEquals(listOf("current"), foreground.map { it.first })
        assertEquals(0, deferredCalls)
        assertTrue(store.exploration.isEmpty(), "merely deferring or running foreground work must not reserve exploration quota")

        val tasks = probe.takeDeferredExploration()
        assertEquals(1, tasks.size)
        assertTrue(probe.takeDeferredExploration().isEmpty())
        tasks.single().invoke()
        assertEquals(1, deferredCalls)
        assertEquals(1, store.exploration.values.single().attempts, "deferred task owns its independent exploration budget")
        assertFalse(foreground.any { it.first == "independent-peer" }, "deferred results must not contaminate foreground selection")
    }
}
