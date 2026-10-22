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
    private val gates = ConcurrentHashMap<RuntimeRecoveryPolicy.Platform, RuntimeRecoveryPolicy.Gate>()
    private data class Revision(val config: Long, val platforms: Long, val network: Long = RuntimeNetworkGate.revision)
    private val revisions = ConcurrentHashMap<RuntimeRecoveryPolicy.Platform, Revision>()

    @Synchronized
    private fun acceptSample(platform: RuntimeRecoveryPolicy.Platform): Boolean {
        if (!RuntimeNetworkGate.isOnline()) {
            window.clear(platform)
            retryCallbacks.clear(platform)
            revisions.remove(platform)
            return false
        }
        val current = Revision(configRevision(platform), StartupPlatformPolicy.state.value.revision)
        if (revisions.put(platform, current) != current) {
            window.clear(platform)
            retryCallbacks.clear(platform)
        }
        return true
    }

    fun coverSample(platform: RuntimeRecoveryPolicy.Platform, source: String, resource: String, failed: Boolean) {
        if (!source.equals(activeSource(platform), true)) return
        if (!acceptSample(platform)) return
        val sample = RuntimeRecoveryPolicy.Sample(
            platform,
            source,
            resource,
            RuntimeRecoveryPolicy.SampleKind.COVER,
            failed,
            monotonicMillis(),
        )
        val thresholdReached = window.record(sample)
        if (!failed || !thresholdReached) return
        recover(platform, source)
    }

    fun visibleReaderTransportFailure(
        platform: RuntimeRecoveryPolicy.Platform,
        source: String,
        resource: String,
        retryPage: () -> Unit,
    ) {
        if (!source.equals(activeSource(platform), true)) return
        if (!acceptSample(platform)) return
        val revision = revisions[platform] ?: return
        val sample = RuntimeRecoveryPolicy.Sample(
            platform,
            source,
            resource,
            RuntimeRecoveryPolicy.SampleKind.VISIBLE_READER_PAGE,
            failed = true,
            atMs = monotonicMillis(),
        )
        retryCallbacks.record(platform, source, resource, sample.atMs) {
            if (RuntimeNetworkGate.isOnline() && revision.config == configRevision(platform) &&
                revision.platforms == StartupPlatformPolicy.state.value.revision &&
                revision.network == RuntimeNetworkGate.revision && isEnabled(platform)
            ) {
                retryPage()
            }
        }
        scope.launch {
            delay(RETRY_CALLBACK_WINDOW_MS)
            retryCallbacks.expire(monotonicMillis())
        }
        if (!window.record(sample)) return
        recover(platform, source)
    }

    /** Poll only this failed page; stale/hidden configurations and generations are discarded. */
    fun retryVisiblePageAfterAttempt(
        platform: RuntimeRecoveryPolicy.Platform,
        previousSource: String,
        attempt: PageAttemptScope,
    ) {
        val page = attempt.page
        val expectedGeneration = attempt.generation
        val expectedRevision = Revision(configRevision(platform), StartupPlatformPolicy.state.value.revision)
        val polls = AtomicInteger()
        val futureRef = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val task = attempt.coordinator.scheduleWatchdog {
            val currentSource = activeSource(platform)
            val sourceChanged = currentSource.isNotBlank() && !currentSource.equals(previousSource, ignoreCase = true)
            val current = expectedRevision.config == configRevision(platform) &&
                expectedRevision.platforms == StartupPlatformPolicy.state.value.revision &&
                expectedRevision.network == RuntimeNetworkGate.revision && isEnabled(platform)
            val fresh = current && RuntimeNetworkGate.isOnline() && RuntimeRecoveryPolicy.mayRetryVisiblePage(
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
            } else if (!current || !RuntimeNetworkGate.isOnline() || polls.incrementAndGet() > MAX_RETRY_POLLS ||
                page.generation.get() != expectedGeneration || !page.readerVisible || !sourceChanged
            ) {
                futureRef.get()?.cancel(false)
            }
        }
        task?.let { future ->
            futureRef.set(future)
            attempt.coordinator.watchdog.schedule({ future.cancel(false) }, RETRY_POLL_WINDOW_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun recover(platform: RuntimeRecoveryPolicy.Platform, source: String) {
        val expectedConfigRevision = configRevision(platform)
        val expectedPlatformRevision = StartupPlatformPolicy.state.value.revision
        val expectedNetworkRevision = RuntimeNetworkGate.revision
        if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision)) {
            retryCallbacks.clear(platform, source)
            return
        }
        val gate = gates.computeIfAbsent(platform) { RuntimeRecoveryPolicy.Gate(COOLDOWN_MS) }
        if (!gate.tryAcquire(monotonicMillis())) return
        scope.launch {
            try {
                if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision)) return@launch
                val candidates = when (platform) {
                    RuntimeRecoveryPolicy.Platform.MANWA -> RouteManager.runtimeImageRecoveryCandidates(source)
                    RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.runtimeImageRecoveryCandidates(source)
                    RuntimeRecoveryPolicy.Platform.PICACG -> PicacgRouteManager.runtimeImageRecoveryCandidates(source)
                }
                if (candidates.isEmpty()) return@launch
                val apiHealthy = when (platform) {
                    RuntimeRecoveryPolicy.Platform.MANWA -> NetworkModule.validateCurrentApi()
                    RuntimeRecoveryPolicy.Platform.JM -> JmNetworkModule.validateCurrentApi()
                    RuntimeRecoveryPolicy.Platform.PICACG -> PicacgRouteManager.validateCurrentApi()
                }
                if (!apiHealthy || !isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision)) return@launch
                val endpoint = candidates.firstOrNull { candidate ->
                    if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision)) return@firstOrNull false
                    when (platform) {
                        RuntimeRecoveryPolicy.Platform.MANWA -> SpeedTester.probeForDeprecatedScan(
                            candidate,
                            image = true,
                            imageSamplePath = RouteManager.loadProbeImgPath(),
                            runtimeRecovery = true,
                        )
                        RuntimeRecoveryPolicy.Platform.JM -> JmSpeedTester.probeForDeprecatedScan(
                            candidate,
                            image = true,
                            configuredLineUrl = JmRouteManager.baseUrl,
                            runtimeRecovery = true,
                        )
                        RuntimeRecoveryPolicy.Platform.PICACG -> PicacgSpeedTester.validateRuntimeImageEndpoint(candidate)
                    }
                } ?: return@launch
                if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision)) return@launch
                val switched = when (platform) {
                    RuntimeRecoveryPolicy.Platform.MANWA -> {
                        val changed = RouteManager.advanceRuntimeImageSourceAfterRecovery(
                            source,
                            expectedConfigRevision,
                            expectedPlatformRevision,
                            endpoint,
                        )
                        if (changed) NetworkModule.rebindImageRoute("image transport recovery")
                        changed
                    }
                    RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.advanceRuntimeImageDomainAfterRecovery(
                        source,
                        expectedConfigRevision,
                        expectedPlatformRevision,
                        endpoint,
                    )
                    RuntimeRecoveryPolicy.Platform.PICACG -> PicacgRouteManager.advanceRuntimeImageSourceAfterRecovery(
                        source,
                        expectedConfigRevision,
                        expectedPlatformRevision,
                        endpoint,
                    )
                }
                if (switched) retryCallbacks.take(platform, source, monotonicMillis()).forEach { it() }
            } finally {
                retryCallbacks.clear(platform, source)
                window.clear(platform)
                gate.release(monotonicMillis())
            }
        }
    }

    private fun isCurrentRoute(
        platform: RuntimeRecoveryPolicy.Platform,
        source: String,
        expectedConfigRevision: Long,
        expectedPlatformRevision: Long,
        expectedNetworkRevision: Long,
    ): Boolean = RuntimeNetworkGate.isOnline() && source.isNotBlank() &&
        activeSource(platform).equals(source, ignoreCase = true) && configRevision(platform) == expectedConfigRevision &&
        isEnabled(platform) && StartupPlatformPolicy.state.value.revision == expectedPlatformRevision &&
        RuntimeNetworkGate.revision == expectedNetworkRevision

    private fun isEnabled(platform: RuntimeRecoveryPolicy.Platform): Boolean = StartupPlatformPolicy.isEnabled(
        when (platform) {
            RuntimeRecoveryPolicy.Platform.MANWA -> ContentPlatform.MANWA
            RuntimeRecoveryPolicy.Platform.JM -> ContentPlatform.JM
            RuntimeRecoveryPolicy.Platform.PICACG -> ContentPlatform.PICACG
        },
    )

    private fun configRevision(platform: RuntimeRecoveryPolicy.Platform): Long = when (platform) {
        RuntimeRecoveryPolicy.Platform.MANWA -> RouteManager.configRevision
        RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.currentSpeedTestConfigRevision()
        RuntimeRecoveryPolicy.Platform.PICACG -> PicacgRouteManager.configRevision
    }

    private fun activeSource(platform: RuntimeRecoveryPolicy.Platform): String = when (platform) {
        RuntimeRecoveryPolicy.Platform.MANWA -> RouteManager.imgRewriteTarget ?: RouteManager.imgHost
        RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.activeImageDomain
        RuntimeRecoveryPolicy.Platform.PICACG -> PicacgRouteManager.currentImageHost.orEmpty()
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    private const val COOLDOWN_MS = 30_000L
    private const val RETRY_CALLBACK_WINDOW_MS = 10_000L
    private const val MAX_RETRY_POLLS = 40
    private const val RETRY_POLL_WINDOW_MS = 10_000L
}
