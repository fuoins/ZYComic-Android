package com.zycomic.app.net

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
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

    suspend fun run(
        reuseApi: Boolean = false,
        reuseImages: Boolean = false,
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
                val started = System.currentTimeMillis()
                val previous = _snapshot.value?.takeIf {
                    it.revision == revision && it.remoteVersion == PicacgRouteManager.remoteVersion
                }
                val cachedApi = previous?.apiProbes.orEmpty().takeIf { reuseApi }.orEmpty()
                val cachedImages = previous?.imageProbes.orEmpty().takeIf { reuseImages }.orEmpty()
                val apiResults = cachedApi.toMutableList()
                val imageResults = cachedImages.toMutableList()
                val urls = PicacgRouteManager.apiUrls
                val apiEndpoints = timings.start(SpeedTestTimings.Phase.PICACG_LINE_PREPARATION).use {
                    publishProgress(Progress(Stage.PREPARING_API, total = urls.size))
                    if (cachedApi.isNotEmpty()) {
                        emptyList()
                    } else {
                        urls.flatMap { baseUrl ->
                            val host = runCatching { URI(baseUrl).host }.getOrNull()
                            if (host == null) emptyList() else resolve(host).map { ip -> baseUrl to DomainIp(host, ip) }
                        }
                    }
                }
                timings.start(SpeedTestTimings.Phase.PICACG_LINES).use {
                    publishProgress(Progress(Stage.TESTING_API, total = apiEndpoints.size))
                    apiEndpoints.forEachIndexed { index, (baseUrl, endpoint) ->
                        if (isCurrent()) apiResults += probeApi(baseUrl, endpoint)
                        publishProgress(
                            Progress(
                                Stage.TESTING_API,
                                completed = index + 1,
                                total = apiEndpoints.size,
                                successful = apiResults.count(Probe::success),
                                failed = apiResults.count { !it.success },
                            ),
                        )
                    }
                }

                var blockedReason: String? = null
                val actualSampleUrl = PicacgRouteManager.verifiedImageSampleUrl
                val imageEndpoints = timings.start(SpeedTestTimings.Phase.PICACG_IMAGE_PREPARATION).use {
                    publishProgress(Progress(Stage.PREPARING_IMAGES, total = PicacgRouteManager.imageHosts.size))
                    if (cachedImages.isNotEmpty()) {
                        emptyList()
                    } else if (actualSampleUrl == null) {
                        blockedReason = "没有来自真实 Picacg 封面/章节图片 API 响应的样本 URL；图源验证未运行（不可仅探测根路径或伪造成功）"
                        emptyList()
                    } else {
                        val sample = runCatching { URI(actualSampleUrl) }.getOrNull()
                        if (sample?.host == null || PicacgRouteManager.imageHosts.none { it.equals(sample.host, true) }) {
                            blockedReason = "实际图片样本不属于当前配置图源池；图源验证未运行"
                            emptyList()
                        } else {
                            PicacgRouteManager.imageHosts.flatMap { imageHost ->
                                val candidateUrl = replaceHost(actualSampleUrl, imageHost)
                                resolve(imageHost).map { ip -> candidateUrl to DomainIp(imageHost, ip) }
                            }
                        }
                    }
                }
                if (blockedReason != null) publishProgress(Progress(Stage.IMAGE_BLOCKED, total = 0))
                timings.start(SpeedTestTimings.Phase.PICACG_IMAGES).use {
                    if (imageEndpoints.isNotEmpty()) publishProgress(Progress(Stage.TESTING_IMAGES, total = imageEndpoints.size))
                    imageEndpoints.forEachIndexed { index, (url, endpoint) ->
                        if (isCurrent()) imageResults += probeImage(url, endpoint)
                        publishProgress(
                            Progress(
                                Stage.TESTING_IMAGES,
                                completed = index + 1,
                                total = imageEndpoints.size,
                                successful = imageResults.count(Probe::success),
                                failed = imageResults.count { !it.success },
                            ),
                        )
                    }
                }
                if (imageEndpoints.isEmpty() && imageResults.isEmpty() && blockedReason == null) {
                    blockedReason = "真实图片样本可用，但当前 sources3 图源没有可探测的 IP；图源验证未完成"
                    publishProgress(Progress(Stage.IMAGE_BLOCKED, total = 0))
                }

                val finished = System.currentTimeMillis()
                val result = Snapshot(started, finished, PicacgRouteManager.remoteVersion, apiResults, imageResults, blockedReason, revision)
                if (isCurrent() && revision == PicacgRouteManager.configRevision && beforeApply()) {
                    val fastest = apiResults.filter(Probe::success).minByOrNull(Probe::elapsedMs)
                    if (fastest != null && !PicacgRouteManager.isApiManuallyPinned) {
                        val fastestUrl = urls.firstOrNull { runCatching { URI(it).host.equals(fastest.endpoint.domain, true) }.getOrDefault(false) }
                        if (fastestUrl != null) {
                            PicacgRouteManager.selectApiUrl(fastestUrl, manual = false)
                            PicacgRouteManager.applyAutomaticApiEndpoint(fastest.endpoint)
                        }
                    }
                    val fastestImage = imageResults.filter(Probe::success).minByOrNull(Probe::elapsedMs)
                    if (fastestImage != null && !PicacgRouteManager.isImageManuallyPinned) {
                        PicacgRouteManager.selectImageHost(fastestImage.endpoint.domain, manual = false)
                        PicacgRouteManager.applyAutomaticImageEndpoint(fastestImage.endpoint)
                    }
                    _snapshot.value = result
                }
                publishProgress(Progress(Stage.COMPLETE, apiResults.size, apiResults.size, apiResults.count(Probe::success), apiResults.count { !it.success }))
                result
            }
        }
    }

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
            val client = clientOverride ?: PicacgRouteManager.newPinnedClient(baseProbeClient, endpoint)
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
        return URI(uri.scheme, null, host, uri.port, uri.path, uri.query, uri.fragment).toASCIIString()
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
    }

    private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
    private const val MAX_DECODED_PIXELS = 4_000_000L
}
