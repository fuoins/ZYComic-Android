package com.zycomic.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ManwaImageFallbackProbePolicyTest {
    private data class ProbeResult(val successful: Boolean, val hasHttpResponse: Boolean)

    @Test
    fun `fixed success does not request fallback sample`() = runBlocking {
        var sampleReads = 0
        val probed = mutableListOf<String>()

        val attempts = ManwaImageFallbackProbePolicy.probeFixedThenFallback(
            fixedPath = "/fixed.jpg",
            samplePath = {
                sampleReads++
                "/sample.jpg"
            },
            probe = { path ->
                probed += path
                ProbeResult(successful = true, hasHttpResponse = true)
            },
            isSuccess = { it.successful },
            hasHttpResponse = { it.hasHttpResponse },
        )

        assertEquals(listOf("/fixed.jpg"), probed)
        assertEquals(0, sampleReads)
        assertEquals(1, attempts.size)
    }

    @Test
    fun `connection failure does not request fallback sample`() = runBlocking {
        var sampleReads = 0
        val attempts = ManwaImageFallbackProbePolicy.probeFixedThenFallback(
            fixedPath = "/fixed.jpg",
            samplePath = {
                sampleReads++
                "/sample.jpg"
            },
            probe = { ProbeResult(successful = false, hasHttpResponse = false) },
            isSuccess = { it.successful },
            hasHttpResponse = { it.hasHttpResponse },
        )

        assertEquals(0, sampleReads)
        assertEquals(1, attempts.size)
    }

    @Test
    fun `HTTP failure waits for sample and probes fallback`() = runBlocking {
        var sampleReads = 0
        val probed = mutableListOf<String>()
        val attempts = ManwaImageFallbackProbePolicy.probeFixedThenFallback(
            fixedPath = "/fixed.jpg",
            samplePath = {
                sampleReads++
                "/sample.jpg"
            },
            probe = { path ->
                probed += path
                ProbeResult(successful = path == "/sample.jpg", hasHttpResponse = true)
            },
            isSuccess = { it.successful },
            hasHttpResponse = { it.hasHttpResponse },
        )

        assertEquals(1, sampleReads)
        assertEquals(listOf("/fixed.jpg", "/sample.jpg"), probed)
        assertEquals(2, attempts.size)
        assertTrue(attempts.last().result.successful)
    }

    @Test
    fun `same fallback path is not probed twice`() = runBlocking {
        val probed = mutableListOf<String>()
        val attempts = ManwaImageFallbackProbePolicy.probeFixedThenFallback(
            fixedPath = "/fixed.jpg",
            samplePath = { "/fixed.jpg" },
            probe = { path ->
                probed += path
                ProbeResult(successful = false, hasHttpResponse = true)
            },
            isSuccess = { it.successful },
            hasHttpResponse = { it.hasHttpResponse },
        )

        assertEquals(listOf("/fixed.jpg"), probed)
        assertEquals(1, attempts.size)
    }

    @Test
    fun `cancellation from fixed probe propagates`() = runBlocking {
        var propagated = false
        try {
            ManwaImageFallbackProbePolicy.probeFixedThenFallback(
                fixedPath = "/fixed.jpg",
                samplePath = { "/sample.jpg" },
                probe = { throw CancellationException("probe cancelled") },
                isSuccess = { true },
                hasHttpResponse = { true },
            )
        } catch (cancelled: CancellationException) {
            propagated = cancelled.message == "probe cancelled"
        }
        assertTrue(propagated)
    }

    @Test
    fun `shared lazy sample provider is read once by concurrent waiters`() = runBlocking {
        val sampleReads = AtomicInteger()
        val shared = ManwaImageFallbackProbePolicy.sharedLazy(this) {
            sampleReads.incrementAndGet()
            "/sample.jpg"
        }
        assertEquals(0, sampleReads.get())

        val results = listOf(async { shared.await() }, async { shared.await() }).awaitAll()

        assertEquals(listOf("/sample.jpg", "/sample.jpg"), results)
        assertEquals(1, sampleReads.get())
        shared.cancel()
    }

    @Test
    fun `cancelling shared lazy child reaches its provider`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val providerCancelled = CompletableDeferred<Unit>()
        val shared = ManwaImageFallbackProbePolicy.sharedLazy<String?>(this) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                providerCancelled.complete(Unit)
            }
        }
        val waiter = async { shared.await() }
        started.await()

        shared.cancel()
        var waiterCancelled = false
        try {
            waiter.await()
        } catch (_: CancellationException) {
            waiterCancelled = true
        }
        providerCancelled.await()

        assertTrue(waiterCancelled)
        assertFalse(waiter.isActive)
    }
}
