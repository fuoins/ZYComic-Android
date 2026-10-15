package com.zycomic.app.net

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPageLoadStatus
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Cancellation source for an image attempt. */
enum class ImageCancelReason {
    RACE_LOSER,
    USER_RETRY,
    PAGE_HIDDEN,
    ABANDON_FAR,
}

/** Per-reader bounded resources: primary workers and extra visible-page race attempts are separate. */
class ImageAttemptCoordinator(primaryCapacity: Int = 4) : Closeable {
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
    private val primaryInFlight = Semaphore(primaryCapacity.coerceIn(1, 10), true)
    private val raceInFlight = Semaphore(2, true)

    fun tryAcquirePrimary(): Boolean = primaryInFlight.tryAcquire()

    val availablePrimaryAttemptCount: Int
        get() = primaryInFlight.availablePermits()

    fun tryAcquireRace(): Boolean = raceInFlight.tryAcquire()

    fun releasePrimaryAttempt() = primaryInFlight.release()

    fun releaseRaceAttempt() = raceInFlight.release()

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
    queueWaitNanos: Long = 0L,
    urlResolutionTimeNanos: Long = 0L,
) {
    private val cancelReason = AtomicReference<ImageCancelReason?>(null)
    private val attemptReasons = ConcurrentHashMap<Int, ImageCancelReason>()
    private val calls = ConcurrentHashMap<Call, Int>()
    private val winnerClaimed = AtomicBoolean(false)

    @Volatile var canRace: Boolean = canRace
        private set
    val lastProgressAtNanos = AtomicLong(0L)
    val requestStartedAtNanos = AtomicLong(0L)
    val receivedAnyBytes = AtomicBoolean(false)
    private val activeEndpointRequests = AtomicInteger(0)
    private val queueNanos = AtomicLong(queueWaitNanos.coerceAtLeast(0L))
    private val imageUrlResolutionNanos = AtomicLong(urlResolutionTimeNanos.coerceAtLeast(0L))
    private val networkNanos = AtomicLong(0L)
    private val decryptNanos = AtomicLong(0L)
    private val cacheNanos = AtomicLong(0L)
    private val validationNanos = AtomicLong(0L)
    private val permitWaitStartedAtNanos = AtomicLong(0L)

    @Volatile
    var raceActive: Boolean = false
        private set

    val isCurrent: Boolean
        get() = cancelReason.get() == null && page.generation.get() == generation
    val hasOuterCall: Boolean
        get() = calls.values.any { it == -1 }
    val hasActiveEndpointRequest: Boolean
        get() = activeEndpointRequests.get() > 0

    fun waitingForRequestPermit() {
        permitWaitStartedAtNanos.compareAndSet(0L, System.nanoTime())
        if (isCurrent) page.loadStatus = ReaderPageLoadStatus.WaitingForRequest
    }

    fun endpointRequestStarted(attemptId: Int, totalAttempts: Int) {
        val now = System.nanoTime()
        val permitWaitStartedAt = permitWaitStartedAtNanos.getAndSet(0L)
        if (permitWaitStartedAt > 0L) queueNanos.addAndGet((now - permitWaitStartedAt).coerceAtLeast(0L))
        requestStartedAtNanos.set(now)
        lastProgressAtNanos.set(now)
        activeEndpointRequests.incrementAndGet()
        if (isCurrent) {
            val retryRound = page.automaticRetryRound.get()
            page.loadStatus = when {
                retryRound > 1 -> ReaderPageLoadStatus.TryingFallback(attemptId + 1, totalAttempts, retryRound)
                attemptId == 0 -> ReaderPageLoadStatus.Requesting
                else -> ReaderPageLoadStatus.TryingFallback(attemptId + 1, totalAttempts)
            }
        }
    }

    fun endpointRequestFinished() {
        activeEndpointRequests.decrementAndGet()
    }

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
        // Keep progressing hedge calls alive across a visibility change. A stalled page may
        // still release secondary calls; its primary request retains its normal ownership.
        if (!isVisible && !hasRecentByteProgress()) {
            calls.entries.filter { it.value > 0 }.forEach { (call, attemptId) ->
                attemptReasons.putIfAbsent(attemptId, ImageCancelReason.RACE_LOSER)
                call.cancel()
            }
        }
    }

    /** True when this attempt has received bytes recently enough to count as active progress. */
    fun hasRecentByteProgress(withinMillis: Long = 1_500L): Boolean {
        if (!receivedAnyBytes.get()) return false
        val lastProgress = lastProgressAtNanos.get()
        return lastProgress > 0L &&
            System.nanoTime() - lastProgress < TimeUnit.MILLISECONDS.toNanos(withinMillis)
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
        if (!isCurrent) return
        if (bytesRead > 0) {
            receivedAnyBytes.set(true)
            lastProgressAtNanos.set(System.nanoTime())
            page.loadStatus = ReaderPageLoadStatus.Receiving(
                contentLength.takeIf { it > 0 }?.let { (100L * bytesRead / it).toInt().coerceIn(0, 100) },
            )
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

    fun recordEndpointAttempt(endpoint: DomainIp): Boolean =
        page.attemptedImageEndpoints.add(endpoint.normalizedForComparison()).also { added ->
            if (added) page.endpointsAttemptedInRound.incrementAndGet()
        }

    fun beginNextAutomaticRound(): Boolean {
        while (true) {
            val round = page.automaticRetryRound.get()
            if (round >= MAX_AUTOMATIC_RETRY_ROUNDS) return false
            if (page.automaticRetryRound.compareAndSet(round, round + 1)) {
                page.endpointsAttemptedInRound.set(0)
                return true
            }
        }
    }

    fun addNetworkTime(nanos: Long) {
        networkNanos.addAndGet(nanos.coerceAtLeast(0L))
    }

    fun addDecryptTime(nanos: Long) {
        decryptNanos.addAndGet(nanos.coerceAtLeast(0L))
    }

    fun addCacheTime(nanos: Long) {
        cacheNanos.addAndGet(nanos.coerceAtLeast(0L))
    }

    fun addValidationTime(nanos: Long) {
        validationNanos.addAndGet(nanos.coerceAtLeast(0L))
    }

    fun timingSummary(): String {
        val waitStartedAt = permitWaitStartedAtNanos.get()
        val pendingQueueTime = if (waitStartedAt > 0L) {
            (System.nanoTime() - waitStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        val queueTime = queueNanos.get() + pendingQueueTime
        return listOf(
            "resolveUrl=${millis(imageUrlResolutionNanos.get())}ms",
            "queue=${millis(queueTime)}ms",
            "network=${millis(networkNanos.get())}ms",
            "decrypt=${millis(decryptNanos.get())}ms",
            "cache=${millis(cacheNanos.get())}ms",
            "validation=${millis(validationNanos.get())}ms",
        ).joinToString(" ")
    }

    private fun millis(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos)

    companion object {
        const val MAX_AUTOMATIC_RETRY_ROUNDS = 3
        const val MAX_ENDPOINTS_PER_ROUND = 3
    }
}
