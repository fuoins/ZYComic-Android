package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Independent Picacg API and real-image probes. No endpoint is successful by assumption or synthesis. */
object PicacgSpeedTester {
    /** 快测拿到多少个“快速成功”后才停止派发新候选；与 JM/漫蛙一致，放行后后台继续补到 8 个。 */
    private const val QUICK_FILL_TARGET = 8

    enum class Stage { PREPARING_API, TESTING_API, PREPARING_IMAGES, TESTING_IMAGES, IMAGE_BLOCKED, COMPLETE }

    data class Progress(
        val stage: Stage,
        val completed: Int = 0,
        val total: Int = 0,
        val successful: Int = 0,
        val failed: Int = 0,
    )

    data class Probe(
        val endpoint: DomainIp,
        val success: Boolean,
        val elapsedMs: Long,
        val httpStatus: Int?,
        val contentType: String? = null,
        val imageDecoded: Boolean? = null,
        val measuredAtEpochMs: Long? = null,
        val testedThisRound: Boolean = false,
        val quickSelected: Boolean = false,
        /** False = cover (imgproxy) probe; true = reader-page (storage-b bucket) probe. */
        val content: Boolean = false,
    )

    data class Snapshot(
        val startedAtEpochMs: Long,
        val completedAtEpochMs: Long,
        val remoteVersion: Int?,
        val apiProbes: List<Probe>,
        val imageProbes: List<Probe>,
        val imageBlockedReason: String?,
        val revision: Long,
        // Published selection is log metadata, not the mutable current route.
        val roundSelectedLine: DomainIp? = null,
        val roundSelectedImage: DomainIp? = null,
        val networkIdentity: String? = null,
    ) {
        val apiComplete: Boolean get() = apiProbes.isNotEmpty()
        val imageComplete: Boolean get() = imageProbes.isNotEmpty()
        val complete: Boolean get() = apiComplete && imageComplete
    }

    fun logSummary(probes: List<Probe>): String = SpeedTestLogSummary(
        probes.map { SpeedTestLogSummary.Result(it.testedThisRound, it.success, it.quickSelected) },
    ).text()

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()
    private val _progress = MutableStateFlow<Progress?>(null)
    /** Latest probe-stage/endpoint progress, also available to the settings log screen. */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()
    private val runLock = Mutex()
    private val baseProbeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private data class ApiTarget(val url: String, val endpoint: DomainIp, val identity: PicacgSpeedTestHistoryPolicy.Identity)
    // content=false -> cover probe (imgproxy tile); content=true -> reader-page probe (storage-b bucket).
    private data class ImageTarget(
        val url: String,
        val endpoint: DomainIp,
        val identity: PicacgSpeedTestHistoryPolicy.Identity,
        val content: Boolean = false,
    )

