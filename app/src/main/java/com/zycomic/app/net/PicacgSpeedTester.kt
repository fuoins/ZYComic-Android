package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.atomic.AtomicInteger

/** Independent Picacg API and real-image probes. No endpoint is successful by assumption or synthesis. */
object PicacgSpeedTester {
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
        onProgress: (Progress) -> Unit = {},
    ): Snapshot = withContext(Dispatchers.IO) {
        runLock.withLock {
            fun publishProgress(value: Progress) {
                _progress.value = value
                onProgress(value)
            }
            SpeedTestTimingLog.start(SpeedTestTimingLog.Scope.PICACG).use { timings ->
                val revision = PicacgRouteManager.configRevision
                val activeVersion = PicacgRouteManager.remoteVersion
                val started = System.currentTimeMillis()
                val previous = _snapshot.value?.takeIf {
                    !forceFresh && it.revision == revision && it.remoteVersion == activeVersion
                }
                val cachedApi = previous?.apiProbes.orEmpty().takeIf { reuseApi && !forceFresh }.orEmpty()
                val cachedImages = previous?.imageProbes.orEmpty().takeIf { reuseImages && !forceFresh }.orEmpty()
                val storedApi = if (forceFresh) PicacgSpeedTestHistoryPolicy.PhaseHistory(null) else PicacgSpeedTestHistoryStorage.loadApi()
                val storedImages = if (forceFresh) PicacgSpeedTestHistoryPolicy.PhaseHistory(null) else PicacgSpeedTestHistoryStorage.loadImages()
                val apiCanUseHistory = !forceFresh && PicacgSpeedTestHistoryPolicy.canUseAtVersion(storedApi.remoteVersion, activeVersion)
                val imageCanUseHistory = !forceFresh && PicacgSpeedTestHistoryPolicy.canUseAtVersion(storedImages.remoteVersion, activeVersion)
                val apiReconcile = !forceFresh && PicacgSpeedTestHistoryPolicy.shouldReconcile(reconcileConfig, storedApi.remoteVersion, activeVersion)
                val imageReconcile = !forceFresh && PicacgSpeedTestHistoryPolicy.shouldReconcile(reconcileConfig, storedImages.remoteVersion, activeVersion)
                val apiQuickSelection = if (cachedApi.isEmpty() && fastApi && apiCanUseHistory) {
                    PicacgSpeedTestHistoryPolicy.selectRankedTop(storedApi)
                } else {
                    emptyList()
                }
                val imageQuickSelection = if (cachedImages.isEmpty() && fastImages && imageCanUseHistory) {
                    PicacgSpeedTestHistoryPolicy.selectRankedTop(storedImages)
                } else {
                    emptyList()
                }
                val apiQuick = apiQuickSelection.isNotEmpty()
                val imageQuick = imageQuickSelection.isNotEmpty()
                val apiUrls = PicacgRouteManager.apiUrls
                val apiTargets = timings.start(SpeedTestTimings.Phase.PICACG_LINE_PREPARATION).use {
                    publishProgress(Progress(Stage.PREPARING_API, total = apiUrls.size))
                    when {
                        cachedApi.isNotEmpty() || apiQuick -> emptyList()
                        else -> apiUrls.flatMap { baseUrl ->
                            val host = runCatching { URI(baseUrl).host }.getOrNull()
                            if (host == null) {
                                emptyList()
                            } else {
                                resolve(host).map { ip ->
                                    val endpoint = DomainIp(host, ip)
                                    ApiTarget(baseUrl, endpoint, apiIdentity(baseUrl, endpoint))
                                }
                            }
                        }
                    }
                }
                val targetsToProbeApi = when {
                    cachedApi.isNotEmpty() -> emptyList()
                    apiQuick -> apiQuickSelection.map { it.toApiTarget() }
                    apiReconcile -> apiTargets.filter { target -> storedApi.measurements.none { it.identity == target.identity } }
                    else -> apiTargets
                }
                val apiQuickIdentities = apiQuickSelection.mapTo(linkedSetOf()) { it.identity }
                val apiResults = when {
                    cachedApi.isNotEmpty() -> cachedApi.map { it.copy(testedThisRound = false, quickSelected = false) }.toMutableList()
                    apiQuick -> storedApi.measurements.filter { old -> apiQuickSelection.none { it.identity == old.identity } }
                        .map { it.toProbe() }.toMutableList()
                    apiReconcile -> storedApi.measurements.filter { old -> apiTargets.any { it.identity == old.identity } }
                        .map { it.toProbe() }.toMutableList()
                    else -> mutableListOf()
                }
                var freshApiHistory = emptyList<PicacgSpeedTestHistoryPolicy.Measurement>()
                timings.start(SpeedTestTimings.Phase.PICACG_LINES).use {
                    publishProgress(Progress(Stage.TESTING_API, total = targetsToProbeApi.size))
                    val completed = AtomicInteger(0)
                    val successful = AtomicInteger(0)
                    val failed = AtomicInteger(0)
                    val freshResults = coroutineScope {
                        targetsToProbeApi.map { target ->
                            async {
                                val result = SpeedTestProbeBudget.scheduler.withPermit(
                                    ContentPlatform.PICACG,
                                    { SpeedTestTimingLog.recordQueueWait(SpeedTestTimingLog.Scope.PICACG, it) },
                                ) {
                                    if (isCurrent()) probeApi(target.url, target.endpoint) else null
                                }?.copy(
                                    measuredAtEpochMs = System.currentTimeMillis(),
                                    testedThisRound = true,
                                    quickSelected = target.identity in apiQuickIdentities,
                                )
                                synchronized(apiResults) {
                                    if (result?.success == true) successful.incrementAndGet()
                                    if (result != null && !result.success) failed.incrementAndGet()
                                    val done = completed.incrementAndGet()
                                    publishProgress(
                                        Progress(
                                            Stage.TESTING_API,
                                            completed = done,
                                            total = targetsToProbeApi.size,
                                            successful = successful.get(),
                                            failed = failed.get(),
                                        ),
                                    )
                                }
                                target to result
                            }
                        }.awaitAll().filter { it.second != null }
                    }
                    apiResults += freshResults.mapNotNull { it.second }
                    freshApiHistory = freshResults.mapNotNull { (target, result) ->
                        result?.let { target.identity.toMeasurement(it, it.measuredAtEpochMs ?: System.currentTimeMillis()) }
                    }
                }

                var blockedReason: String? = null
                // Each root × IP is probed twice with fixed, auth-free tiles: a cover probe on the
                // imgproxy node (storage-b + storage1 tiles) and a reader-page probe on the storage-b
                // bucket, so covers and pages get independent per-root rankings.
                val imageTargets = timings.start(SpeedTestTimings.Phase.PICACG_IMAGE_PREPARATION).use {
                    publishProgress(Progress(Stage.PREPARING_IMAGES, total = PicacgRouteManager.imageHosts.size))
                    when {
                        cachedImages.isNotEmpty() || imageQuick -> emptyList()
                        PicacgRouteManager.imageHosts.isEmpty() -> {
                            blockedReason = "哔咔未配置任何正文图源（storage-b）；图源验证未运行"
                            emptyList()
                        }
                        else -> PicacgRouteManager.imageHosts.flatMap { contentHost ->
                            val coverHost = PicacgBuiltinNetwork.coverHostFor(contentHost)
                            if (coverHost == null) {
                                emptyList()
                            } else {
                                val coverTileUrl = PicacgBuiltinNetwork.speedTileUrl(coverHost)
                                val contentTileUrl = PicacgBuiltinNetwork.speedTileContentUrl(contentHost)
                                resolve(contentHost).flatMap { ip ->
                                    val endpoint = DomainIp(contentHost, ip)
                                    listOf(
                                        ImageTarget(coverTileUrl, endpoint, imageIdentity(endpoint, content = false), content = false),
                                        ImageTarget(contentTileUrl, endpoint, imageIdentity(endpoint, content = true), content = true),
                                    )
                                }
                            }
                        }
                    }
                }
                if (blockedReason != null) publishProgress(Progress(Stage.IMAGE_BLOCKED, total = 0))
                val canReconcileImages = imageReconcile && PicacgRouteManager.imageHosts.isNotEmpty()
                val targetsToProbeImages = when {
                    cachedImages.isNotEmpty() -> emptyList()
                    imageQuick -> imageQuickSelection.map { it.toImageTarget() }
                    canReconcileImages -> imageTargets.filter { target ->
                        storedImages.measurements.none { it.identity == target.identity }
                    }
                    else -> imageTargets
                }
                val imageQuickIdentities = imageQuickSelection.mapTo(linkedSetOf()) { it.identity }
                val imageResults = when {
                    cachedImages.isNotEmpty() -> cachedImages.map { it.copy(testedThisRound = false, quickSelected = false) }.toMutableList()
                    imageQuick -> storedImages.measurements.filter { old -> imageQuickSelection.none { it.identity == old.identity } }
                        .map { it.toProbe() }.toMutableList()
                    canReconcileImages ->
                        storedImages.measurements
                            .filter { old -> imageTargets.any { it.identity == old.identity } }
                            .map { it.toProbe() }.toMutableList()
                    else -> mutableListOf()
                }
                var freshImageHistory = emptyList<PicacgSpeedTestHistoryPolicy.Measurement>()
                timings.start(SpeedTestTimings.Phase.PICACG_IMAGES).use {
                    if (targetsToProbeImages.isNotEmpty()) publishProgress(Progress(Stage.TESTING_IMAGES, total = targetsToProbeImages.size))
                    val completed = AtomicInteger(0)
                    val successful = AtomicInteger(0)
                    val failed = AtomicInteger(0)
                    val freshResults = coroutineScope {
                        targetsToProbeImages.map { target ->
                            async {
                                val result = SpeedTestProbeBudget.scheduler.withPermit(
                                    ContentPlatform.PICACG,
                                    { SpeedTestTimingLog.recordQueueWait(SpeedTestTimingLog.Scope.PICACG, it) },
                                ) {
                                    if (isCurrent()) probeImage(target.url, target.endpoint, content = target.content) else null
                                }?.copy(
                                    measuredAtEpochMs = System.currentTimeMillis(),
                                    testedThisRound = true,
                                    quickSelected = target.identity in imageQuickIdentities,
                                )
                                synchronized(imageResults) {
                                    if (result?.success == true) successful.incrementAndGet()
                                    if (result != null && !result.success) failed.incrementAndGet()
                                    val done = completed.incrementAndGet()
                                    publishProgress(
                                        Progress(
                                            Stage.TESTING_IMAGES,
                                            completed = done,
                                            total = targetsToProbeImages.size,
                                            successful = successful.get(),
                                            failed = failed.get(),
                                        ),
                                    )
                                }
                                target to result
                            }
                        }.awaitAll().filter { it.second != null }
                    }
                    imageResults += freshResults.mapNotNull { it.second }
                    freshImageHistory = freshResults.mapNotNull { (target, result) ->
                        result?.let { target.identity.toMeasurement(it, it.measuredAtEpochMs ?: System.currentTimeMillis()) }
                    }
                }
                if (imageTargets.isEmpty() && imageResults.isEmpty() && blockedReason == null) {
                    blockedReason = "哔咔图源没有可探测的 IP；图源验证未完成"
                    publishProgress(Progress(Stage.IMAGE_BLOCKED, total = 0))
                }

                val finished = System.currentTimeMillis()
                val probeOrder = compareBy<Probe>({ if (it.success) it.elapsedMs else Long.MAX_VALUE }, { it.endpoint.domain }, { it.endpoint.ip })
                var result = Snapshot(started, finished, activeVersion, apiResults.sortedWith(probeOrder), imageResults.sortedWith(probeOrder), blockedReason, revision)
                if (beforeApply() && isCurrent() && revision == PicacgRouteManager.configRevision && activeVersion == PicacgRouteManager.remoteVersion) {
                    val apiSelection = if (apiQuick) freshApiHistory.map { it.toProbe() } else apiResults
                    val fastest = apiSelection.filter(Probe::success).minByOrNull(Probe::elapsedMs)
                    if (fastest != null && !PicacgRouteManager.isApiManuallyPinned) {
                        val fastestUrl = apiUrls.firstOrNull { runCatching { URI(it).host.equals(fastest.endpoint.domain, true) }.getOrDefault(false) }
                        if (fastestUrl != null) {
                            PicacgRouteManager.selectApiUrl(fastestUrl, manual = false)
                            PicacgRouteManager.applyAutomaticApiEndpoint(fastest.endpoint)
                        }
                    }
                    val imageSelection = if (imageQuick) freshImageHistory.map { it.toProbe() } else imageResults
                    if (!PicacgRouteManager.isImageManuallyPinned) {
                        // Keep the fastest IP per storage-b root for EACH service independently — covers
                        // (imgproxy) and reader pages (storage-b) — then order roots by that best probe.
                        // Both ranked lists are installed for automatic fallback; never a single winner.
                        fun rankingOf(wantContent: Boolean): List<DomainIp> =
                            imageSelection.filter { it.success && it.content == wantContent }
                                .groupBy { it.endpoint.domain.lowercase() }
                                .map { (_, probes) ->
                                    val best = probes.minByOrNull(Probe::elapsedMs)!!
                                    best.elapsedMs to best.endpoint
                                }
                                .sortedBy { it.first }
                                .map { it.second }
                        val coverRanking = rankingOf(wantContent = false)
                        val contentRanking = rankingOf(wantContent = true)
                        if (!PicacgRouteManager.applyImageRankings(coverRanking, contentRanking)) {
                            // Legacy fallback: if neither ranking validated, pin the single fastest image.
                            val fastestImage = imageSelection.filter(Probe::success).minByOrNull(Probe::elapsedMs)
                            if (fastestImage != null) {
                                PicacgRouteManager.selectImageHost(fastestImage.endpoint.domain, manual = false)
                                PicacgRouteManager.applyAutomaticImageEndpoint(fastestImage.endpoint)
                            }
                        }
                    }
                    val canPersistApiHistory = forceFresh || apiCanUseHistory || apiReconcile || storedApi.remoteVersion == null
                    if (cachedApi.isEmpty() && canPersistApiHistory) {
                        val apiHistory = when {
                            forceFresh -> PicacgSpeedTestHistoryPolicy.freshOnly(activeVersion, freshApiHistory)
                            apiReconcile -> PicacgSpeedTestHistoryPolicy.reconcile(
                                storedApi,
                                activeVersion,
                                apiTargets.mapTo(linkedSetOf(), ApiTarget::identity),
                                freshApiHistory,
                            )
                            apiQuick -> PicacgSpeedTestHistoryPolicy.mergeQuick(storedApi, apiQuickSelection.mapTo(linkedSetOf()) { it.identity }, freshApiHistory)
                            apiCanUseHistory -> PicacgSpeedTestHistoryPolicy.mergeFull(storedApi, freshApiHistory)
                            else -> PicacgSpeedTestHistoryPolicy.freshOnly(activeVersion, freshApiHistory)
                        }
                        PicacgSpeedTestHistoryStorage.saveApi(apiHistory)
                    }
                    val canPersistImageHistory = forceFresh || imageCanUseHistory || imageReconcile || storedImages.remoteVersion == null
                    if (cachedImages.isEmpty() && canPersistImageHistory) {
                        val imageHistory = when {
                            forceFresh -> PicacgSpeedTestHistoryPolicy.freshOnly(activeVersion, freshImageHistory)
                            canReconcileImages -> PicacgSpeedTestHistoryPolicy.reconcile(
                                storedImages,
                                activeVersion,
                                imageTargets.mapTo(linkedSetOf(), ImageTarget::identity),
                                freshImageHistory,
                            )
                            imageQuick -> PicacgSpeedTestHistoryPolicy.mergeQuick(storedImages, imageQuickSelection.mapTo(linkedSetOf()) { it.identity }, freshImageHistory)
                            imageCanUseHistory -> PicacgSpeedTestHistoryPolicy.mergeFull(storedImages, freshImageHistory)
                            else -> PicacgSpeedTestHistoryPolicy.freshOnly(activeVersion, freshImageHistory)
                        }
                        // forceFresh intentionally clears stale image rows; a configured pool records this round.
                        if (forceFresh || canReconcileImages || PicacgRouteManager.imageHosts.isNotEmpty()) {
                            PicacgSpeedTestHistoryStorage.saveImages(imageHistory)
                        }
                    }
                    result = result.copy(
                        roundSelectedLine = SpeedTestLogSelectionPolicy.selectedEndpoint(
                            PicacgRouteManager.selectedApiEndpoint,
                            apiResults.filter(Probe::testedThisRound).mapTo(hashSetOf(), Probe::endpoint),
                        ) { endpoint -> apiResults.any { it.endpoint == endpoint && it.testedThisRound && it.success } },
                        roundSelectedImage = SpeedTestLogSelectionPolicy.selectedEndpoint(
                            PicacgRouteManager.selectedImageEndpoint,
                            imageResults.filter(Probe::testedThisRound).mapTo(hashSetOf(), Probe::endpoint),
                        ) { endpoint -> blockedReason == null && imageResults.any { it.endpoint == endpoint && it.testedThisRound && it.success } },
                    )
                    _snapshot.value = result
                }
                val measuredApiResults = apiResults.filter(Probe::testedThisRound)
                publishProgress(
                    Progress(
                        Stage.COMPLETE,
                        apiResults.size,
                        apiResults.size,
                        measuredApiResults.count(Probe::success),
                        measuredApiResults.count { !it.success },
                    ),
                )
                result
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
                "GET-storage-b-page-tile;redirects-disabled;status-200;image-content-type;image-magic"
            } else {
                "GET-imgproxy-tile;redirects-disabled;status-200;image-content-type;image-magic"
            },
        )
    }

    /** Cover tiles are imgproxy `/insecure/...` urls; reader-page tiles are storage-b `/static/...` urls. */
    private fun isContentTileUrl(u: String): Boolean = !u.contains("/insecure/")

    private fun PicacgSpeedTestHistoryPolicy.Measurement.toProbe() = Probe(
        endpoint = endpoint,
        success = success,
        elapsedMs = elapsedMs,
        httpStatus = httpStatus,
        contentType = contentType,
        imageDecoded = imageDecoded,
        measuredAtEpochMs = measuredAtEpochMs,
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
    internal suspend fun probeForDeprecatedScan(endpoint: DomainIp, url: String, image: Boolean): Boolean =
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
            runInterruptible {
                val client = PicacgRouteManager.newDiagnosticPinnedClient(baseProbeClient, endpoint)
                try {
                    if (image) probeImage(url, endpoint, client).success else probeApi(url, endpoint, client).success
                } finally {
                    client.connectionPool.evictAll()
                }
            }
        }

    private fun probeApi(baseUrl: String, endpoint: DomainIp, clientOverride: OkHttpClient? = null): Probe {
        val start = System.nanoTime()
        var code: Int? = null
        if (!PicacgNetworkPolicy.apiBaseAllowed(baseUrl)) return Probe(endpoint, false, elapsedMs(start), null)
        val success = runCatching {
            val client = clientOverride ?: PicacgRouteManager.newPinnedClient(baseProbeClient, endpoint)
            val request = Request.Builder().url(baseUrl.trimEnd('/') + "/").get().build()
            client.newCall(request).execute().use { response ->
                code = response.code
                response.closeBodyQuietly()
                PicacgNetworkPolicy.apiRootReachable(response.code)
            }
        }.getOrDefault(false)
        return Probe(endpoint, success, elapsedMs(start), code)
    }

    private fun probeImage(
        url: String,
        endpoint: DomainIp,
        clientOverride: OkHttpClient? = null,
        content: Boolean = false,
    ): Probe {
        val start = System.nanoTime()
        var code: Int? = null
        var contentType: String? = null
        var validImage: Boolean? = false
        val success = runCatching {
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
            // Fetch one tile and validate status + content-type + image magic on the head bytes.
            fun fetchTile(testUrl: String): Boolean =
                client.newCall(Request.Builder().url(testUrl).header("User-Agent", "okhttp/3.8.1").get().build()).execute().use { response ->
                    code = response.code
                    contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                    if (response.code != 200 || contentType?.startsWith("image/") != true) {
                        response.closeBodyQuietly()
                        false
                    } else {
                        val bytes = response.body?.byteStream()?.use { readImageHead(it, MAX_IMAGE_HEAD_BYTES) }
                            ?: throw IOException("Empty image body")
                        validImage = hasImageMagic(bytes)
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
        }.getOrDefault(false)
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

    /** Magic-byte recognition for JPEG / PNG / GIF / WEBP; cheaper than decoding pixels and enough to rank sources. */
    private fun hasImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return true
        }
        if (bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return true
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return true
        }
        return false
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
