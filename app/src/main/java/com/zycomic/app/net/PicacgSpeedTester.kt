package com.zycomic.app.net

import android.graphics.BitmapFactory
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
    )

    data class Snapshot(
        val startedAtEpochMs: Long,
        val completedAtEpochMs: Long,
        val remoteVersion: Int?,
        val apiProbes: List<Probe>,
        val imageProbes: List<Probe>,
        val imageBlockedReason: String?,
        val revision: Long,
    ) {
        val apiComplete: Boolean get() = apiProbes.isNotEmpty()
        val imageComplete: Boolean get() = imageProbes.isNotEmpty()
        val complete: Boolean get() = apiComplete && imageComplete
    }

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
    private data class ImageTarget(val url: String, val endpoint: DomainIp, val identity: PicacgSpeedTestHistoryPolicy.Identity)

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
                val actualSampleUrl = PicacgRouteManager.verifiedImageSampleUrl
                val imageTargets = timings.start(SpeedTestTimings.Phase.PICACG_IMAGE_PREPARATION).use {
                    publishProgress(Progress(Stage.PREPARING_IMAGES, total = PicacgRouteManager.imageHosts.size))
                    when {
                        cachedImages.isNotEmpty() || imageQuick -> emptyList()
                        actualSampleUrl == null -> {
                            blockedReason = "没有来自真实 Picacg 封面/章节图片 API 响应的样本 URL；图源验证未运行（不可仅探测根路径或伪造成功）"
                            emptyList()
                        }
                        else -> {
                            val sample = runCatching { URI(actualSampleUrl) }.getOrNull()
                            if (sample?.host == null || PicacgRouteManager.imageHosts.none { it.equals(sample.host, true) }) {
                                blockedReason = "实际图片样本不属于当前配置图源池；图源验证未运行"
                                emptyList()
                            } else {
                                PicacgRouteManager.imageHosts.flatMap { imageHost ->
                                    val candidateUrl = replaceHost(actualSampleUrl, imageHost)
                                    resolve(imageHost).map { ip ->
                                        val endpoint = DomainIp(imageHost, ip)
                                        ImageTarget(candidateUrl, endpoint, imageIdentity(candidateUrl, endpoint))
                                    }
                                }
                            }
                        }
                    }
                }
                if (blockedReason != null) publishProgress(Progress(Stage.IMAGE_BLOCKED, total = 0))
                val canReconcileImages = imageReconcile &&
                    ((actualSampleUrl != null && blockedReason == null) || PicacgRouteManager.imageHosts.isEmpty())
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
                                    if (isCurrent()) probeImage(target.url, target.endpoint) else null
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
                    blockedReason = "真实图片样本可用，但当前哔咔图源没有可探测的 IP；图源验证未完成"
                    publishProgress(Progress(Stage.IMAGE_BLOCKED, total = 0))
                }

                val finished = System.currentTimeMillis()
                val probeOrder = compareBy<Probe>({ if (it.success) it.elapsedMs else Long.MAX_VALUE }, { it.endpoint.domain }, { it.endpoint.ip })
                val result = Snapshot(started, finished, activeVersion, apiResults.sortedWith(probeOrder), imageResults.sortedWith(probeOrder), blockedReason, revision)
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
                    val fastestImage = imageSelection.filter(Probe::success).minByOrNull(Probe::elapsedMs)
                    if (fastestImage != null && !PicacgRouteManager.isImageManuallyPinned) {
                        PicacgRouteManager.selectImageHost(fastestImage.endpoint.domain, manual = false)
                        PicacgRouteManager.applyAutomaticImageEndpoint(fastestImage.endpoint)
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
                        // Missing or invalid real sample never creates an image success. forceFresh intentionally clears stale image rows.
                        if (forceFresh || canReconcileImages || (actualSampleUrl != null && blockedReason == null) || imageTargets.isNotEmpty()) {
                            PicacgSpeedTestHistoryStorage.saveImages(imageHistory)
                        }
                    }
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

    private fun imageIdentity(url: String, endpoint: DomainIp) = PicacgSpeedTestHistoryPolicy.Identity(
        platform = PicacgSpeedTestHistoryPolicy.PLATFORM,
        phase = PicacgSpeedTestHistoryPolicy.IMAGE_PHASE,
        url = url,
        domain = endpoint.domain,
        ip = endpoint.ip,
        routeCondition = routeCondition(PicacgSpeedTestHistoryPolicy.IMAGE_ROUTE_CONDITION, endpoint),
        probeCondition = "GET-full-real-sample;status-200;image-content-type;bitmap-decodes",
    )

    private fun PicacgSpeedTestHistoryPolicy.Measurement.toProbe() = Probe(
        endpoint = endpoint,
        success = success,
        elapsedMs = elapsedMs,
        httpStatus = httpStatus,
        contentType = contentType,
        imageDecoded = imageDecoded,
        measuredAtEpochMs = measuredAtEpochMs,
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

    private fun PicacgSpeedTestHistoryPolicy.Measurement.toImageTarget() = ImageTarget(identity.url, endpoint, identity)

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

    private fun probeImage(url: String, endpoint: DomainIp, clientOverride: OkHttpClient? = null): Probe {
        val start = System.nanoTime()
        var code: Int? = null
        var contentType: String? = null
        var decoded: Boolean? = false
        val success = runCatching {
            // Follow the actual storage-b -> safedataplj chain; never turn redirect targets into selectable origins.
            val client = clientOverride ?: PicacgRouteManager.newPinnedClient(baseProbeClient, endpoint).newBuilder()
                .followRedirects(true)
                .followSslRedirects(false)
                .build()
            val request = Request.Builder().url(url).header("User-Agent", "okhttp/3.8.1").get().build()
            client.newCall(request).execute().use { response ->
                code = response.code
                contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (response.code != 200 || contentType?.startsWith("image/") != true) {
                    response.closeBodyQuietly()
                    false
                } else {
                    val bytes = response.body?.byteStream()?.use(::readBounded) ?: throw IOException("Empty image body")
                    decoded = decodeImage(bytes)
                    PicacgNetworkPolicy.imageSampleVerified(response.code, contentType, decoded == true)
                }
            }
        }.getOrDefault(false)
        return Probe(endpoint, success, elapsedMs(start), code, contentType, decoded)
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_IMAGE_BYTES) throw IOException("Image sample exceeds safe decode limit")
            output.write(buffer, 0, count)
        }
        if (total == 0) throw IOException("Empty image body")
        return output.toByteArray()
    }

    private fun decodeImage(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sampleSize = 1
        while (bounds.outWidth / sampleSize.toLong() * (bounds.outHeight / sampleSize.toLong()) > MAX_DECODED_PIXELS) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtMost(64) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        return try {
            bitmap.width > 0 && bitmap.height > 0
        } finally {
            bitmap.recycle()
        }
    }

    private fun replaceHost(url: String, host: String): String {
        val uri = URI(url)
        return buildString {
            append(uri.scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }.let(::URI).toASCIIString()
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

    private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    private const val MAX_DECODED_PIXELS = 4_000_000L
}