    suspend fun run(
        reuseApi: Boolean = false,
        reuseImages: Boolean = false,
        fastApi: Boolean = false,
        fastImages: Boolean = false,
        reconcileConfig: Boolean = false,
        forceFresh: Boolean = false,
        isCurrent: () -> Boolean = { true },
        beforeApply: suspend () -> Boolean = { true },
        onApiPublished: (DomainIp?) -> Unit = {},
        onRouteReady: (() -> Unit)? = null,
        onQualifiedEndpoint: (() -> Unit)? = null,
        onStageUsable: ((StartupTestStage) -> Unit)? = null,
        onProgress: (Progress) -> Unit = {},
    ): Snapshot = withContext(Dispatchers.IO) {
        runLock.withLock {
            coroutineScope {
                val routeReadyNotified = AtomicBoolean(false)
                val progressLock = Any()
                val apiReady = AtomicBoolean(false)
                val qualifiedEndpointNotified = AtomicBoolean(false)
                val lineStageNotified = AtomicBoolean(false)
                val imageStageNotified = AtomicBoolean(false)
                val applyGateMutex = Mutex()
                var applyGateResult: Boolean? = null
                suspend fun awaitApplyGate(): Boolean = applyGateMutex.withLock {
                    if (applyGateResult == null) applyGateResult = beforeApply()
                    applyGateResult == true
                }
                val coverReady = AtomicBoolean(false)
                val contentReady = AtomicBoolean(false)
                val publicationRejected = AtomicBoolean(false)
                fun notifyRouteReady() {
                    if (apiReady.get() && coverReady.get() && contentReady.get() &&
                        routeReadyNotified.compareAndSet(false, true)
                    ) {
                        onRouteReady?.invoke()
                    }
                }
                fun markImageReady(content: Boolean) {
                    if (content) contentReady.set(true) else coverReady.set(true)
                    notifyRouteReady()
                }
                val apiHasPublished = AtomicBoolean(false)
                val lastApiPublished = AtomicReference<DomainIp?>(null)
                fun publishApi(endpoint: DomainIp?) {
                    val previous = lastApiPublished.getAndSet(endpoint)
                    val wasPublished = apiHasPublished.getAndSet(true)
                    if (!wasPublished || previous != endpoint) onApiPublished(endpoint)
                    apiReady.set(endpoint != null)
                    if (endpoint != null) notifyRouteReady()
                }
                fun publishProgress(value: Progress) = synchronized(progressLock) {
                    _progress.value = value
                    onProgress(value)
                }
                SpeedTestTimingLog.start(SpeedTestTimingLog.Scope.PICACG).use { timings ->
                    val started = System.currentTimeMillis()
                    val networkIdentity = PlatformSpeedTestCoordinator.currentNetworkIdentity()
                    // One session is shared by API, cover and reader-page probes; preparation time counts.
                    val adaptiveSession = AdaptiveSpeedHistory.session(networkIdentity, started)
                    val revision = PicacgRouteManager.configRevision
                    val activeVersion = PicacgRouteManager.remoteVersion
                    // History may rank candidates, but only probes in this run can establish health.
                    // `reuse*` remains source-compatible as a hint for the bounded quick strategy.
                    val quickApi = fastApi || reuseApi
                    val quickImages = fastImages || reuseImages
                    val preferredApiHost = PicacgRouteManager.selectedApiHost
                    val preferredApiEndpoint = PicacgRouteManager.selectedApiEndpoint
                    val preferredCoverHost = PicacgRouteManager.imageSourceFor(content = false)
                    val preferredContentHost = PicacgRouteManager.imageSourceFor(content = true)
                    val preferredImageEndpoint = PicacgRouteManager.selectedImageEndpoint
                    val previousImageState = PicacgRouteManager.state.value
                    val preferredCoverEndpoint = if (PicacgRouteManager.isImageManuallyPinned) {
                        preferredImageEndpoint?.takeIf { it.domain.equals(preferredCoverHost, true) }
                    } else {
                        previousImageState.coverRanking.firstOrNull { it.domain.equals(preferredCoverHost, true) }
                    }
                    val preferredContentEndpoint = if (PicacgRouteManager.isImageManuallyPinned) {
                        preferredImageEndpoint?.takeIf { it.domain.equals(preferredContentHost, true) }
                    } else {
                        previousImageState.contentRanking.firstOrNull { it.domain.equals(preferredContentHost, true) }
                    }
                    PicacgRouteManager.clearAutomaticHealthSelections()
                    val storedApi = if (forceFresh) PicacgSpeedTestHistoryPolicy.PhaseHistory(null) else PicacgSpeedTestHistoryStorage.loadApi()
                    val storedImages = if (forceFresh) PicacgSpeedTestHistoryPolicy.PhaseHistory(null) else PicacgSpeedTestHistoryStorage.loadImages()
                    val apiCanUseHistory = !forceFresh && PicacgSpeedTestHistoryPolicy.canUseAtVersion(storedApi.remoteVersion, activeVersion)
                    val imageCanUseHistory = !forceFresh && PicacgSpeedTestHistoryPolicy.canUseAtVersion(storedImages.remoteVersion, activeVersion)
                    val apiReconcile = !forceFresh && PicacgSpeedTestHistoryPolicy.shouldReconcile(reconcileConfig, storedApi.remoteVersion, activeVersion)
                    val imageReconcile = !forceFresh && PicacgSpeedTestHistoryPolicy.shouldReconcile(reconcileConfig, storedImages.remoteVersion, activeVersion)
                    fun current(): Boolean = isCurrent() && revision == PicacgRouteManager.configRevision &&
                        activeVersion == PicacgRouteManager.remoteVersion &&
                        networkIdentity == PlatformSpeedTestCoordinator.currentNetworkIdentity()
                    suspend fun notifyQualifiedIfApplied(matches: Boolean, stage: StartupTestStage) {
                        if (!current()) return
                        if (!awaitApplyGate() || !current()) {
                            publicationRejected.set(true)
                            return
                        }
                        if (matches && current()) {
                            val stageNotified = if (stage == StartupTestStage.LINE) lineStageNotified else imageStageNotified
                            if (stageNotified.compareAndSet(false, true)) onStageUsable?.invoke(stage)
                            if (qualifiedEndpointNotified.compareAndSet(false, true)) onQualifiedEndpoint?.invoke()
                        }
                    }
                    fun logAdaptive(message: String) {
                        android.util.Log.i("PlatformSpeedTest", "picacg adaptive $message")
                    }
                    fun identityKey(identity: PicacgSpeedTestHistoryPolicy.Identity, service: String): String =
                        "$service ${identity.domain}[${identity.ip}] $identity"
                    fun combineRows(
                        history: PicacgSpeedTestHistoryPolicy.PhaseHistory,
                        active: Set<PicacgSpeedTestHistoryPolicy.Identity>,
                        fresh: List<PicacgSpeedTestHistoryPolicy.Measurement>,
                    ): MutableList<Probe> {
                        val freshByIdentity = fresh.associateBy(PicacgSpeedTestHistoryPolicy.Measurement::identity)
                        val old = history.measurements.filter { it.identity in active && it.identity !in freshByIdentity }
                            .map { it.toProbe() }
                        return (old + fresh.map { it.toProbe(testedThisRound = true, quickSelected = quickApi) }).toMutableList()
                    }

                    data class ImagePhase(
                        val blockedReason: String?,
                        val freshHistory: List<PicacgSpeedTestHistoryPolicy.Measurement>,
                        val results: List<Probe>,
                        val activeIdentities: Set<PicacgSpeedTestHistoryPolicy.Identity>,
                    )
                    val imageTask = async {
                        var blockedReason: String? = null
                        val allImageTargets = timings.start(SpeedTestTimings.Phase.PICACG_IMAGE_PREPARATION).use {
                            publishProgress(Progress(Stage.PREPARING_IMAGES, total = PicacgRouteManager.imageHosts.size))
                            when {
                                PicacgRouteManager.imageHosts.isEmpty() -> {
                                    blockedReason = "哔咔未配置任何正文图源（storage-b）；图源验证未运行"
                                    emptyList()
                                }
                                else -> adaptiveSession.prepareBlocking(quick = quickImages) {
                                    PicacgSpeedTestTargetPolicy.imageTargets(PicacgRouteManager.imageHosts, ::resolve).map { target ->
                                        ImageTarget(
                                            target.url,
                                            target.endpoint,
                                            imageIdentity(target.endpoint, content = target.content),
                                            content = target.content,
                                        )
                                    }
                                }.orEmpty()
                            }
                        }
                        if (blockedReason != null) publishProgress(Progress(Stage.IMAGE_BLOCKED, total = 0))
                        val activeImageIdentities = allImageTargets.mapTo(linkedSetOf(), ImageTarget::identity)
                        val freshImageHistory = mutableListOf<PicacgSpeedTestHistoryPolicy.Measurement>()
                        val imageResults = coroutineScope {
                            val priorImageHistory = if (imageCanUseHistory || imageReconcile) storedImages else PicacgSpeedTestHistoryPolicy.PhaseHistory(null)
                            val accumulated = combineRows(priorImageHistory, activeImageIdentities, emptyList())
                            val completed = AtomicInteger(0)
                            val successful = AtomicInteger(0)
                            val failed = AtomicInteger(0)
                            suspend fun runImageKind(content: Boolean, service: String): List<Pair<ImageTarget, Probe>> {
                                val targets = allImageTargets.filter { it.content == content }
                                if (targets.isEmpty()) return emptyList()
                                publishProgress(Progress(Stage.TESTING_IMAGES, total = allImageTargets.size))
                                val attemptedIdentities = java.util.concurrent.ConcurrentHashMap.newKeySet<PicacgSpeedTestHistoryPolicy.Identity>()
                                val results = adaptiveSession.run(
                                    scope = if (content) "PICACG:content" else "PICACG:cover",
                                    candidates = targets,
                                    key = { identityKey(it.identity, service) },
                                    quick = quickImages,
                                    stopAfterQualified = if (quickImages) QUICK_FILL_TARGET else Int.MAX_VALUE,
                                    preferredKeys = if (quickImages) {
                                        val preferredHost = if (content) preferredContentHost else preferredCoverHost
                                        val preferredEndpoint = if (content) preferredContentEndpoint else preferredCoverEndpoint
                                        targets.filter { target ->
                                            target.endpoint.domain.equals(preferredHost, true) &&
                                                (preferredEndpoint == null || target.endpoint == preferredEndpoint)
                                        }.mapTo(linkedSetOf()) { identityKey(it.identity, service) }
                                    } else {
                                        emptySet()
                                    },
                                    independenceKey = { it.endpoint.ip.trim().lowercase(java.util.Locale.ROOT).ifBlank { it.endpoint.domain.lowercase(java.util.Locale.ROOT) } },
                                    success = Probe::success,
                                    elapsedMs = Probe::elapsedMs,
                                    sampleInvalid = {
                                        it.elapsedMs < 0L || (
                                            it.success &&
                                                (it.httpStatus != 200 || it.imageDecoded != true || !PicacgImagePayloadPolicy.isImageContentType(it.contentType))
                                            )
                                    },
                                    log = ::logAdaptive,
                                    onUsable = usable@{ target, measured ->
                                        if (!current()) return@usable
                                        val allowed = awaitApplyGate() && current()
                                        if (!allowed) {
                                            publicationRejected.set(true)
                                            return@usable
                                        }
                                        if (PicacgRouteManager.isImageManuallyPinned) {
                                            if (PicacgRouteManager.selectedImageEndpoint == target.endpoint) markImageReady(target.content)
                                            return@usable
                                        }
                                        synchronized(freshImageHistory) {
                                            freshImageHistory.removeAll { it.identity == target.identity }
                                            freshImageHistory += target.identity.toMeasurement(
                                                measured.copy(measuredAtEpochMs = measured.measuredAtEpochMs ?: System.currentTimeMillis()),
                                                measured.measuredAtEpochMs ?: System.currentTimeMillis(),
                                            )
                                            fun readyRanking(contentKind: Boolean): List<DomainIp> = PicacgSpeedTestTargetPolicy.ranking(
                                                freshImageHistory.map { row ->
                                                    PicacgSpeedTestTargetPolicy.ImageOutcome(
                                                        row.endpoint,
                                                        content = row.identity.probeCondition.contains("reader-page"),
                                                        success = row.success,
                                                        elapsedMs = row.elapsedMs,
                                                    )
                                                },
                                                content = contentKind,
                                            )
                                            if (PicacgRouteManager.applyImageRankings(readyRanking(false), readyRanking(true))) {
                                                markImageReady(target.content)
                                            }
                                        }
                                    },
                                    onQualified = { target, _ ->
                                        val currentState = PicacgRouteManager.state.value
                                        val source = PicacgRouteManager.imageSourceFor(target.content)
                                        val routeMatches = if (PicacgRouteManager.isImageManuallyPinned) {
                                            PicacgRouteManager.selectedImageEndpoint == target.endpoint &&
                                                source.equals(target.endpoint.domain, true)
                                        } else {
                                            val ranking = if (target.content) currentState.contentRanking else currentState.coverRanking
                                            ranking.firstOrNull() == target.endpoint && source.equals(target.endpoint.domain, true)
                                        }
                                        notifyQualifiedIfApplied(routeMatches, StartupTestStage.IMAGE)
                                    },
                                    probe = probe@{ target ->
                                        if (!current()) return@probe null
                                        val probe = SpeedTestProbeBudget.scheduler.withPermit(
                                            ContentPlatform.PICACG,
                                            { SpeedTestTimingLog.recordQueueWait(SpeedTestTimingLog.Scope.PICACG, it) },
                                        ) {
                                            if (current()) {
                                                val probeOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                                                runInterruptible {
                                                    attemptedIdentities += target.identity
                                                    probeImage(target.url, target.endpoint, content = target.content, ownerJob = probeOwner)
                                                }
                                            } else {
                                                null
                                            }
                                        }
                                        if (probe == null || !current()) return@probe null
                                        val measured = probe.copy(measuredAtEpochMs = System.currentTimeMillis(), testedThisRound = true, quickSelected = quickImages)
                                        synchronized(allImageTargets) {
                                            if (measured.success) successful.incrementAndGet() else failed.incrementAndGet()
                                            publishProgress(
                                                Progress(
                                                    Stage.TESTING_IMAGES,
                                                    completed = completed.incrementAndGet(),
                                                    total = allImageTargets.size,
                                                    successful = successful.get(),
                                                    failed = failed.get(),
                                                ),
                                            )
                                        }
                                        measured
                                    },
                                ).filterNot { (_, probe) ->
                                    probe.elapsedMs < 0L || (
                                        probe.success &&
                                            (probe.httpStatus != 200 || probe.imageDecoded != true || !PicacgImagePayloadPolicy.isImageContentType(probe.contentType))
                                        )
                                }
                                if (quickImages && results.any { it.second.success }) {
                                    adaptiveSession.deferExploration(
                                        scope = if (content) "PICACG:content" else "PICACG:cover",
                                        candidates = targets.filterNot { it.identity in attemptedIdentities },
                                        key = { identityKey(it.identity, service) },
                                        success = Probe::success,
                                        elapsedMs = Probe::elapsedMs,
                                        sampleInvalid = {
                                            it.elapsedMs < 0L || (
                                                it.success &&
                                                    (it.httpStatus != 200 || it.imageDecoded != true || !PicacgImagePayloadPolicy.isImageContentType(it.contentType))
                                                )
                                        },
                                        log = ::logAdaptive,
                                    ) { target ->
                                        if (!current()) return@deferExploration null
                                        SpeedTestProbeBudget.scheduler.withPermit(ContentPlatform.PICACG) {
                                            if (current()) {
                                                val probeOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                                                runInterruptible {
                                                    probeImage(target.url, target.endpoint, content = target.content, ownerJob = probeOwner)
                                                }
                                            } else {
                                                null
                                            }
                                        }.takeIf { current() }
                                    }
                                }
                                return results
                            }
                            val coverTask = async { runImageKind(content = false, service = "cover") }
                            val contentTask = async { runImageKind(content = true, service = "content") }
                            val measurements = coverTask.await().plus(contentTask.await()).map { (target, probe) ->
                                target.identity.toMeasurement(probe, probe.measuredAtEpochMs ?: System.currentTimeMillis())
                            }
                            measurements.forEach { measurement ->
                                freshImageHistory.removeAll { it.identity == measurement.identity }
                                freshImageHistory += measurement
                            }
                            val freshByEndpointAndKind = freshImageHistory.associateBy { it.identity }
                            // Replace retained display history with measured values without treating retained rows as current.
                            val freshDisplay = freshImageHistory.map { it.toProbe(testedThisRound = true, quickSelected = quickImages) }
                            accumulated.removeAll { old ->
                                freshByEndpointAndKind.keys.any {
                                    it.domain == old.endpoint.domain && it.ip == old.endpoint.ip &&
                                        (it.probeCondition.contains("reader-page") == old.content)
                                }
                            }
                            accumulated += freshDisplay
                            if (allImageTargets.isEmpty() && accumulated.isEmpty() && blockedReason == null) {
                                blockedReason = "哔咔图源没有可探测的 IP；图源验证未完成"
                                publishProgress(Progress(Stage.IMAGE_BLOCKED, total = 0))
                            }
                            accumulated
                        }

                        ImagePhase(blockedReason, freshImageHistory.toList(), imageResults.toList(), activeImageIdentities)
                    }
                    val apiUrls = PicacgRouteManager.apiUrls
                    val apiTargets = timings.start(SpeedTestTimings.Phase.PICACG_LINE_PREPARATION).use {
                        publishProgress(Progress(Stage.PREPARING_API, total = apiUrls.size))
                        adaptiveSession.prepareBlocking(quick = quickApi) {
                            PicacgSpeedTestTargetPolicy.apiTargets(apiUrls, ::resolve).map { target ->
                                ApiTarget(target.url, target.endpoint, apiIdentity(target.url, target.endpoint))
                            }
                        }.orEmpty()
                    }
                    val activeApiIdentities = apiTargets.mapTo(linkedSetOf(), ApiTarget::identity)
                    val freshApiHistory: List<PicacgSpeedTestHistoryPolicy.Measurement>
                    val apiResults = run {
                        val completed = AtomicInteger(0)
                        val successful = AtomicInteger(0)
                        val failed = AtomicInteger(0)
                        val attemptedIdentities = java.util.concurrent.ConcurrentHashMap.newKeySet<PicacgSpeedTestHistoryPolicy.Identity>()
                        publishProgress(Progress(Stage.TESTING_API, total = apiTargets.size))
                        val fresh = adaptiveSession.run(
                            scope = "PICACG:api",
                            candidates = apiTargets,
                            key = { identityKey(it.identity, "api-root") },
                            quick = quickApi,
                            stopAfterQualified = if (quickApi) QUICK_FILL_TARGET else Int.MAX_VALUE,
                            preferredKeys = if (quickApi) {
                                apiTargets.filter { target ->
                                    target.endpoint.domain.equals(preferredApiHost, true) &&
                                        (preferredApiEndpoint == null || target.endpoint == preferredApiEndpoint)
                                }.mapTo(linkedSetOf()) { identityKey(it.identity, "api-root") }
                            } else {
                                emptySet()
                            },
                            independenceKey = { it.endpoint.ip.trim().lowercase(java.util.Locale.ROOT).ifBlank { it.endpoint.domain.lowercase(java.util.Locale.ROOT) } },
                            success = Probe::success,
                            elapsedMs = Probe::elapsedMs,
                            sampleInvalid = { it.elapsedMs < 0L || (it.success && it.httpStatus == null) },
                            log = ::logAdaptive,
                            onUsable = usable@{ target, _ ->
                                if (!current()) return@usable
                                val allowed = awaitApplyGate() && current()
                                if (!allowed) {
                                    publicationRejected.set(true)
                                    return@usable
                                }
                                val manual = PicacgRouteManager.isApiManuallyPinned
                                val applied = if (manual) {
                                    PicacgRouteManager.selectedApiEndpoint == target.endpoint
                                } else if (PicacgRouteManager.selectedApiEndpoint == target.endpoint &&
                                    PicacgRouteManager.selectedApiHost.equals(URI(target.url).host, true)
                                ) {
                                    true
                                } else {
                                    PicacgRouteManager.applyAutomaticApiRoute(target.url, target.endpoint)
                                }
                                if (applied && current()) {
                                    publishApi(target.endpoint)
                                }
                            },
                            onQualified = { target, _ ->
                                notifyQualifiedIfApplied(
                                    PicacgRouteManager.selectedApiEndpoint == target.endpoint &&
                                        PicacgRouteManager.selectedApiHost.equals(URI(target.url).host, true),
                                    StartupTestStage.LINE,
                                )
                            },
                            probe = probe@{ target ->
                                if (!current()) return@probe null
                                val probe = SpeedTestProbeBudget.scheduler.withPermit(
                                    ContentPlatform.PICACG,
                                    { SpeedTestTimingLog.recordQueueWait(SpeedTestTimingLog.Scope.PICACG, it) },
                                ) {
                                    if (current()) {
                                        val probeOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                                        runInterruptible {
                                            attemptedIdentities += target.identity
                                            probeApi(target.url, target.endpoint, ownerJob = probeOwner)
                                        }
                                    } else {
                                        null
                                    }
                                }
                                if (probe == null || !current()) return@probe null
                                val measured = probe.copy(measuredAtEpochMs = System.currentTimeMillis(), testedThisRound = true, quickSelected = quickApi)
                                synchronized(apiTargets) {
                                    if (measured.success) successful.incrementAndGet() else failed.incrementAndGet()
                                    publishProgress(
                                        Progress(
                                            Stage.TESTING_API,
                                            completed = completed.incrementAndGet(),
                                            total = apiTargets.size,
                                            successful = successful.get(),
                                            failed = failed.get(),
                                        ),
                                    )
                                }
                                measured
                            },
                        ).filterNot { (_, probe) -> probe.elapsedMs < 0L || (probe.success && probe.httpStatus == null) }
                        freshApiHistory = fresh.map { (target, probe) ->
                            target.identity.toMeasurement(probe, probe.measuredAtEpochMs ?: System.currentTimeMillis())
                        }
                        if (quickApi && fresh.any { it.second.success }) {
                            adaptiveSession.deferExploration(
                                scope = "PICACG:api",
                                candidates = apiTargets.filterNot { it.identity in attemptedIdentities },
                                key = { identityKey(it.identity, "api-root") },
                                success = Probe::success,
                                elapsedMs = Probe::elapsedMs,
                                sampleInvalid = { it.elapsedMs < 0L || (it.success && it.httpStatus == null) },
                                log = ::logAdaptive,
                            ) { target ->
                                if (!current()) return@deferExploration null
                                SpeedTestProbeBudget.scheduler.withPermit(ContentPlatform.PICACG) {
                                    if (current()) {
                                        val probeOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                                        runInterruptible { probeApi(target.url, target.endpoint, ownerJob = probeOwner) }
                                    } else {
                                        null
                                    }
                                }.takeIf { current() }
                            }
                        }
                        val oldHistory = if (apiCanUseHistory || apiReconcile) storedApi else PicacgSpeedTestHistoryPolicy.PhaseHistory(null)
                        combineRows(oldHistory, activeApiIdentities, freshApiHistory)
                    }
                    val apiProbes = apiResults.toList()
                    val fastestCurrentApi = freshApiHistory.asSequence()
                        .filter(PicacgSpeedTestHistoryPolicy.Measurement::success)
                        .minWithOrNull(compareBy({ it.elapsedMs }, { it.endpoint.domain }, { it.endpoint.ip }))
                    val requestedEndpoint = if (PicacgRouteManager.isApiManuallyPinned) {
                        PicacgRouteManager.selectedApiEndpoint
                    } else {
                        fastestCurrentApi?.endpoint
                    }
                    val selectedTarget = if (PicacgRouteManager.isApiManuallyPinned) {
                        requestedEndpoint?.let { endpoint -> apiTargets.firstOrNull { it.endpoint == endpoint } }
                    } else {
                        fastestCurrentApi?.identity?.let { identity -> apiTargets.firstOrNull { it.identity == identity } }
                    }
                    var publishedLine: DomainIp? = null
                    var publicationAllowed = !publicationRejected.get() && awaitApplyGate() && current()
                    if (publicationAllowed) {
                        val selectedSucceededThisRound = requestedEndpoint != null && apiProbes.any {
                            it.endpoint == requestedEndpoint && it.testedThisRound && it.success
                        }
                        var bindingApplied = PicacgRouteManager.isApiManuallyPinned && selectedSucceededThisRound &&
                            PicacgRouteManager.selectedApiEndpoint == requestedEndpoint
                        if (selectedSucceededThisRound && selectedTarget != null && !PicacgRouteManager.isApiManuallyPinned) {
                            bindingApplied = (
                                PicacgRouteManager.selectedApiEndpoint == selectedTarget.endpoint &&
                                    PicacgRouteManager.selectedApiHost.equals(URI(selectedTarget.url).host, true)
                                ) ||
                                PicacgRouteManager.applyAutomaticApiRoute(selectedTarget.url, selectedTarget.endpoint)
                        }
                        publishedLine = SpeedTestLogSelectionPolicy.selectedEndpoint(
                            requestedEndpoint?.takeIf { selectedSucceededThisRound && bindingApplied },
                            apiProbes.filter(Probe::testedThisRound).mapTo(hashSetOf(), Probe::endpoint),
                        ) { endpoint -> apiProbes.any { it.endpoint == endpoint && it.testedThisRound && it.success } }
                        if (current()) {
                            publishApi(publishedLine)
                        } else {
                            publicationAllowed = false
                        }
                    }

                    val imagePhase = imageTask.await()
                    val blockedReason = imagePhase.blockedReason
                    val freshImageHistory = imagePhase.freshHistory.toMutableList()
                    val imageResults = imagePhase.results
                    val activeImageIdentities = imagePhase.activeIdentities

                    if (publicationAllowed && awaitApplyGate() && current()) {
                        run {
                            val canPersistApiHistory = forceFresh || apiCanUseHistory || apiReconcile || storedApi.remoteVersion == null
                            if (canPersistApiHistory) {
                                val history = when {
                                    forceFresh -> PicacgSpeedTestHistoryPolicy.freshOnly(activeVersion, freshApiHistory)
                                    apiReconcile -> {
                                        val freshIdentities = freshApiHistory.mapTo(hashSetOf()) { it.identity }
                                        PicacgSpeedTestHistoryPolicy.reconcile(
                                            storedApi.copy(measurements = storedApi.measurements.filterNot { it.identity in freshIdentities }),
                                            activeVersion,
                                            activeApiIdentities,
                                            freshApiHistory,
                                        )
                                    }
                                    quickApi -> PicacgSpeedTestHistoryPolicy.mergeQuick(storedApi, freshApiHistory.mapTo(linkedSetOf()) { it.identity }, freshApiHistory)
                                    apiCanUseHistory -> PicacgSpeedTestHistoryPolicy.mergeFull(storedApi, freshApiHistory)
                                    else -> PicacgSpeedTestHistoryPolicy.freshOnly(activeVersion, freshApiHistory)
                                }
                                PicacgSpeedTestHistoryStorage.saveApi(history)
                            }
                        }
                        run {
                            val canPersistImageHistory = forceFresh || imageCanUseHistory || imageReconcile || storedImages.remoteVersion == null
                            if (canPersistImageHistory) {
                                val history = when {
                                    forceFresh -> PicacgSpeedTestHistoryPolicy.freshOnly(activeVersion, freshImageHistory)
                                    imageReconcile -> {
                                        val freshIdentities = freshImageHistory.mapTo(hashSetOf()) { it.identity }
                                        PicacgSpeedTestHistoryPolicy.reconcile(
                                            storedImages.copy(measurements = storedImages.measurements.filterNot { it.identity in freshIdentities }),
                                            activeVersion,
                                            activeImageIdentities,
                                            freshImageHistory,
                                        )
                                    }
                                    quickImages -> PicacgSpeedTestHistoryPolicy.mergeQuick(storedImages, freshImageHistory.mapTo(linkedSetOf()) { it.identity }, freshImageHistory)
                                    imageCanUseHistory -> PicacgSpeedTestHistoryPolicy.mergeFull(storedImages, freshImageHistory)
                                    else -> PicacgSpeedTestHistoryPolicy.freshOnly(activeVersion, freshImageHistory)
                                }
                                if (forceFresh || imageReconcile || PicacgRouteManager.imageHosts.isNotEmpty()) PicacgSpeedTestHistoryStorage.saveImages(history)
                            }
                        }
                        if (!PicacgRouteManager.isImageManuallyPinned) {
                            fun rankingOf(content: Boolean): List<DomainIp> = PicacgSpeedTestTargetPolicy.ranking(
                                freshImageHistory.map { measurement ->
                                    PicacgSpeedTestTargetPolicy.ImageOutcome(
                                        measurement.endpoint,
                                        content = measurement.identity.probeCondition.contains("reader-page"),
                                        success = measurement.success,
                                        elapsedMs = measurement.elapsedMs,
                                    )
                                },
                                content = content,
                            )
                            PicacgRouteManager.applyImageRankings(rankingOf(false), rankingOf(true))
                        }
                        val finished = System.currentTimeMillis()
                        val ordered = compareBy<Probe>({ if (it.success) it.elapsedMs else Long.MAX_VALUE }, { it.endpoint.domain }, { it.endpoint.ip }, { it.content })
                        val result = Snapshot(
                            started,
                            finished,
                            activeVersion,
                            apiProbes.sortedWith(ordered),
                            imageResults.sortedWith(ordered),
                            blockedReason,
                            revision,
                            roundSelectedLine = publishedLine,
                            roundSelectedImage = imageResults.asSequence()
                                .filter { it.content && it.testedThisRound && it.success }
                                .minByOrNull(Probe::elapsedMs)
                                ?.endpoint,
                            networkIdentity = networkIdentity,
                        )
                        _snapshot.value = result
                        val measuredApi = apiProbes.filter(Probe::testedThisRound)
                        publishProgress(
                            Progress(
                                Stage.COMPLETE,
                                completed = measuredApi.size + imageResults.count(Probe::testedThisRound),
                                total = apiProbes.size + imageResults.size,
                                successful = measuredApi.count(Probe::success) + imageResults.count { it.testedThisRound && it.success },
                                failed = measuredApi.count { !it.success } + imageResults.count { it.testedThisRound && !it.success },
                            ),
                        )
                        AdaptiveSpeedHistory.launchExploration(adaptiveSession)
                        return@use result
                    }
                    val finished = System.currentTimeMillis()
                    Snapshot(started, finished, activeVersion, apiProbes, imageResults, blockedReason, revision, networkIdentity = networkIdentity)
                }
            }
        }
    }

