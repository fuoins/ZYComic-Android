package com.zycomic.app.net

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okhttp3.Call
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Cancellation source for an image attempt. */
enum class ImageCancelReason {
    RACE_LOSER,
    USER_RETRY,
    PAGE_HIDDEN,
    ABANDON_FAR,
}

/** Per-reader bounded resources: at most three in-flight image attempts and two racing workers. */
class ImageAttemptCoordinator : Closeable {
    private val threadIds = java.util.concurrent.atomic.AtomicInteger()
    private val factory = ThreadFactory { task ->
        Thread(task, "zy-img-race-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }
    private val raceExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        factory,
        ThreadPoolExecutor.AbortPolicy(),
    )
    val watchdog: ScheduledExecutorService = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "zy-img-watchdog").apply { isDaemon = true }
    }
    private val inFlight = Semaphore(3, true)

    fun tryAcquirePrimary(): Boolean = inFlight.tryAcquire()

    val availableAttemptCount: Int
        get() = inFlight.availablePermits()

    fun tryAcquireRace(): Boolean = inFlight.tryAcquire()

    fun releaseAttempt() = inFlight.release()

    fun submitRace(task: Runnable): Boolean = try {
        raceExecutor.execute(task)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun scheduleWatchdog(task: Runnable): ScheduledFuture<*> =
        watchdog.scheduleAtFixedRate(task, 250, 250, TimeUnit.MILLISECONDS)

    override fun close() {
        raceExecutor.shutdownNow()
        watchdog.shutdownNow()
    }
}

/** Owner token shared by the reader loader, source request and image interceptor. */
class PageAttemptScope(
    val page: ReaderPage,
    val generation: Int,
    val coordinator: ImageAttemptCoordinator,
    canRace: Boolean,
    val isSecondaryVisible: Boolean = false,
) {
    private val cancelReason = AtomicReference<ImageCancelReason?>(null)
    private val attemptReasons = ConcurrentHashMap<Int, ImageCancelReason>()
    private val calls = ConcurrentHashMap<Call, Int>()
    private val winnerClaimed = AtomicBoolean(false)
    @Volatile var canRace: Boolean = canRace
        private set
    val lastProgressAtNanos = AtomicLong(System.nanoTime())
    val receivedAnyBytes = AtomicBoolean(false)
    @Volatile
    var raceActive: Boolean = false
        private set

    val isCurrent: Boolean
        get() = cancelReason.get() == null && page.generation.get() == generation
    val hasOuterCall: Boolean
        get() = calls.values.any { it == -1 }

    fun registerCall(call: Call, attemptId: Int): Closeable {
        calls[call] = attemptId
        if (!isCurrent || attemptReasons.containsKey(attemptId)) call.cancel()
        return Closeable { calls.remove(call) }
    }

    fun cancelAttempt(attemptId: Int, reason: ImageCancelReason) {
        attemptReasons.putIfAbsent(attemptId, reason)
        calls.entries.filter { it.value == attemptId }.forEach { it.key.cancel() }
    }

    fun updateVisibility(isVisible: Boolean) {
        canRace = isVisible
        if (!isVisible) {
            calls.entries.filter { it.value > 0 }.forEach { (call, attemptId) ->
                attemptReasons.putIfAbsent(attemptId, ImageCancelReason.RACE_LOSER)
                call.cancel()
            }
        }
    }

    fun cancel(reason: ImageCancelReason) {
        if (cancelReason.compareAndSet(null, reason)) {
            calls.keys.forEach(Call::cancel)
        }
    }

    fun reasonFor(call: Call): ImageCancelReason? =
        cancelReason.get() ?: calls[call]?.let(attemptReasons::get)

    fun claimWinner(): Boolean = isCurrent && winnerClaimed.compareAndSet(false, true)

    fun releaseWinnerClaim() {
        if (isCurrent) winnerClaimed.compareAndSet(true, false)
    }

    fun cancelLosers(winnerId: Int) {
        calls.entries.filter { it.value != winnerId && it.value >= 0 }.forEach { (call, attemptId) ->
            attemptReasons.putIfAbsent(attemptId, ImageCancelReason.RACE_LOSER)
            call.cancel()
        }
    }

    fun beginRace() {
        raceActive = true
        page.raceActive = true
        page.progress = 0
    }

    fun endRace() {
        raceActive = false
        page.raceActive = false
    }

    fun reportProgress(bytesRead: Long, contentLength: Long) {
        if (bytesRead > 0) {
            receivedAnyBytes.set(true)
            lastProgressAtNanos.set(System.nanoTime())
            page.slow = false
        }
        if (raceActive) {
            page.progress = 0
        } else {
            page.progress = if (contentLength > 0) {
                (100L * bytesRead / contentLength).toInt().coerceIn(0, 100)
            } else {
                -1
            }
        }
    }
}
