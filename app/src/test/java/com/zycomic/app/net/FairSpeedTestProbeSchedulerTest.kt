package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class FairSpeedTestProbeSchedulerTest {
    @Test
    fun `shared cap and Picacg cap are both respected`() = runBlocking {
        val scheduler = FairSpeedTestProbeScheduler(maxConcurrent = 3, picacgMaxConcurrent = 1)
        val active = AtomicInteger()
        val activePicacg = AtomicInteger()
        val peak = AtomicInteger()
        val peakPicacg = AtomicInteger()

        coroutineScope {
            (0 until 45).map { index ->
                val platform = when (index % 3) {
                    0 -> ContentPlatform.MANWA
                    1 -> ContentPlatform.PICACG
                    else -> ContentPlatform.JM
                }
                async {
                    scheduler.withPermit(platform) {
                        peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
                        if (platform == ContentPlatform.PICACG) {
                            peakPicacg.updateAndGet { maxOf(it, activePicacg.incrementAndGet()) }
                        }
                        try {
                            delay(4)
                        } finally {
                            active.decrementAndGet()
                            if (platform == ContentPlatform.PICACG) activePicacg.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        assertTrue(peak.get() <= 3)
        assertTrue(peakPicacg.get() <= 1)
    }

    @Test
    fun `round robin gives other platforms service ahead of long Manwa queue`() = runBlocking {
        val scheduler = FairSpeedTestProbeScheduler(maxConcurrent = 1, picacgMaxConcurrent = 1)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            scheduler.withPermit(ContentPlatform.MANWA) {
                order += "manwa-1"
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val queued = listOf(
            async { scheduler.withPermit(ContentPlatform.MANWA) { order += "manwa-2" } },
            async { scheduler.withPermit(ContentPlatform.MANWA) { order += "manwa-3" } },
            async { scheduler.withPermit(ContentPlatform.PICACG) { order += "picacg" } },
            async { scheduler.withPermit(ContentPlatform.JM) { order += "jm" } },
        )
        withTimeout(2_000) {
            while (scheduler.queuedCount != queued.size) delay(1)
        }

        releaseFirst.complete(Unit)
        (listOf(first) + queued).awaitAll()

        assertEquals(listOf("manwa-1", "picacg", "jm", "manwa-2", "manwa-3"), order)
    }

    @Test
    fun `cancelled queued waiter is removed without running its probe`() = runBlocking {
        val scheduler = FairSpeedTestProbeScheduler(maxConcurrent = 1, picacgMaxConcurrent = 1)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val ranCancelledProbe = AtomicReference(false)
        val first = async {
            scheduler.withPermit(ContentPlatform.MANWA) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val cancelled = async {
            scheduler.withPermit(ContentPlatform.JM) { ranCancelledProbe.set(true) }
        }
        withTimeout(2_000) {
            while (scheduler.queuedCount != 1) delay(1)
        }
        cancelled.cancel()
        cancelled.join()
        assertEquals(0, scheduler.queuedCount)

        releaseFirst.complete(Unit)
        first.await()
        assertFalse(ranCancelledProbe.get())
    }

    @Test
    fun `cancel before enqueue does not leave a queued waiter`() = runBlocking {
        val scheduler = FairSpeedTestProbeScheduler(maxConcurrent = 1, picacgMaxConcurrent = 1)
        val ranProbe = AtomicReference(false)
        val cancelled = launch(start = CoroutineStart.LAZY) {
            scheduler.withPermit(ContentPlatform.JM) { ranProbe.set(true) }
        }
        cancelled.cancel()
        cancelled.join()

        assertEquals(0, scheduler.queuedCount)
        assertFalse(ranProbe.get())
        scheduler.withPermit(ContentPlatform.MANWA) { }
    }

    @Test
    fun `cancellation after grant but before probe releases permit without probing`() = runBlocking {
        val scheduler = FairSpeedTestProbeScheduler(maxConcurrent = 1, picacgMaxConcurrent = 1)
        val ranProbe = AtomicReference(false)
        val cancelled = async(start = CoroutineStart.LAZY) {
            val job: Job = currentCoroutineContext()[Job]!!
            scheduler.withPermit(ContentPlatform.JM, { job.cancel() }) { ranProbe.set(true) }
        }
        cancelled.start()
        cancelled.join()

        assertFalse(ranProbe.get())
        scheduler.withPermit(ContentPlatform.MANWA) { }
        assertEquals(0, scheduler.queuedCount)
    }

    @Test
    fun `queue wait callback is separate from measured probe time`() = runBlocking {
        val scheduler = FairSpeedTestProbeScheduler(maxConcurrent = 1, picacgMaxConcurrent = 1)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val recordedWaitMs = AtomicLong(-1L)
        val probeElapsedMs = AtomicLong(-1L)
        val first = async {
            scheduler.withPermit(ContentPlatform.MANWA) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val queuedProbe = async {
            scheduler.withPermit(ContentPlatform.JM, { recordedWaitMs.set(it) }) {
                val probeStarted = System.nanoTime()
                delay(10)
                probeElapsedMs.set((System.nanoTime() - probeStarted) / 1_000_000L)
            }
        }
        withTimeout(2_000) {
            while (scheduler.queuedCount != 1) delay(1)
        }
        delay(60)
        releaseFirst.complete(Unit)
        listOf(first, queuedProbe).awaitAll()

        assertTrue(recordedWaitMs.get() >= 40L)
        assertTrue(probeElapsedMs.get() in 5L..200L)
        assertTrue(recordedWaitMs.get() > probeElapsedMs.get())
    }

    @Test
    fun `queue wait timing is reported in its own phase`() {
        var now = 0L
        var published = emptyMap<SpeedTestTimings.Phase, Long>()
        val timings = SpeedTestTimings(nanoTime = { now }, publish = { published = it })
        val probe = timings.start(SpeedTestTimings.Phase.LINES)
        now += 7_000_000L
        probe.close()
        timings.record(SpeedTestTimings.Phase.PROBE_QUEUE_WAIT, 41L)
        now += 3_000_000L
        timings.finish()

        assertEquals(7L, published[SpeedTestTimings.Phase.LINES])
        assertEquals(41L, published[SpeedTestTimings.Phase.PROBE_QUEUE_WAIT])
        assertEquals(10L, published[SpeedTestTimings.Phase.TOTAL])
    }
}