    private fun apiIdentity(url: String, endpoint: DomainIp) = PicacgSpeedTestHistoryPolicy.Identity(
        platform = PicacgSpeedTestHistoryPolicy.PLATFORM,
        phase = PicacgSpeedTestHistoryPolicy.API_PHASE,
        url = url,
        domain = endpoint.domain,
        ip = endpoint.ip,
        routeCondition = routeCondition(PicacgSpeedTestHistoryPolicy.API_ROUTE_CONDITION, endpoint),
        probeCondition = "GET-root;redirects-disabled;accept-non-redirect-http-status",
    )

    private fun imageIdentity(endpoint: DomainIp, content: Boolean): PicacgSpeedTestHistoryPolicy.Identity {
        // Reader-page probe hits the storage-b bucket directly; cover probe hits the imgproxy node.
        val tileUrl = if (content) {
            PicacgBuiltinNetwork.speedTileContentUrl(endpoint.domain)
        } else {
            PicacgBuiltinNetwork.coverHostFor(endpoint.domain)
                ?.let(PicacgBuiltinNetwork::speedTileUrl)
                .orEmpty()
        }
        return PicacgSpeedTestHistoryPolicy.Identity(
            platform = PicacgSpeedTestHistoryPolicy.PLATFORM,
            phase = PicacgSpeedTestHistoryPolicy.IMAGE_PHASE,
            url = tileUrl,
            domain = endpoint.domain,
            ip = endpoint.ip,
            routeCondition = routeCondition(PicacgSpeedTestHistoryPolicy.IMAGE_ROUTE_CONDITION, endpoint),
            probeCondition = if (content) {
                "GET-storage-b-reader-page-tile;redirects-disabled;status-200;image-content-type;image-magic"
            } else {
                "GET-imgproxy-tile;redirects-disabled;status-200;image-content-type;image-magic"
            },
        )
    }

