package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Coordinates runtime image recovery independently of measurements and speed-test state. */
internal object RuntimeImageRecovery {
    private val window = RuntimeRecoveryPolicy.Window()
    private val retryCallbacks = RuntimeRecoveryPolicy.RetryCallbacks()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One gate per platform prevents simultaneous failures on stale sources from swinging routes.
    private val gates = ConcurrentHashMap<RuntimeRecoveryPolicy.Platform, RuntimeRecoveryPolicy.Gate>()

    fun coverSample(platform: RuntimeRecoveryPolicy.Platform, source: String, resource: String, failed: Boolean) {
        val sample = RuntimeRecoveryPolicy.Sample(
            platform,
            source,
            resource,
            RuntimeRecoveryPolicy.SampleKind.COVER,
            failed,
            System.nanoTime() / 1_000_000L,
        )
        if (!window.record(sample)) return
        recover(platform, source)
    }

    fun visibleReaderTransportFailure(
        platform: RuntimeRecoveryPolicy.Platform,
        source: String,
        resource: String,
        retryPage: () -> Unit,
    ) {
        val sample = RuntimeRecoveryPolicy.Sample(
            platform,
            source,
            resource,
            RuntimeRecoveryPolicy.SampleKind.VISIBLE_READER_PAGE,
            failed = true,
            atMs = System.nanoTime() / 1_000_000L,
        )
        retryCallbacks.record(platform, source, resource, sample.atMs, retryPage)
        scope.launch {
            delay(RETRY_CALLBACK_WINDOW_MS)
            retryCallbacks.expire(monotonicMillis())
        }
        if (!window.record(sample)) return
        recover(platform, source)
    }

    /** Poll only this failed page; stale/hidden generations are discarded and never retained. */
    fun retryVisiblePageAfterAttempt(
        platform: RuntimeRecoveryPolicy.Platform,
        previousSource: String,
        attempt: PageAttemptScope,
    ) {
        val page = attempt.page
        val expectedGeneration = attempt.generation
        val polls = AtomicInteger()
        val futureRef = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val task = attempt.coordinator.scheduleWatchdog {
            val currentSource = activeSource(platform)
            val sourceChanged = currentSource.isNotBlank() && !currentSource.equals(previousSource, ignoreCase = true)
            val fresh = RuntimeRecoveryPolicy.mayRetryVisiblePage(
                expectedGeneration = expectedGeneration,
                currentGeneration = page.generation.get(),
                visible = page.readerVisible,
                attemptActive = page.activeAttempt.get() != null,
                failed = page.status is Page.State.Error,
                sourceChanged = sourceChanged,
            )
            if (fresh) {
                page.chapter.pageLoader?.retryAfterRuntimeRecovery(page, expectedGeneration)
                futureRef.get()?.cancel(false)
            } else if (polls.incrementAndGet() > MAX_RETRY_POLLS || page.generation.get() != expectedGeneration ||
                !page.readerVisible || !sourceChanged
            ) {
                futureRef.get()?.cancel(false)
                return@scheduleWatchdog
            }
        }
        // scheduleWatchdog is periodic; bound it even when the attempt remains stuck or the page goes stale.
        task?.let { future ->
            futureRef.set(future)
            attempt.coordinator.watchdog.schedule({ future.cancel(false) }, RETRY_POLL_WINDOW_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun recover(platform: RuntimeRecoveryPolicy.Platform, source: String) {
        val expectedConfigRevision = configRevision(platform)
        val expectedPlatformRevision = StartupPlatformPolicy.state.value.revision
        if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision)) {
            retryCallbacks.clear(platform, source)
            return
        }
        val gate = gates.computeIfAbsent(platform) { RuntimeRecoveryPolicy.Gate(COOLDOWN_MS) }
        if (!gate.tryAcquire(monotonicMillis())) return
        scope.launch {
            try {
                if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision)) return@launch
                val apiHealthy = when (platform) {
                    RuntimeRecoveryPolicy.Platform.MANWA -> NetworkModule.validateCurrentApi()
                    RuntimeRecoveryPolicy.Platform.JM -> JmNetworkModule.validateCurrentApi()
                }
                if (!apiHealthy || !isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision)) return@launch
                val switched = when (platform) {
                    RuntimeRecoveryPolicy.Platform.MANWA -> {
                        val changed = RouteManager.advanceRuntimeImageSourceAfterRecovery(
                            source,
                            expectedConfigRevision,
                            expectedPlatformRevision,
                        )
                        if (changed) NetworkModule.rebindImageRoute("image transport recovery")
                        changed
                    }
                    RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.advanceRuntimeImageDomainAfterRecovery(
                        source,
                        expectedConfigRevision,
                        expectedPlatformRevision,
                    )
                }
                if (switched) {
                    retryCallbacks.take(platform, source, monotonicMillis()).forEach { retry -> retry() }
                } else {
                    retryCallbacks.clear(platform, source)
                }
            } finally {
                gate.release(monotonicMillis())
            }
        }
    }

    private fun isCurrentRoute(
        platform: RuntimeRecoveryPolicy.Platform,
        source: String,
        expectedConfigRevision: Long,
        expectedPlatformRevision: Long,
    ): Boolean =
        source.isNotBlank() && activeSource(platform).equals(source, ignoreCase = true) &&
            configRevision(platform) == expectedConfigRevision &&
            StartupPlatformPolicy.isEnabled(
                if (platform == RuntimeRecoveryPolicy.Platform.MANWA) ContentPlatform.MANWA else ContentPlatform.JM,
            ) && StartupPlatformPolicy.state.value.revision == expectedPlatformRevision

    private fun configRevision(platform: RuntimeRecoveryPolicy.Platform): Long = when (platform) {
        RuntimeRecoveryPolicy.Platform.MANWA -> RouteManager.configRevision
        RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.currentSpeedTestConfigRevision()
    }

    private fun activeSource(platform: RuntimeRecoveryPolicy.Platform): String = when (platform) {
        RuntimeRecoveryPolicy.Platform.MANWA -> RouteManager.imgRewriteTarget ?: RouteManager.imgHost
        RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.activeImageDomain
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    private const val COOLDOWN_MS = 30_000L
    private const val RETRY_CALLBACK_WINDOW_MS = 10_000L
    private const val MAX_RETRY_POLLS = 40
    private const val RETRY_POLL_WINDOW_MS = 10_000L
}
