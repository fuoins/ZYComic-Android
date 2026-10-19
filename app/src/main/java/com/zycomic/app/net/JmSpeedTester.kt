package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** JM 独立测速器；只探测 JM API 线路和 sources2 图源池。 */
object JmSpeedTester {
    enum class ImgVerdict { OK, UNREACHABLE, NO_IMAGE }

    private data class ProbeResult(val delayMs: Long, val elapsedMs: Long, val verdict: ImgVerdict? = null)
    private data class LineTarget(val endpoint: DomainIp, val url: String)

    private val lock = Mutex()
    private val running = AtomicBoolean(false)
    private val dnsResolver = JmProbeDnsResolver()
    val isRunning: Boolean get() = running.get()

    /** Optionally retest the five best historical endpoints independently for API lines and images. */
    suspend fun run(
        reuseLines: Boolean = false,
        reuseImages: Boolean = false,
        isCurrent: () -> Boolean = { true },
        onProgress: ((JmSpeedTestProgress) -> Unit)? = null,
    ): JmRouteManager.SpeedTestSnapshot = lock.withLock {
        check(running.compareAndSet(false, true)) { "JM 测速已在运行" }
        try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val platformRevision = StartupPlatformPolicy.state.value.revision
                    if (!StartupPlatformPolicy.isEnabled(ContentPlatform.JM)) {
                        return@coroutineScope JmRouteManager.SpeedTestSnapshot()
                    }
                    val configRevision = JmRouteManager.currentSpeedTestConfigRevision()
                    val configFingerprint = JmRouteManager.currentSpeedTestConfigFingerprint()
                    val imageConfigFingerprint = JmRouteManager.currentImageConfigFingerprint()
                    val imageNetworkPolicy = JmRouteManager.currentImageNetworkPolicy()
                    val linesToTest = JmRouteManager.lineHosts.toList()
                    val imagesToTest = JmRouteManager.imageDomains.toList()
                    val prior = JmRouteManager.speedTestSnapshot.value
                    val priorVersionMatches = prior.remoteVersion == JmRouteManager.state.value.remoteVersion &&
                        prior.completedAtEpochMs != null && prior.completedAtEpochMs > 0L
                    val priorLineDelays = if (priorVersionMatches) {
                        (prior.lineIpDelaysMs + prior.untestedLineIpDelaysMs).filter { (endpoint, delay) ->
                            endpoint.domain.isNotBlank() && endpoint.ip.isNotBlank() && delay >= 0L
                        }
                    } else {
                        emptyMap()
                    }
                    val priorImageVerdicts = prior.imageIpVerdicts + prior.untestedImageIpVerdicts
                    val priorImageDelays = if (priorVersionMatches) {
                        (prior.imageIpDelaysMs + prior.untestedImageIpDelaysMs).filter { (endpoint, delay) ->
                            val verdict = priorImageVerdicts[endpoint]
                            endpoint.domain.isNotBlank() && endpoint.ip.isNotBlank() && delay >= 0L &&
                                when (verdict) {
                                    ImgVerdict.OK -> delay < Long.MAX_VALUE
                                    ImgVerdict.NO_IMAGE, ImgVerdict.UNREACHABLE -> delay == Long.MAX_VALUE
                                    null -> false
                                }
                        }
                    } else {
                        emptyMap()
                    }
                    val rankedPriorLineDelays = JmSpeedTestQuickProbePolicy.rankPriorDelays(
                        prior.lineIpDelaysMs,
                        prior.untestedLineIpDelaysMs,
                    )
                    val rankedPriorImageDelays = JmSpeedTestQuickProbePolicy.rankPriorDelays(
                        prior.imageIpDelaysMs,
                        prior.untestedImageIpDelaysMs,
                    )
                    val lineQuickEndpoints = JmSpeedTestQuickProbePolicy.selectPriorEndpoints(
                        priorVersion = prior.remoteVersion,
                        currentVersion = JmRouteManager.state.value.remoteVersion,
                        priorCompletedAtEpochMs = prior.completedAtEpochMs,
                        delays = rankedPriorLineDelays,
                    )
                    val imageQuickEndpoints = JmSpeedTestQuickProbePolicy.selectPriorEndpoints(
                        priorVersion = prior.remoteVersion,
                        currentVersion = JmRouteManager.state.value.remoteVersion,
                        priorCompletedAtEpochMs = prior.completedAtEpochMs,
                        delays = rankedPriorImageDelays,
                    )
                    val quickLines = reuseLines && lineQuickEndpoints.isNotEmpty()
                    val quickImages = reuseImages && imageQuickEndpoints.isNotEmpty()
                    fun stillCurrent(): Boolean =
                        isCurrent() && StartupPlatformPolicy.isEnabled(ContentPlatform.JM) &&
                            StartupPlatformPolicy.state.value.revision == platformRevision &&
                            JmRouteManager.currentSpeedTestConfigRevision() == configRevision &&
                            JmRouteManager.currentSpeedTestConfigFingerprint() == configFingerprint &&
                            JmRouteManager.currentImageConfigFingerprint() == imageConfigFingerprint &&
                            JmRouteManager.currentImageNetworkPolicy() == imageNetworkPolicy &&
                            JmRouteManager.lineHosts == linesToTest && JmRouteManager.imageDomains == imagesToTest

                    val started = System.currentTimeMillis()
                    val startedNanos = System.nanoTime()
                    val progressGuard = Any()
                    var progress = JmSpeedTestProgress()
                    fun publish(next: (JmSpeedTestProgress) -> JmSpeedTestProgress) = synchronized(progressGuard) {
                        progress = next(progress).copy(elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L)
                        if (stillCurrent()) onProgress?.invoke(progress)
                    }
                    fun phase(value: JmSpeedTestProgress.Phase, total: Int) = publish {
                        JmSpeedTestProgress(phase = value, total = total)
                    }
                    fun endpointStarted(endpoint: DomainIp) = publish {
                        it.copy(endpoint = "${endpoint.domain} [${endpoint.ip}]", endpointSuccessful = null, endpointElapsedMs = null)
                    }
                    fun endpointDone(endpoint: DomainIp, result: ProbeResult) = publish {
                        val ok = result.delayMs < Long.MAX_VALUE
                        it.copy(
                            completed = it.completed + 1,
                            successful = it.successful + if (ok) 1 else 0,
                            failed = it.failed + if (ok) 0 else 1,
                            endpoint = "${endpoint.domain} [${endpoint.ip}]",
                            endpointSuccessful = ok,
                            endpointElapsedMs = result.elapsedMs,
                        )
                    }
                    val ticker = launch {
                        while (isActive) {
                            delay(500)
                            publish { it }
                        }
                    }
                    val runner = JmEndpointProbeRunner()
                    val probeClient = JmNetworkModule.newProbeClient()
                    val lineClient = probeClient.newBuilder().addInterceptor(JmApiInterceptor(recordRouteHealth = false)).build()
                    try {
                        val resolvedLines = if (quickLines) {
                            lineQuickEndpoints.map { endpoint ->
                                val configuredUrl = linesToTest.firstOrNull { runCatching { URI(it).host.equals(endpoint.domain, true) }.getOrDefault(false) }
                                LineTarget(endpoint, "${(configuredUrl ?: "https://${endpoint.domain}").trimEnd('/')}${JmSpeedProbeProtocol.LINE_PATH}")
                            }
                        } else {
                            phase(JmSpeedTestProgress.Phase.PREPARING_LINES, linesToTest.size)
                            runner.run(
                                linesToTest,
                                probe = { url ->
                                    val host = URI(url).host
                                    DomainIpProbePolicy.endpoints(host, resolveProbeIps(host)).map {
                                        LineTarget(it, url.trimEnd('/') + JmSpeedProbeProtocol.LINE_PATH)
                                    }
                                },
                                onStarted = { url -> publish { it.copy(endpoint = url, endpointSuccessful = null, endpointElapsedMs = null) } },
                                onResult = { url, targets ->
                                    publish {
                                        it.copy(
                                            completed = it.completed + 1,
                                            endpoint = url,
                                            endpointSuccessful = targets.isNotEmpty(),
                                            successful = it.successful + if (targets.isNotEmpty()) 1 else 0,
                                            failed = it.failed + if (targets.isEmpty()) 1 else 0,
                                        )
                                    }
                                },
                            ).flatMap { it.second }.distinctBy { it.endpoint }
                        }
                        phase(JmSpeedTestProgress.Phase.TESTING_LINES, resolvedLines.size)
                        val lineResults = runner.run(
                            resolvedLines,
                            probe = { target -> measureAtIp(lineClient, target.endpoint, target.url, false, ::stillCurrent) },
                            onStarted = { endpointStarted(it.endpoint) },
                            onResult = { target, result ->
                                if (stillCurrent()) {
                                    JmRouteManager.record(
                                        "speed_line_ip",
                                        "${target.endpoint.domain} [${target.endpoint.ip}]",
                                        "JM API ${if (result.delayMs < Long.MAX_VALUE) "${result.delayMs}ms" else "不可达"}",
                                    )
                                    endpointDone(target.endpoint, result)
                                }
                            },
                        )
                        val freshLineIpDelays = lineResults.associate { it.first.endpoint to it.second.delayMs }
                        val testedLineEndpoints = freshLineIpDelays.keys
                        val untestedLineIpDelays = if (quickLines) priorLineDelays.filterKeys { it !in testedLineEndpoints } else emptyMap()
                        val lineIpDelays = JmSpeedTestQuickProbePolicy.rankPriorDelays(freshLineIpDelays, emptyMap())
                        val lineIpTestedAt = lineIpDelays.keys.associateWith { System.currentTimeMillis() }
                        val untestedLineIpTestedAt = JmSpeedTestQuickProbePolicy.mergeTestedAt(
                            if (quickLines) prior.lineIpTestedAtEpochMs + prior.untestedLineIpTestedAtEpochMs else emptyMap(),
                            emptyMap(),
                            testedLineEndpoints,
                            lineIpDelays.keys,
                        ).filterKeys { it in untestedLineIpDelays }
                        val lineDelays = linesToTest.associate { url ->
                            val host = URI(url).host.lowercase(Locale.ROOT)
                            host to (freshLineIpDelays.filterKeys { it.domain.equals(host, true) }.values.minOrNull() ?: Long.MAX_VALUE)
                        }

                        val imageEndpoints = if (quickImages) {
                            imageQuickEndpoints
                        } else {
                            phase(JmSpeedTestProgress.Phase.PREPARING_IMAGES, imagesToTest.size)
                            runner.run(
                                imagesToTest,
                                probe = { domain -> DomainIpProbePolicy.endpoints(domain, resolveProbeIps(domain, image = true)) },
                                onStarted = { domain -> publish { it.copy(endpoint = domain, endpointSuccessful = null, endpointElapsedMs = null) } },
                                onResult = { domain, endpoints ->
                                    publish {
                                        it.copy(
                                            completed = it.completed + 1,
                                            endpoint = domain,
                                            endpointSuccessful = endpoints.isNotEmpty(),
                                            successful = it.successful + if (endpoints.isNotEmpty()) 1 else 0,
                                            failed = it.failed + if (endpoints.isEmpty()) 1 else 0,
                                        )
                                    }
                                },
                            ).flatMap { it.second }.distinct()
                        }
                        phase(JmSpeedTestProgress.Phase.TESTING_IMAGES, imageEndpoints.size)
                        val imageResults = runner.run(
                            imageEndpoints,
                            probe = { endpoint ->
                                measureAtIp(
                                    probeClient,
                                    endpoint,
                                    "https://${endpoint.domain}${JmSpeedProbeProtocol.IMAGE_PATH}",
                                    true,
                                    ::stillCurrent,
                                )
                            },
                            onStarted = ::endpointStarted,
                            onResult = { endpoint, result ->
                                if (stillCurrent()) {
                                    JmRouteManager.record(
                                        "speed_image_ip",
                                        "${endpoint.domain} [${endpoint.ip}]",
                                        "JM 图源 ${result.verdict}; ${result.elapsedMs}ms",
                                    )
                                    endpointDone(endpoint, result)
                                }
                            },
                        )
                        val freshImageIpDelays = imageResults.associate { it.first to it.second.delayMs }
                        val freshImageIpVerdicts = imageResults.associate { it.first to (it.second.verdict ?: ImgVerdict.UNREACHABLE) }
                        val testedImageEndpoints = freshImageIpDelays.keys
                        val untestedImageIpDelays = if (quickImages) priorImageDelays.filterKeys { it !in testedImageEndpoints } else emptyMap()
                        val untestedImageIpVerdicts = priorImageVerdicts.filterKeys { it in untestedImageIpDelays }
                        val imageIpDelays = JmSpeedTestQuickProbePolicy.rankPriorDelays(freshImageIpDelays, emptyMap())
                        val imageIpVerdicts = freshImageIpVerdicts
                        val imageIpTestedAt = JmSpeedTestQuickProbePolicy.mergeTestedAt(
                            emptyMap(),
                            imageIpDelays.keys.associateWith { System.currentTimeMillis() },
                            testedImageEndpoints,
                            imageIpDelays.keys,
                        )
                        val untestedImageIpTestedAt = JmSpeedTestQuickProbePolicy.mergeTestedAt(
                            if (quickImages) prior.imageIpTestedAtEpochMs + prior.untestedImageIpTestedAtEpochMs else emptyMap(),
                            emptyMap(),
                            testedImageEndpoints,
                        ).filterKeys { it in untestedImageIpDelays }
                        val imageDelays = imagesToTest.associate { domain ->
                            domain.lowercase(Locale.ROOT) to (freshImageIpDelays.filterKeys { it.domain.equals(domain, true) }.values.minOrNull() ?: Long.MAX_VALUE)
                        }
                        val snapshot = JmRouteManager.SpeedTestSnapshot(
                            lineDelaysMs = lineDelays, imageDelaysMs = imageDelays,
                            lineIpDelaysMs = lineIpDelays, imageIpDelaysMs = imageIpDelays, imageIpVerdicts = imageIpVerdicts,
                            lineIpTestedAtEpochMs = lineIpTestedAt, imageIpTestedAtEpochMs = imageIpTestedAt,
                            untestedLineIpDelaysMs = untestedLineIpDelays,
                            untestedImageIpDelaysMs = untestedImageIpDelays,
                            untestedImageIpVerdicts = untestedImageIpVerdicts,
                            untestedLineIpTestedAtEpochMs = untestedLineIpTestedAt,
                            untestedImageIpTestedAtEpochMs = untestedImageIpTestedAt,
                            testedLineEndpoints = testedLineEndpoints, testedImageEndpoints = testedImageEndpoints,
                            lineQuickTest = quickLines, imageQuickTest = quickImages,
                            lineQuickTestAllFailed = quickLines && freshLineIpDelays.isNotEmpty() && freshLineIpDelays.values.none { it < Long.MAX_VALUE },
                            imageQuickTestAllFailed = quickImages && freshImageIpDelays.isNotEmpty() && freshImageIpDelays.values.none { it < Long.MAX_VALUE },
                            startedAtEpochMs = started, completedAtEpochMs = System.currentTimeMillis(),
                            remoteVersion = JmRouteManager.state.value.remoteVersion,
                            configFingerprint = configFingerprint, imageConfigFingerprint = imageConfigFingerprint,
                            imageNetworkPolicy = imageNetworkPolicy, imageResultsReused = false,
                        )
                        if (stillCurrent()) {
                            JmRouteManager.recordSpeedTest(snapshot, platformRevision)
                            publish { it.copy(phase = JmSpeedTestProgress.Phase.COMPLETE) }
                        }
                        snapshot
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        publish { it.copy(phase = JmSpeedTestProgress.Phase.FAILED) }
                        throw error
                    } finally {
                        ticker.cancel()
                        probeClient.dispatcher.cancelAll()
                        probeClient.connectionPool.evictAll()
                        probeClient.dispatcher.executorService.shutdown()
                    }
                }
            }
        } finally {
            running.set(false)
        }
    }

    /** Apply a persisted exact-match snapshot without issuing any requests. */
    suspend fun reuseValidResults(): Boolean = lock.withLock {
        val revision = StartupPlatformPolicy.state.value.revision
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.JM)) return@withLock false
        withContext(Dispatchers.IO) {
            StartupPlatformPolicy.state.value.revision == revision &&
                StartupPlatformPolicy.isEnabled(ContentPlatform.JM) && JmRouteManager.reuseValidResults(revision) &&
                StartupPlatformPolicy.state.value.revision == revision && StartupPlatformPolicy.isEnabled(ContentPlatform.JM)
        }
    }

    private suspend fun resolveProbeIps(host: String, image: Boolean = false): List<String> {
        val configured = if (image) JmRouteManager.resolveImageIp(host) else JmRouteManager.resolveLineIp(host).map(String::trim).filter(String::isNotEmpty).distinct()
        if (configured.isNotEmpty()) {
            return configured.filter { ip -> ':' in ip || ip.all { it.isDigit() || it == '.' } }
                .mapNotNull { ip -> runCatching { InetAddress.getByName(ip).hostAddress.substringBefore('%') }.getOrNull() }.distinct()
        }
        return dnsResolver.resolve(host)
    }

    /** Pin the socket address only; retain the request host for Host, TLS SNI and certificate checks. */
    internal fun newProbeClientForIp(client: OkHttpClient, endpoint: DomainIp): OkHttpClient =
        client.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname.equals(endpoint.domain, true)) {
                        listOf(InetAddress.getByName(endpoint.ip))
                    } else {
                        client.dns.lookup(hostname)
                    }
            })
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()

    private suspend fun measureAtIp(
        client: OkHttpClient,
        endpoint: DomainIp,
        url: String,
        image: Boolean,
        stillCurrent: () -> Boolean,
    ): ProbeResult {
        if (!stillCurrent()) return ProbeResult(Long.MAX_VALUE, 0, if (image) ImgVerdict.UNREACHABLE else null)
        val routed = newProbeClientForIp(client, endpoint)
        return try {
            measure(routed, url, image)
        } finally {
            routed.connectionPool.evictAll()
        }
    }

    private suspend fun measure(client: OkHttpClient, url: String, image: Boolean): ProbeResult = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).get()
            .header("User-Agent", JmApiInterceptor.USER_AGENT)
            .header("Origin", JmApiInterceptor.ORIGIN)
            .header("Referer", JmApiInterceptor.REFERER)
            .header("X-Requested-With", JmApiInterceptor.PACKAGE_NAME)
            .header("Accept", if (image) "image/*,*/*;q=0.8" else "application/json")
            .apply { if (image) header("Range", "bytes=0-2047") }
            .build()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000L
        fun failed() = ProbeResult(Long.MAX_VALUE, elapsed(), if (image) ImgVerdict.UNREACHABLE else null)
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(failed())
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        // API bodies are already signed/decrypted by JmApiInterceptor; never inspect the raw envelope.
                        val body = if (image) {
                            response.body?.source()?.let { source ->
                                source.request(2048)
                                source.buffer.clone().readByteArray(minOf(source.buffer.size, 2048L))
                            } ?: ByteArray(0)
                        } else {
                            response.body?.bytes() ?: ByteArray(0)
                        }
                        val validStatus = if (image) response.code == 200 || response.code == 206 else response.isSuccessful
                        val valid = validStatus && if (image) {
                            JmSpeedProbeProtocol.isValidImageResponse(response.header("Content-Type"), body)
                        } else {
                            JmSpeedProbeProtocol.isValidDecryptedJson(body.toString(Charsets.UTF_8))
                        }
                        if (valid) {
                            ProbeResult(elapsed(), elapsed(), if (image) ImgVerdict.OK else null)
                        } else {
                            ProbeResult(
                                Long.MAX_VALUE,
                                elapsed(),
                                if (image && response.isSuccessful) {
                                    ImgVerdict.NO_IMAGE
                                } else if (image) {
                                    ImgVerdict.UNREACHABLE
                                } else {
                                    null
                                },
                            )
                        }
                    }
                } catch (_: Exception) {
                    failed()
                }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
}