    /** Cover tiles are imgproxy `/insecure/...` urls; reader-page tiles are storage-b `/static/...` urls. */
    private fun isContentTileUrl(u: String): Boolean = !u.contains("/insecure/")

    private fun PicacgSpeedTestHistoryPolicy.Measurement.toProbe(
        testedThisRound: Boolean = false,
        quickSelected: Boolean = false,
    ) = Probe(
        endpoint = endpoint,
        success = success,
        elapsedMs = elapsedMs,
        httpStatus = httpStatus,
        contentType = contentType,
        imageDecoded = imageDecoded,
        measuredAtEpochMs = measuredAtEpochMs,
        testedThisRound = testedThisRound,
        quickSelected = quickSelected,
        content = isContentTileUrl(identity.url),
    )

    private fun PicacgSpeedTestHistoryPolicy.Identity.toMeasurement(probe: Probe, measuredAt: Long) =
        PicacgSpeedTestHistoryPolicy.Measurement(
            identity = this,
            success = probe.success,
            elapsedMs = probe.elapsedMs,
            measuredAtEpochMs = measuredAt,
            httpStatus = probe.httpStatus,
            contentType = probe.contentType,
            imageDecoded = probe.imageDecoded,
        )

    private fun PicacgSpeedTestHistoryPolicy.Measurement.toApiTarget() = ApiTarget(identity.url, endpoint, identity)

