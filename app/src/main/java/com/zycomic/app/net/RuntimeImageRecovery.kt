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

/** Picacg exposes two independently measured image services; other platforms keep a single stream. */
internal enum class PicacgImageService { COVER, BODY }

/** Coordinates runtime image recovery independently of measurements and speed-test state. */
internal object RuntimeImageRecovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Track(val platform: RuntimeRecoveryPolicy.Platform, val service: PicacgImageService? = null)
    private val windows = ConcurrentHashMap<Track, RuntimeRecoveryPolicy.Window>()
    private val retryCallbacks = ConcurrentHashMap<Track, RuntimeRecoveryPolicy.RetryCallbacks>()
    private val gates = ConcurrentHashMap<Track, RuntimeRecoveryPolicy.Gate>()
    private data class Revision(val config: Long, val platforms: Long, val network: Long = RuntimeNetworkGate.revision)
    private val revisions = ConcurrentHashMap<Track, Revision>()

    @Synchronized
    private fun acceptSample(platform: RuntimeRecoveryPolicy.Platform, service: PicacgImageService? = null): Boolean {
        val track = Track(platform, service)
        if (!RuntimeNetworkGate.isOnline()) {
            windows[track]?.clear()
            retryCallbacks[track]?.clear(platform)
            revisions.remove(track)
            return false
        }
        val current = Revision(configRevision(platform), StartupPlatformPolicy.state.value.revision)
        if (revisions.put(track, current) != current) {
            windows[track]?.clear()
            retryCallbacks[track]?.clear(platform)
        }
        return true
    }

    fun coverSample(
        platform: RuntimeRecoveryPolicy.Platform,
        source: String,
        resource: String,
        failed: Boolean,
        service: PicacgImageService? = null,
    ) {
        val track = Track(platform, service)
        if (!source.equals(activeSource(platform, service), true)) return
        if (!acceptSample(platform, service)) return
        val sample = RuntimeRecoveryPolicy.Sample(
            platform,
            source,
            resource,
            RuntimeRecoveryPolicy.SampleKind.COVER,
            failed,
            monotonicMillis(),
        )
        val thresholdReached = window(track).record(sample)
        if (!failed || !thresholdReached) return
        recover(platform, source, service)
    }

    fun visibleReaderTransportFailure(
        platform: RuntimeRecoveryPolicy.Platform,
        source: String,
        resource: String,
        retryPage: () -> Unit,
        service: PicacgImageService? = null,
    ) {
        val track = Track(platform, service)
        if (!source.equals(activeSource(platform, service), true)) return
        if (!acceptSample(platform, service)) return
        val revision = revisions[track] ?: return
        val sample = RuntimeRecoveryPolicy.Sample(
            platform,
            source,
            resource,
            RuntimeRecoveryPolicy.SampleKind.VISIBLE_READER_PAGE,
            failed = true,
            atMs = monotonicMillis(),
        )
        callbacks(track).record(platform, source, resource, sample.atMs) {
            if (RuntimeNetworkGate.isOnline() && revision.config == configRevision(platform) &&
                revision.platforms == StartupPlatformPolicy.state.value.revision &&
                revision.network == RuntimeNetworkGate.revision && isEnabled(platform)
            ) {
                retryPage()
            }
        }
        scope.launch {
            delay(RETRY_CALLBACK_WINDOW_MS)
            callbacks(track).expire(monotonicMillis())
        }
        if (!window(track).record(sample)) return
        recover(platform, source, service)
    }

    /** Poll only this failed page; stale/hidden configurations and generations are discarded. */
    fun retryVisiblePageAfterAttempt(
        platform: RuntimeRecoveryPolicy.Platform,
        previousSource: String,
        attempt: PageAttemptScope,
        service: PicacgImageService? = null,
    ) {
        val page = attempt.page
        val expectedGeneration = attempt.generation
        val expectedRevision = Revision(configRevision(platform), StartupPlatformPolicy.state.value.revision)
        val polls = AtomicInteger()
        val futureRef = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val task = attempt.coordinator.scheduleWatchdog {
            val currentSource = activeSource(platform, service)
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

    private fun recover(platform: RuntimeRecoveryPolicy.Platform, source: String, service: PicacgImageService? = null) {
        val track = Track(platform, service)
        val expectedConfigRevision = configRevision(platform)
        val expectedPlatformRevision = StartupPlatformPolicy.state.value.revision
        val expectedNetworkRevision = RuntimeNetworkGate.revision
        if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision, service)) {
            callbacks(track).clear(platform, source)
            return
        }
        val gate = gates.computeIfAbsent(track) { RuntimeRecoveryPolicy.Gate(COOLDOWN_MS) }
        if (!gate.tryAcquire(monotonicMillis())) return
        scope.launch {
            try {
                if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision, service)) return@launch
                val candidates = when (platform) {
                    RuntimeRecoveryPolicy.Platform.MANWA -> RouteManager.runtimeImageRecoveryCandidates(source)
                    RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.runtimeImageRecoveryCandidates(source)
                    RuntimeRecoveryPolicy.Platform.PICACG -> PicacgRouteManager.runtimeImageRecoveryCandidates(source, service)
                }
                if (candidates.isEmpty()) return@launch
                val apiHealthy = when (platform) {
                    RuntimeRecoveryPolicy.Platform.MANWA -> NetworkModule.validateCurrentApi()
                    RuntimeRecoveryPolicy.Platform.JM -> JmNetworkModule.validateCurrentApi()
                    RuntimeRecoveryPolicy.Platform.PICACG -> PicacgRouteManager.validateCurrentApi()
                }
                if (!apiHealthy || !isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision, service)) return@launch
                val endpoint = candidates.firstOrNull { candidate ->
                    if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision, service)) return@firstOrNull false
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
                        RuntimeRecoveryPolicy.Platform.PICACG -> PicacgSpeedTester.validateRuntimeImageEndpoint(
                            candidate,
                            content = service == PicacgImageService.BODY,
                        )
                    }
                } ?: return@launch
                if (!isCurrentRoute(platform, source, expectedConfigRevision, expectedPlatformRevision, expectedNetworkRevision, service)) return@launch
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
                        service,
                    )
                }
                if (switched) callbacks(track).take(platform, source, monotonicMillis()).forEach { it() }
            } finally {
                callbacks(track).clear(platform, source)
                windows[track]?.clear()
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
        service: PicacgImageService? = null,
    ): Boolean = RuntimeNetworkGate.isOnline() && source.isNotBlank() &&
        activeSource(platform, service).equals(source, ignoreCase = true) && configRevision(platform) == expectedConfigRevision &&
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

    private fun activeSource(platform: RuntimeRecoveryPolicy.Platform, service: PicacgImageService? = null): String = when (platform) {
        RuntimeRecoveryPolicy.Platform.MANWA -> RouteManager.imgRewriteTarget ?: RouteManager.imgHost
        RuntimeRecoveryPolicy.Platform.JM -> JmRouteManager.activeImageDomain
        RuntimeRecoveryPolicy.Platform.PICACG -> PicacgRouteManager.imageSourceFor(service == PicacgImageService.BODY).orEmpty()
    }

    private fun window(track: Track) = windows.computeIfAbsent(track) { RuntimeRecoveryPolicy.Window() }
    private fun callbacks(track: Track) = retryCallbacks.computeIfAbsent(track) { RuntimeRecoveryPolicy.RetryCallbacks() }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    private const val COOLDOWN_MS = 30_000L
    private const val RETRY_CALLBACK_WINDOW_MS = 10_000L
    private const val MAX_RETRY_POLLS = 40
    private const val RETRY_POLL_WINDOW_MS = 10_000L
}
