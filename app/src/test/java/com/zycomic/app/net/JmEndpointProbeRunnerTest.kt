package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JmEndpointProbeRunnerTest {

    @Test
    fun `runner bounds concurrency without serializing and preserves input order`() = runBlocking {
        val runner = JmEndpointProbeRunner(concurrency = 2)
        val targets = listOf(1, 2, 3, 4)
        val gates = targets.associateWith { CompletableDeferred<Unit>() }
        val started = Channel<Int>(Channel.UNLIMITED)
        val completed = Channel<Int>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()

        val result = async {
            runner.run(
                targets = targets,
                probe = { target ->
                    val nowActive = active.incrementAndGet()
                    maxActive.updateAndGet { current -> maxOf(current, nowActive) }
                    try {
                        gates.getValue(target).await()
                        "$target-result"
                    } finally {
                        active.decrementAndGet()
                    }
                },
                onStarted = { target -> started.trySend(target) },
                onResult = { target, _ -> completed.trySend(target) },
            )
        }

        val firstStarted = setOf(
            withTimeout(5_000) { started.receive() },
            withTimeout(5_000) { started.receive() },
        )
        assertEquals(setOf(1, 2), firstStarted, "two probes should start before either is released")
        assertTrue(started.tryReceive().isFailure, "the concurrency limit must prevent a third start")
        assertEquals(2, maxActive.get())

        // Release probes in an order different from the target order.
        gates.getValue(2).complete(Unit)
        assertEquals(2, withTimeout(5_000) { completed.receive() })
        assertEquals(3, withTimeout(5_000) { started.receive() })
        gates.getValue(3).complete(Unit)
        assertEquals(3, withTimeout(5_000) { completed.receive() })
        assertEquals(4, withTimeout(5_000) { started.receive() })
        gates.getValue(4).complete(Unit)
        assertEquals(4, withTimeout(5_000) { completed.receive() })
        gates.getValue(1).complete(Unit)
        assertEquals(1, withTimeout(5_000) { completed.receive() })

        assertEquals(targets.map { it to "$it-result" }, withTimeout(5_000) { result.await() })
        assertEquals(2, maxActive.get(), "runner should remain bounded but allow parallel probes")
    }

    @Test
    fun `started callback is immediate and fast result is not blocked by slow endpoint`() = runBlocking {
        val runner = JmEndpointProbeRunner(concurrency = 2)
        val slowGate = CompletableDeferred<Unit>()
        val started = Channel<String>(Channel.UNLIMITED)
        val results = Channel<Pair<String, String>>(Channel.UNLIMITED)
        val callbackTargets = mutableSetOf<String>()

        val result = async {
            runner.run(
                targets = listOf("slow", "fast"),
                probe = { target ->
                    assertTrue(target in callbackTargets, "onStarted must run before the probe")
                    if (target == "slow") slowGate.await()
                    "$target-result"
                },
                onStarted = { target ->
                    callbackTargets += target
                    started.trySend(target)
                },
                onResult = { target, value -> results.trySend(target to value) },
            )
        }

        assertEquals(
            setOf("slow", "fast"),
            setOf(
                withTimeout(5_000) { started.receive() },
                withTimeout(5_000) { started.receive() },
            ),
        )
        assertEquals("fast" to "fast-result", withTimeout(5_000) { results.receive() })

        slowGate.complete(Unit)
        assertEquals("slow" to "slow-result", withTimeout(5_000) { results.receive() })
        assertEquals(
            listOf("slow" to "slow-result", "fast" to "fast-result"),
            withTimeout(5_000) { result.await() },
        )
    }

    @Test
    fun `cancellation propagates without reporting a completed result`() = runBlocking {
        val runner = JmEndpointProbeRunner(concurrency = 1)
        val started = Channel<String>(Channel.UNLIMITED)
        val results = Channel<String>(Channel.UNLIMITED)
        val probeCancelled = AtomicBoolean(false)
        val neverReleased = CompletableDeferred<Unit>()

        val job = async {
            runner.run(
                targets = listOf("active", "queued"),
                probe = { target ->
                    try {
                        neverReleased.await()
                        "$target-result"
                    } catch (cancelled: CancellationException) {
                        probeCancelled.set(true)
                        throw cancelled
                    }
                },
                onStarted = { target -> started.trySend(target) },
                onResult = { target, _ -> results.trySend(target) },
            )
        }

        assertEquals("active", withTimeout(5_000) { started.receive() })
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(probeCancelled.get(), "cancellation should reach the in-flight probe")
        assertTrue(results.tryReceive().isFailure, "cancellation must not synthesize a result callback")
        assertTrue(started.tryReceive().isFailure, "a cancelled queued probe must not start")
    }

    @Test
    fun `default concurrency permits are isolated per runner instance`() = runBlocking {
        val runnerA = JmEndpointProbeRunner()
        val runnerB = JmEndpointProbeRunner()
        val targets = (0..24).toList()
        val gatesA = targets.associateWith { CompletableDeferred<Unit>() }
        val gatesB = targets.associateWith { CompletableDeferred<Unit>() }
        val started = Channel<Pair<String, Int>>(Channel.UNLIMITED)

        val resultA = async {
            runnerA.run(
                targets = targets,
                probe = { target ->
                    gatesA.getValue(target).await()
                    target
                },
                onStarted = { target -> started.trySend("A" to target) },
            )
        }
        val resultB = async {
            runnerB.run(
                targets = targets,
                probe = { target ->
                    gatesB.getValue(target).await()
                    target
                },
                onStarted = { target -> started.trySend("B" to target) },
            )
        }

        val starts = (1..48).map { withTimeout(5_000) { started.receive() } }
        assertEquals(24, starts.count { it.first == "A" })
        assertEquals(24, starts.count { it.first == "B" })
        assertFalse(starts.contains("A" to 24))
        assertFalse(starts.contains("B" to 24))

        gatesA.values.forEach { it.complete(Unit) }
        gatesB.values.forEach { it.complete(Unit) }
        assertEquals(targets.map { it to it }, withTimeout(5_000) { resultA.await() })
        assertEquals(targets.map { it to it }, withTimeout(5_000) { resultB.await() })
    }

    @Test
    fun `progress message describes phase counts and elapsed time without endpoint identity`() {
        val failureMessage = JmSpeedTestProgress(
            phase = JmSpeedTestProgress.Phase.TESTING_LINES,
            completed = 2,
            total = 5,
            successful = 1,
            failed = 1,
            endpoint = "https://line.example",
            endpointSuccessful = false,
            endpointElapsedMs = 842,
            elapsedMs = 12_999,
        ).message()

        assertTrue(failureMessage.contains("线路测速"))
        assertTrue(failureMessage.contains("2/5"))
        assertTrue(failureMessage.contains("成功 1 / 失败 1"))
        assertTrue(failureMessage.contains("已用 12秒"))
        assertFalse(failureMessage.contains("https://line.example"))
        assertFalse(failureMessage.contains("842ms"))

        val successMessage = JmSpeedTestProgress(
            phase = JmSpeedTestProgress.Phase.TESTING_IMAGES,
            completed = 1,
            total = 1,
            successful = 1,
            endpoint = "https://image.example",
            endpointSuccessful = true,
            endpointElapsedMs = 9,
        ).message()
        assertTrue(successMessage.contains("图源测速"))
        assertTrue(successMessage.contains("成功 1 / 失败 0"))
        assertFalse(successMessage.contains("https://image.example"))
        assertFalse(successMessage.contains("9ms"))
    }
}