    private fun PicacgSpeedTestHistoryPolicy.Measurement.toImageTarget() =
        ImageTarget(identity.url, endpoint, identity, content = isContentTileUrl(identity.url))

    private fun routeCondition(base: String, endpoint: DomainIp): String =
        "$base;ip-source=${if (PicacgRouteManager.resolveIps(endpoint.domain).isNotEmpty()) "configured-rules" else "system-dns"}"

    private fun resolve(host: String): List<String> {
        val configured = PicacgRouteManager.resolveIps(host).distinct()
        if (configured.isNotEmpty()) return configured
        return runCatching { okhttp3.Dns.SYSTEM.lookup(host).map { it.hostAddress.substringBefore('%') }.distinct() }.getOrDefault(emptyList())
    }

    /** Diagnostic-only adapter: validates the same API/image response without publishing or selecting routes. */
    internal suspend fun probeForDeprecatedScan(endpoint: DomainIp, url: String, image: Boolean, runtimeRecovery: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val urlHost = runCatching { URI(url).host }.getOrNull() ?: return@withContext false
            if (!urlHost.equals(endpoint.domain, ignoreCase = true)) return@withContext false
            val rules = if (image) PicacgRouteManager.imageRouteRules() else PicacgRouteManager.apiRouteRules()
            if (rules.entries.none { (host, addresses) ->
                    host.equals(endpoint.domain, ignoreCase = true) && addresses.any { it.trim() == endpoint.ip }
                }
            ) {
                return@withContext false
            }
            val probeOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            runInterruptible {
                val pinnedClient = PicacgRouteManager.newDiagnosticPinnedClient(baseProbeClient, endpoint)
                val client = if (runtimeRecovery && !image) {
                    pinnedClient.newBuilder().addInterceptor { chain ->
                        if (!PicacgApiRuntimeRecovery.isProbeAllowed(endpoint)) throw java.io.IOException("API recovery probe paused")
                        chain.proceed(chain.request()).also { response ->
                            PicacgApiRuntimeRecovery.recordProbeResponse(endpoint, response.code, response.header("Retry-After"))
                        }
                    }.build()
                } else {
                    pinnedClient
                }
                try {
                    if (image) probeImage(url, endpoint, client, ownerJob = probeOwner).success else probeApi(url, endpoint, client, ownerJob = probeOwner).success
                } finally {
                    client.connectionPool.evictAll()
                }
            }
        }

