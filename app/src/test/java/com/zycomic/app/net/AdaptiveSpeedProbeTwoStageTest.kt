package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class AdaptiveSpeedProbeTwoStageTest {
    private class MemoryStore : AdaptiveSpeedProbe.Store {
        val history = ConcurrentHashMap<String, AdaptiveSpeedProbe.History>()
        override fun read(key: String) = history[key]
        override fun write(key: String, value: AdaptiveSpeedProbe.History) {
            history[key] = value
        }
    }

    private val startedAt = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()

    private fun storeWithBaseline(scope: String): MemoryStore = MemoryStore().apply {
        val bucket = AdaptiveSpeedProbe.timeBucket(startedAt, ZoneId.of("UTC"))
        val key = listOf(bucket, "wifi", scope, "baseline").joinToString("") { "${it.length}:$it" }
        history[key] = AdaptiveSpeedProbe.History(successes = 2, latencyMs = 50L, lastMeasuredAt = startedAt)
    }

    private fun session(
        store: MemoryStore,
        budgetMs: Long = 300L,
        concurrency: Int = 1,
        fullConcurrency: Int = 3,
        attemptTimeoutMs: Long = 1_000L,
    ) = AdaptiveSpeedProbe.Session(
        networkIdentity = "wifi",
        startedAtEpochMs = startedAt,
        store = store,
        budgetMs = budgetMs,
        concurrency = concurrency,
        fullConcurrency = fullConcurrency,
        attemptTimeoutMs = attemptTimeoutMs,
        cooldownMs = 10L,
        now = { startedAt },
        monotonicMs = { System.nanoTime() / 1_000_000L },
        zone = ZoneId.of("UTC"),
    )

    @Test
    fun `threshold result publishes before run completes and later faster result is published`() = runBlocking {
        val firstPublished = CompletableDeferred<Int>()
        val finishSecond = CompletableDeferred<Unit>()
        val callbacks = CopyOnWriteArrayList<Int>()
        val run = async {
            session(storeWithBaseline("early"), budgetMs = 500L).run(
                scope = "early",
                candidates = listOf(1, 2),
                key = { "ep$it" },
                quick = true,
                success = { true },
                elapsedMs = { it.toLong() },
                onUsable = { _, result ->
                    callbacks += result
                    firstPublished.complete(result)
                },
            ) { candidate ->
                if (candidate == 1) {
                    100
                } else {
                    finishSecond.await()
                    30
                }
            }
        }
        assertEquals(100, withTimeout(1_000L) { firstPublished.await() })
        assertFalse(run.isCompleted, "callback must publish without detaching or completing the run")
        finishSecond.complete(Unit)
        withTimeout(1_000L) { run.await() }
        assertEquals(listOf(100, 30), callbacks)
    }

    @Test
    fun `deadline publishes best slow result while remaining work continues and later improvement republishes`() = runBlocking {
        val published = CompletableDeferred<Int>()
        val finishTail = CompletableDeferred<Unit>()
        val callbacks = CopyOnWriteArrayList<Int>()
        val run = async {
            session(storeWithBaseline("fallback"), budgetMs = 100L).run(
                scope = "fallback",
                candidates = listOf(1, 2),
                key = { "ep$it" },
                quick = true,
                success = { true },
                elapsedMs = { it.toLong() },
                onUsable = { _, result ->
                    callbacks += result
                    published.complete(result)
                },
            ) { candidate ->
                if (candidate == 1) {
                    delay(10L)
                    500 // Valid but slower than the derived 200ms fast threshold.
                } else {
                    finishTail.await()
                    400
                }
            }
        }
        assertEquals(500, withTimeout(1_000L) { published.await() })
        assertFalse(run.isCompleted, "deadline callback must not terminate the background tail")
        finishTail.complete(Unit)
        withTimeout(1_000L) { run.await() }
        assertEquals(listOf(500, 400), callbacks)
    }

    @Test
    fun `quick release keeps existing in-flight attempts and drains the remaining tail serially`() = runBlocking {
        val initialAttemptsStarted = CompletableDeferred<Unit>()
        val releaseInitialAttempts = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Int>()
        val seen = CopyOnWriteArrayList<Int>()
        val activeTail = AtomicInteger(0)
        val maxActiveTail = AtomicInteger(0)
        val run = async {
            session(storeWithBaseline("drain"), budgetMs = 500L, concurrency = 3).run(
                scope = "drain",
                candidates = (0..5).toList(),
                key = { "ep$it" },
                quick = true,
                success = { true },
                elapsedMs = { it.toLong() },
                onUsable = { _, value -> released.complete(value) },
            ) { candidate ->
                seen += candidate
                when (candidate) {
                    0 -> {
                        initialAttemptsStarted.await()
                        50
                    }
                    1, 2 -> {
                        if (seen.count { it == 1 || it == 2 } == 2) initialAttemptsStarted.complete(Unit)
                        releaseInitialAttempts.await()
                        candidate * 10
                    }
                    else -> {
                        val active = activeTail.incrementAndGet()
                        maxActiveTail.updateAndGet { maxOf(it, active) }
                        try {
                            delay(20L)
                            candidate * 10
                        } finally {
                            activeTail.decrementAndGet()
                        }
                    }
                }
            }
        }
        assertEquals(50, withTimeout(1_000L) { released.await() })
        withTimeout(1_000L) { initialAttemptsStarted.await() }
        delay(20L)
        assertEquals(3, seen.size, "already in-flight peers remain active; no fourth is dispatched yet")
        releaseInitialAttempts.complete(Unit)
        val result = withTimeout(2_000L) { run.await() }
        assertEquals(6, result.size, "every candidate is measured after early release")
        assertEquals(1, maxActiveTail.get(), "new background attempts are serialized")
    }

    @Test
    fun `full two stage publishes early preserves transport timeout and measures every candidate`() = runBlocking {
        val seen = CopyOnWriteArrayList<Int>()
        val callbacks = CopyOnWriteArrayList<Int>()
        val published = CompletableDeferred<Int>()
        val run = async {
            session(storeWithBaseline("full"), budgetMs = 500L, fullConcurrency = 3, attemptTimeoutMs = 25L).run(
                scope = "full",
                candidates = listOf(1, 2, 3),
                key = { "ep$it" },
                quick = false,
                success = { true },
                elapsedMs = { it.toLong() },
                onUsable = { _, value ->
                    callbacks += value
                    published.complete(value)
                },
            ) { candidate ->
                seen += candidate
                when (candidate) {
                    1 -> 50 // Meets the existing 200ms derived threshold and releases early.
                    2 -> {
                        delay(120L)
                        500
                    }
                    else -> {
                        delay(80L)
                        450
                    }
                }
            }
        }
        assertEquals(50, withTimeout(1_000L) { published.await() })
        assertFalse(run.isCompleted, "full callback must not wait for or cancel slower in-flight candidates")
        val result = withTimeout(2_000L) { run.await() }
        assertEquals(setOf(1, 2, 3), seen.toSet())
        assertEquals(3, result.size)
        assertTrue(callbacks.isNotEmpty())
    }

    @Test
    fun `caller cancellation cancels in-flight attempt without publishing`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var callbacks = 0
        val run = async {
            session(storeWithBaseline("cancel"), budgetMs = 500L).run(
                scope = "cancel",
                candidates = listOf(1, 2),
                key = { "ep$it" },
                quick = true,
                success = { true },
                elapsedMs = { it.toLong() },
                onUsable = { _, _ -> callbacks++ },
            ) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(1_000L) { started.await() }
        run.cancelAndJoin()
        assertFalse(run.isActive)
        assertEquals(0, callbacks)
    }
}
