package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import kotlin.coroutines.resume

/** Fair shared endpoint scheduler; transport clients and endpoint identities remain platform-isolated. */
internal class FairSpeedTestProbeScheduler(
    private val maxConcurrent: Int = 24,
    private val picacgMaxConcurrent: Int = 4,
) {
    private enum class State { QUEUED, GRANTED, STARTED, CANCELLED, FINISHED }

    private class Waiter(
        val platform: ContentPlatform,
        val queuedAtNanos: Long,
    ) {
        lateinit var resume: () -> Unit
        var state: State = State.QUEUED
    }

    private val lock = Any()
    private val platforms = listOf(ContentPlatform.MANWA, ContentPlatform.PICACG, ContentPlatform.JM)
    private val queues = platforms.associateWith { ArrayDeque<Waiter>() }
    private val activeByPlatform = platforms.associateWith { 0 }.toMutableMap()
    private var active = 0
    private var lastGrantedPlatform = -1

    init {
        require(maxConcurrent > 0)
        require(picacgMaxConcurrent in 1..maxConcurrent)
    }

    internal val queuedCount: Int
        get() = synchronized(lock) { queues.values.sumOf { it.size } }

    suspend fun <T> withPermit(
        platform: ContentPlatform,
        onQueueWait: (Long) -> Unit = {},
        block: suspend () -> T,
    ): T {
        val waiter = Waiter(platform, System.nanoTime())
        suspendCancellableCoroutine<Unit> { continuation ->
            waiter.resume = { continuation.resume(Unit) }
            continuation.invokeOnCancellation { cancel(waiter) }
            val toResume = synchronized(lock) {
                if (waiter.state == State.QUEUED) {
                    queues.getValue(platform).addLast(waiter)
                    pump()
                } else {
                    emptyList()
                }
            }
            toResume.forEach { it.resume() }
        }
        synchronized(lock) {
            if (waiter.state != State.GRANTED) throw CancellationException("Speed-test permit was cancelled")
            waiter.state = State.STARTED
        }
        return try {
            runCatching { onQueueWait(((System.nanoTime() - waiter.queuedAtNanos) / 1_000_000L).coerceAtLeast(0L)) }
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            release(waiter)
        }
    }

    private fun cancel(waiter: Waiter) {
        val toResume = synchronized(lock) {
            when (waiter.state) {
                State.QUEUED -> {
                    waiter.state = State.CANCELLED
                    queues.getValue(waiter.platform).remove(waiter)
                }
                State.GRANTED -> {
                    waiter.state = State.CANCELLED
                    active--
                    activeByPlatform[waiter.platform] = activeByPlatform.getValue(waiter.platform) - 1
                }
                State.STARTED, State.CANCELLED, State.FINISHED -> Unit
            }
            pump()
        }
        toResume.forEach { it.resume() }
    }

    private fun release(waiter: Waiter) {
        val toResume = synchronized(lock) {
            if (waiter.state == State.STARTED) {
                waiter.state = State.FINISHED
                active--
                activeByPlatform[waiter.platform] = activeByPlatform.getValue(waiter.platform) - 1
            }
            pump()
        }
        toResume.forEach { it.resume() }
    }

    /** Select one waiter at a time in platform round-robin order, skipping capped/empty queues. */
    private fun pump(): List<Waiter> {
        val granted = mutableListOf<Waiter>()
        while (active < maxConcurrent) {
            var selectedIndex = -1
            for (offset in 1..platforms.size) {
                val index = (lastGrantedPlatform + offset).mod(platforms.size)
                val platform = platforms[index]
                val perPlatformLimit = if (platform == ContentPlatform.PICACG) picacgMaxConcurrent else maxConcurrent
                if (activeByPlatform.getValue(platform) < perPlatformLimit && queues.getValue(platform).isNotEmpty()) {
                    selectedIndex = index
                    break
                }
            }
            if (selectedIndex < 0) break
            val platform = platforms[selectedIndex]
            val waiter = queues.getValue(platform).removeFirst()
            if (waiter.state != State.QUEUED) continue
            waiter.state = State.GRANTED
            active++
            activeByPlatform[platform] = activeByPlatform.getValue(platform) + 1
            lastGrantedPlatform = selectedIndex
            granted += waiter
        }
        return granted
    }

    companion object {
        fun global() = FairSpeedTestProbeScheduler()
    }
}

/** One cap and fair scheduling queue are shared across all platform speed tests. */
internal object SpeedTestProbeBudget {
    const val MAX_CONCURRENT_ENDPOINTS = 24
    const val MAX_PICACG_CONCURRENT_ENDPOINTS = 4
    val scheduler = FairSpeedTestProbeScheduler(MAX_CONCURRENT_ENDPOINTS, MAX_PICACG_CONCURRENT_ENDPOINTS)
}