    private fun probeApi(baseUrl: String, endpoint: DomainIp, clientOverride: OkHttpClient? = null, ownerJob: kotlinx.coroutines.Job? = null): Probe {
        val start = System.nanoTime()
        var code: Int? = null
        if (!PicacgNetworkPolicy.apiBaseAllowed(baseUrl)) return Probe(endpoint, false, elapsedMs(start), null)
        val success = try {
            val client = clientOverride ?: PicacgRouteManager.newPinnedClient(baseProbeClient, endpoint)
            val request = Request.Builder().url(baseUrl.trimEnd('/') + "/").get().build()
            SpeedTestProbeCall.execute(client.newCall(request), ownerJob) { response ->
                code = response.code
                response.closeBodyQuietly()
                PicacgNetworkPolicy.apiRootReachable(response.code)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return Probe(endpoint, success, elapsedMs(start), code)
    }

    internal suspend fun validateRuntimeImageEndpoint(endpoint: DomainIp, content: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!RuntimeNetworkGate.isOnline()) return@withContext false
        val url = if (content) {
            PicacgBuiltinNetwork.speedTileContentUrl(endpoint.domain)
        } else {
            val mirror = PicacgBuiltinNetwork.coverHostFor(endpoint.domain) ?: return@withContext false
            PicacgBuiltinNetwork.speedTileUrl(mirror)
        }
        val probeOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        runInterruptible {
            probeImage(url, endpoint, content = content, runtimeRecovery = true, ownerJob = probeOwner).success
        }
    }

    /** Validates a previously successful API probe at its exact configured IP without changing rankings. */
    internal suspend fun validateRuntimeApiEndpoint(endpoint: DomainIp, url: String): Boolean = withContext(Dispatchers.IO) {
        if (!RuntimeNetworkGate.isOnline() || runCatching { URI(url).host.equals(endpoint.domain, true) }.getOrDefault(false).not()) {
            return@withContext false
        }
        if (!PicacgApiRuntimeRecovery.isProbeAllowed(endpoint)) return@withContext false
        probeForDeprecatedScan(endpoint, url, image = false, runtimeRecovery = true)
    }

    private fun probeImage(
        url: String,
        endpoint: DomainIp,
        clientOverride: OkHttpClient? = null,
        content: Boolean = false,
        runtimeRecovery: Boolean = false,
        ownerJob: kotlinx.coroutines.Job? = null,
    ): Probe {
        val start = System.nanoTime()
        var code: Int? = null
        var contentType: String? = null
        var validImage: Boolean? = false
        val success = try {
            // Cover probe pins both the bucket and its imgproxy mirror to the measured IP; the reader-page
            // probe pins only the storage-b bucket (img nodes answer 502/404 for content paths). Redirects
            // are never followed so only that concrete IP is timed.
            val client = clientOverride
                ?: if (content) {
                    PicacgRouteManager.newPinnedClient(baseProbeClient, endpoint)
                } else {
                    PicacgBuiltinNetwork.coverHostFor(endpoint.domain)?.let { coverHost ->
                        PicacgRouteManager.newPinnedClient(baseProbeClient, endpoint).newBuilder()
                            .dns(object : Dns {
                                override fun lookup(hostname: String): List<InetAddress> =
                                    if (hostname.equals(endpoint.domain, true) || hostname.equals(coverHost, true)) {
                                        listOf(InetAddress.getByName(endpoint.ip))
                                    } else {
                                        PicacgRouteManager.dns().lookup(hostname)
                                    }
                            })
                            .build()
                    }
                        ?: PicacgRouteManager.newPinnedClient(baseProbeClient, endpoint)
                }
            val guarded = if (runtimeRecovery) {
                client.newBuilder().addInterceptor(
                    RuntimeImageProbeGuard(RuntimeRecoveryPolicy.Platform.PICACG, DomainIp(URI(url).host, endpoint.ip)),
                ).build()
            } else {
                client
            }
            // Fetch one tile and validate status + content-type + image magic on the head bytes.
            fun fetchTile(testUrl: String): Boolean =
                SpeedTestProbeCall.execute(guarded.newCall(Request.Builder().url(testUrl).header("User-Agent", "okhttp/3.8.1").get().build()), ownerJob) { response ->
                    code = response.code
                    contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                    if (response.code != 200 || !PicacgImagePayloadPolicy.isImageContentType(contentType)) {
                        response.closeBodyQuietly()
                        false
                    } else {
                        val bytes = response.body?.byteStream()?.use { readImageHead(it, MAX_IMAGE_HEAD_BYTES) }
                            ?: throw IOException("Empty image body")
                        validImage = PicacgImagePayloadPolicy.hasImageMagic(bytes)
                        PicacgNetworkPolicy.imageTileVerified(response.code, contentType, validImage == true)
                    }
                }
            // Diagnostic caller supplies a pinned client + real sample and only runs its own url.
            // Reader-page probe fetches the single storage-b tile; cover probe requires BOTH the
            // storage-b and storage1 tiles (~59/60 covers originate from storage1).
            when {
                clientOverride != null -> fetchTile(url)
                content -> fetchTile(url)
                else -> {
                    val storageB = fetchTile(url)
                    val coverHost = PicacgBuiltinNetwork.coverHostFor(endpoint.domain)
                    val storage1 = coverHost != null && fetchTile(PicacgBuiltinNetwork.speedTileStorage1Url(coverHost))
                    storageB && storage1
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return Probe(endpoint, success, elapsedMs(start), code, contentType, validImage, content = content)
    }

    /** Reads at most [maxBytes] — the header needed to validate the image — and never buffers the whole picture. */
    private fun readImageHead(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
        }
        if (total == 0) throw IOException("Empty image body")
        return output.toByteArray()
    }

    private fun elapsedMs(startNanos: Long): Long = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)

    private fun okhttp3.Response.closeBodyQuietly() = runCatching { body?.close() }

    /** A privacy-safe export; never serializes sample URLs, credentials, bodies, or exception messages. */
    fun exportJson(value: Snapshot = snapshot.value ?: error("Picacg speed test has not run")): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("platform", "picacg")
        put("startedAtEpochMs", value.startedAtEpochMs)
        put("completedAtEpochMs", value.completedAtEpochMs)
        put("remoteConfigVersion", value.remoteVersion ?: JSONObject.NULL)
        put("phaseTimingsMs", SpeedTestTimingLog.json(SpeedTestTimingLog.Scope.PICACG))
        put("imageVerificationBlockedReason", value.imageBlockedReason ?: JSONObject.NULL)
        put("complete", value.complete)
        put("apiEndpoints", JSONArray().apply { value.apiProbes.forEach { put(it.toJson("api_root")) } })
        put("imageEndpoints", JSONArray().apply { value.imageProbes.forEach { put(it.toJson("real_image_get")) } })
    }.toString()

    private fun Probe.toJson(probeType: String) = JSONObject().apply {
        put("probe", probeType)
        if (probeType == "real_image_get") put("service", if (content) "body" else "cover")
        put("domain", endpoint.domain)
        put("ip", endpoint.ip)
        put("success", success)
        put("elapsedMs", elapsedMs)
        put("httpStatus", httpStatus ?: JSONObject.NULL)
        put("contentType", contentType ?: JSONObject.NULL)
        put("imageDecoded", imageDecoded ?: JSONObject.NULL)
        put("measuredAtEpochMs", measuredAtEpochMs ?: JSONObject.NULL)
        put("testedThisRound", testedThisRound)
        put("quickSelected", quickSelected)
    }

    /** Only the first 32KB of a tile/image is buffered for magic-byte validation; the rest is not downloaded. */
    private const val MAX_IMAGE_HEAD_BYTES = 32 * 1024
}
