package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.net.Proxy
import java.net.URI
import java.util.Locale
import java.util.UUID
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
    private val _lastSubmission = MutableStateFlow<JmSpeedTestSubmission?>(null)
    val lastSubmission = _lastSubmission.asStateFlow()

    data class RunResult(val snapshot: JmRouteManager.SpeedTestSnapshot, val submission: JmSpeedTestSubmission?)

    /** Optionally retest the five best historical endpoints independently for API lines and images. */
    suspend fun run(
        reuseLines: Boolean = false,
        reuseImages: Boolean = false,
        reconcileConfig: Boolean = false,
        isCurrent: () -> Boolean = { true },
        beforeApply: suspend () -> Boolean = { true },
        onProgress: ((JmSpeedTestProgress) -> Unit)? = null,
    ): RunResult = lock.withLock {
        check(running.compareAndSet(false, true)) { "JM 测速已在运行" }
        _lastSubmission.value = null
        try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val platformRevision = StartupPlatformPolicy.state.value.revision
                    if (!StartupPlatformPolicy.isEnabled(ContentPlatform.JM)) {
                        return@coroutineScope RunResult(JmRouteManager.SpeedTestSnapshot(), null)
                    }
                    val configVersion = JmRouteManager.state.value.remoteVersion
                    val configRevision = JmRouteManager.currentSpeedTestConfigRevision()
                    val configFingerprint = JmRouteManager.currentSpeedTestConfigFingerprint()
                    val imageConfigFingerprint = JmRouteManager.currentImageConfigFingerprint()
                    val imageNetworkPolicy = JmRouteManager.currentImageNetworkPolicy()
                    val runContext = currentCoroutineContext()
                    val identity = JmSpeedTestRunIdentity(
                        UUID.randomUUID().toString(),
                        platformRevision,
                        configRevision,
                        configVersion,
                        configFingerprint,
                        imageConfigFingerprint,
                        imageNetworkPolicy,
                        PlatformSpeedTestCoordinator.currentNetworkIdentity(),
                    )
                    val linesToTest = JmRouteManager.lineHosts.toList()
                    val imagesToTest = JmRouteManager.imageDomains.toList()
                    val prior = JmRouteManager.speedTestSnapshot.value
                    val storedLineHistory = JmSpeedTestHistoryStorage.loadLines()
                    val storedImageHistory = JmSpeedTestHistoryStorage.loadImages()
                    val requestedLineReconciliation = JmSpeedTestHistoryPolicy.shouldReconcile(
                        reconcileConfig,
                        storedLineHistory.remoteVersion,
                        configVersion,
                    )
                    val requestedImageReconciliation = JmSpeedTestHistoryPolicy.shouldReconcile(
                        reconcileConfig,
                        storedImageHistory.remoteVersion,
                        configVersion,
                    )
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
                    val reconcileLines = !quickLines && requestedLineReconciliation
                    val reconcileImages = !quickImages && requestedImageReconciliation
                    fun stillCurrent(): Boolean =
                        runContext.isActive && isCurrent() && StartupPlatformPolicy.isEnabled(ContentPlatform.JM) &&
                            PlatformSpeedTestCoordinator.currentNetworkIdentity() == identity.networkIdentity &&
                            StartupPlatformPolicy.state.value.revision == platformRevision &&
                            JmRouteManager.state.value.remoteVersion == configVersion &&
                            JmRouteManager.currentSpeedTestConfigRevision() == configRevision &&
                            JmRouteManager.currentSpeedTestConfigFingerprint() == configFingerprint &&
                            JmRouteManager.currentImageConfigFingerprint() == imageConfigFingerprint &&
                            JmRouteManager.currentImageNetworkPolicy() == imageNetworkPolicy &&
                            JmRouteManager.lineHosts == linesToTest && JmRouteManager.imageDomains == imagesToTest

                    val started = System.currentTimeMillis()
                    val startedNanos = System.nanoTime()
                    val progressGuard = Any()
                    var progress = JmSpeedTestProgress()
                    var lineCount = 0
                    var imageCount = 0
                    var snapshotSubmitted = false
                    var historiesSaved = false
                    fun diagnose(submission: JmSpeedTestSubmission) {
                        _lastSubmission.value = submission
                        JmRouteManager.record(
                            "speed_submission",
                            "JM",
                            "runId=${identity.runId}; state=${submission.state}; phase=${progress.phase}; " +
                                "snapshotSubmitted=${submission.snapshotSubmitted}; historiesSaved=${submission.historiesSaved}; " +
                                "complete=${submission.state == JmSpeedTestSubmission.State.COMPLETE}; reason=${submission.state.reason}; " +
                                "lineQuick=$quickLines; imageQuick=$quickImages; lineCount=$lineCount; imageCount=$imageCount",
                        )
                    }
                    val pendingLogs = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, String>>()
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
                    val timings = SpeedTestTimingLog.start(SpeedTestTimingLog.Scope.JM)
                    try {
                        val linePreparationTiming = timings.start(SpeedTestTimings.Phase.LINE_PREPARATION)
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
                        linePreparationTiming.close()
                        val lineTiming = timings.start(SpeedTestTimings.Phase.LINES)
                        val activeLineIdentities = resolvedLines.associate { target ->
                            target.endpoint to lineIdentity(target.endpoint, target.url)
                        }.values.toSet()
                        val lineTargetsToProbe = if (reconcileLines) {
                            val priorIdentities = storedLineHistory.measurements.mapTo(hashSetOf()) { it.identity }
                            resolvedLines.filter { target -> lineIdentity(target.endpoint, target.url) !in priorIdentities }
                        } else {
                            resolvedLines
                        }
                        phase(JmSpeedTestProgress.Phase.TESTING_LINES, lineTargetsToProbe.size)
                        val lineResults = runner.run(
                            lineTargetsToProbe,
                            probe = { target -> measureAtIp(lineClient, target.endpoint, target.url, false, ::stillCurrent) },
                            onStarted = { endpointStarted(it.endpoint) },
                            onResult = { target, result ->
                                if (stillCurrent()) {
                                    pendingLogs.add(
                                        Triple(
                                            "speed_line_ip",
                                            "${target.endpoint.domain} [${target.endpoint.ip}]",
                                            "JM API ${if (result.delayMs < Long.MAX_VALUE) "${result.delayMs}ms" else "不可达"}",
                                        ),
                                    )
                                    endpointDone(target.endpoint, result)
                                }
                            },
                        )
                        lineCount = lineResults.size
                        lineTiming.close()
                        val measuredLineHistory = lineResults.map { (target, result) ->
                            JmSpeedTestHistoryPolicy.Measurement(
                                identity = lineIdentity(target.endpoint, target.url),
                                delayMs = result.delayMs,
                                measuredAtEpochMs = System.currentTimeMillis(),
                            )
                        }
                        val lineHistoryAfterRun = when {
                            reconcileLines -> JmSpeedTestHistoryPolicy.reconcile(
                                storedLineHistory,
                                configVersion,
                                activeLineIdentities,
                                measuredLineHistory,
                            )
                            quickLines -> JmSpeedTestHistoryPolicy.mergeQuick(storedLineHistory, configVersion, measuredLineHistory)
                            else -> JmSpeedTestHistoryPolicy.freshOnly(configVersion, measuredLineHistory)
                        }
                        val freshLineIpDelays = lineResults.associate { it.first.endpoint to it.second.delayMs }
                        val effectiveLineIpDelays = if (reconcileLines) {
                            lineHistoryAfterRun.measurements.associate { it.identity.endpoint to it.delayMs }
                        } else {
                            freshLineIpDelays
                        }
                        val testedLineEndpoints = freshLineIpDelays.keys
                        val untestedLineIpDelays = if (quickLines) priorLineDelays.filterKeys { it !in testedLineEndpoints } else emptyMap()
                        val lineIpDelays = JmSpeedTestQuickProbePolicy.rankPriorDelays(effectiveLineIpDelays, emptyMap())
                        val lineIpTestedAt = if (reconcileLines) {
                            lineHistoryAfterRun.measurements.associate { it.identity.endpoint to it.measuredAtEpochMs }
                        } else {
                            lineIpDelays.keys.associateWith { System.currentTimeMillis() }
                        }
                        val untestedLineIpTestedAt = JmSpeedTestQuickProbePolicy.mergeTestedAt(
                            if (quickLines) prior.lineIpTestedAtEpochMs + prior.untestedLineIpTestedAtEpochMs else emptyMap(),
                            emptyMap(),
                            testedLineEndpoints,
                            lineIpDelays.keys,
                        ).filterKeys { it in untestedLineIpDelays }
                        val lineDelays = linesToTest.associate { url ->
                            val host = URI(url).host.lowercase(Locale.ROOT)
                            host to (effectiveLineIpDelays.filterKeys { it.domain.equals(host, true) }.values.minOrNull() ?: Long.MAX_VALUE)
                        }

                        val imagePreparationTiming = timings.start(SpeedTestTimings.Phase.IMAGE_PREPARATION)
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
                        imagePreparationTiming.close()
                        val activeImageIdentities = imageEndpoints.mapTo(linkedSetOf(), ::imageIdentity)
                        val imageTargetsToProbe = if (reconcileImages) {
                            val priorIdentities = storedImageHistory.measurements.mapTo(hashSetOf()) { it.identity }
                            imageEndpoints.filter { endpoint ->
                                imageIdentity(endpoint) !in priorIdentities
                            }
                        } else {
                            imageEndpoints
                        }
                        val imageTiming = timings.start(SpeedTestTimings.Phase.IMAGES)
                        phase(JmSpeedTestProgress.Phase.TESTING_IMAGES, imageTargetsToProbe.size)
                        val imageResults = runner.run(
                            imageTargetsToProbe,
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
                                    pendingLogs.add(
                                        Triple(
                                            "speed_image_ip",
                                            "${endpoint.domain} [${endpoint.ip}]",
                                            "JM 图源 ${result.verdict}; ${result.elapsedMs}ms",
                                        ),
                                    )
                                    endpointDone(endpoint, result)
                                }
                            },
                        )
                        imageTiming.close()
                        val freshImageIpDelays = imageResults.associate { it.first to it.second.delayMs }
                        val freshImageIpVerdicts = imageResults.associate { it.first to (it.second.verdict ?: ImgVerdict.UNREACHABLE) }
                        val measuredImageHistory = imageResults.map { (endpoint, result) ->
                            JmSpeedTestHistoryPolicy.Measurement(
                                identity = imageIdentity(endpoint),
                                delayMs = result.delayMs,
                                verdict = result.verdict ?: ImgVerdict.UNREACHABLE,
                                measuredAtEpochMs = System.currentTimeMillis(),
                            )
                        }
                        val imageHistoryAfterRun = when {
                            reconcileImages -> JmSpeedTestHistoryPolicy.reconcile(
                                storedImageHistory,
                                configVersion,
                                activeImageIdentities,
                                measuredImageHistory,
                            )
                            quickImages -> JmSpeedTestHistoryPolicy.mergeQuick(storedImageHistory, configVersion, measuredImageHistory)
                            else -> JmSpeedTestHistoryPolicy.freshOnly(configVersion, measuredImageHistory)
                        }
                        val effectiveImageIpDelays = if (reconcileImages) {
                            imageHistoryAfterRun.measurements.associate { it.identity.endpoint to it.delayMs }
                        } else {
                            freshImageIpDelays
                        }
                        val effectiveImageIpVerdicts = if (reconcileImages) {
                            imageHistoryAfterRun.measurements.associate { it.identity.endpoint to (it.verdict ?: ImgVerdict.UNREACHABLE) }
                        } else {
                            freshImageIpVerdicts
                        }
                        val testedImageEndpoints = freshImageIpDelays.keys
                        val untestedImageIpDelays = if (quickImages) priorImageDelays.filterKeys { it !in testedImageEndpoints } else emptyMap()
                        val untestedImageIpVerdicts = priorImageVerdicts.filterKeys { it in untestedImageIpDelays }
                        val imageIpDelays = JmSpeedTestQuickProbePolicy.rankPriorDelays(effectiveImageIpDelays, emptyMap())
                        val imageIpVerdicts = effectiveImageIpVerdicts
                        val imageIpTestedAt = if (reconcileImages) {
                            imageHistoryAfterRun.measurements.associate { it.identity.endpoint to it.measuredAtEpochMs }
                        } else {
                            JmSpeedTestQuickProbePolicy.mergeTestedAt(
                                emptyMap(),
                                imageIpDelays.keys.associateWith { System.currentTimeMillis() },
                                testedImageEndpoints,
                                imageIpDelays.keys,
                            )
                        }
                        val untestedImageIpTestedAt = JmSpeedTestQuickProbePolicy.mergeTestedAt(
                            if (quickImages) prior.imageIpTestedAtEpochMs + prior.untestedImageIpTestedAtEpochMs else emptyMap(),
                            emptyMap(),
                            testedImageEndpoints,
                        ).filterKeys { it in untestedImageIpDelays }
                        val imageDelays = imagesToTest.associate { domain ->
                            domain.lowercase(Locale.ROOT) to (effectiveImageIpDelays.filterKeys { it.domain.equals(domain, true) }.values.minOrNull() ?: Long.MAX_VALUE)
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
                            remoteVersion = configVersion,
                            configFingerprint = configFingerprint, imageConfigFingerprint = imageConfigFingerprint,
                            imageNetworkPolicy = imageNetworkPolicy, imageResultsReused = false,
                            runId = identity.runId,
                        )
                        imageCount = testedImageEndpoints.size
                        val beforeApplyAccepted = timings.start(SpeedTestTimings.Phase.CONFIG_WAIT).use { beforeApply() }
                        runContext.ensureActive()
                        val submission = JmSpeedTestSubmission.commit(
                            identity = identity,
                            beforeApplyAccepted = beforeApplyAccepted,
                            isCurrent = ::stillCurrent,
                            submitSnapshot = {
                                pendingLogs.forEach { (kind, host, message) ->
                                    if (stillCurrent()) JmRouteManager.record(kind, host, message)
                                }
                                (stillCurrent() && JmRouteManager.recordSpeedTest(snapshot, platformRevision)).also { snapshotSubmitted = it }
                            },
                            saveHistories = {
                                JmRouteManager.persistSpeedTestHistories(
                                    lineHistoryAfterRun,
                                    imageHistoryAfterRun,
                                    platformRevision,
                                    configRevision,
                                    configFingerprint,
                                    ::stillCurrent,
                                ).also { historiesSaved = it }
                            },
                            publishedRunId = { JmRouteManager.speedTestSnapshot.value.runId },
                        )
                        runContext.ensureActive()
                        diagnose(submission)
                        publish {
                            it.copy(phase = if (submission.state == JmSpeedTestSubmission.State.COMPLETE) JmSpeedTestProgress.Phase.COMPLETE else JmSpeedTestProgress.Phase.FAILED)
                        }
                        RunResult(snapshot, submission)
                    } catch (error: CancellationException) {
                        diagnose(JmSpeedTestSubmission(identity, JmSpeedTestSubmission.State.CANCELLED, snapshotSubmitted, historiesSaved))
                        throw error
                    } catch (error: Exception) {
                        diagnose(JmSpeedTestSubmission(identity, JmSpeedTestSubmission.State.FAILED, snapshotSubmitted, historiesSaved))
                        publish { it.copy(phase = JmSpeedTestProgress.Phase.FAILED) }
                        throw error
                    } finally {
                        ticker.cancel()
                        probeClient.dispatcher.cancelAll()
                        probeClient.connectionPool.evictAll()
                        probeClient.dispatcher.executorService.shutdown()
                        timings.finish()
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

    private fun lineIdentity(endpoint: DomainIp, request: String) = JmSpeedTestHistoryPolicy.Identity(
        phase = JmSpeedTestHistoryPolicy.API_PHASE,
        request = request,
        domain = endpoint.domain.lowercase(Locale.ROOT),
        ip = endpoint.ip.trim().lowercase(Locale.ROOT),
        routeCondition = routeCondition(endpoint, image = false),
        probeCondition = "JM-API-interceptor;GET-json;${JmSpeedProbeProtocol.LINE_PATH};successful-http;valid-decrypted-json;redirects-disabled",
    )

    private fun imageIdentity(endpoint: DomainIp) = JmSpeedTestHistoryPolicy.Identity(
        phase = JmSpeedTestHistoryPolicy.IMAGE_PHASE,
        request = "https://${endpoint.domain.lowercase(Locale.ROOT)}${JmSpeedProbeProtocol.IMAGE_PATH}",
        domain = endpoint.domain.lowercase(Locale.ROOT),
        ip = endpoint.ip.trim().lowercase(Locale.ROOT),
        routeCondition = routeCondition(endpoint, image = true),
        probeCondition = "GET-image;range=bytes-0-${JmSpeedProbeProtocol.IMAGE_PROBE_BYTES - 1};status=200-or-206;valid-image-response;redirects-disabled",
    )

    private fun routeCondition(endpoint: DomainIp, image: Boolean): String {
        val configuredIps = if (image) {
            JmRouteManager.resolveImageIp(endpoint.domain)
        } else {
            JmRouteManager.resolveLineIp(endpoint.domain)
        }
        val usesConfiguredIps = configuredIps.any { isIpLiteral(it.trim()) }
        return "pinned-endpoint;ip-source=${if (usesConfiguredIps) "configured-rules" else "probe-dns"};" +
            "sni-host=${endpoint.domain.lowercase(Locale.ROOT)};" +
            "sni-enabled=${endpoint.domain.lowercase(Locale.ROOT) in JmRouteManager.sniDomains};" +
            "follow-system-vpn=${DevConfig.isFollowSystemVpnEnabled()};system-vpn-active=${NetworkModule.isSystemVpnActive()}"
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

    /** A diagnostic-only JM client that resolves only one configured literal and never retries addresses. */
    private fun newDiagnosticProbeClientForIp(
        client: OkHttpClient,
        endpoint: DomainIp,
        image: Boolean,
    ): OkHttpClient {
        require(isIpLiteral(endpoint.ip)) { "JM diagnostic probes require a literal IP" }
        val configured = if (image) JmRouteManager.resolveImageIp(endpoint.domain) else JmRouteManager.resolveLineIp(endpoint.domain)
        require(configured.any { it.trim().equals(endpoint.ip, ignoreCase = true) }) {
            "JM diagnostic probes require an IP configured for this host and probe type"
        }
        return client.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(endpoint.domain, true)) {
                        throw java.net.UnknownHostException("JM diagnostic probe is pinned to one host")
                    }
                    return listOf(InetAddress.getByName(endpoint.ip))
                }
            })
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    /** Reuses JM's API/image validation while avoiding speed-test persistence and route selection. */
    internal suspend fun probeForDeprecatedScan(endpoint: DomainIp, image: Boolean, configuredLineUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isIpLiteral(endpoint.ip)) return@withContext false
            val configuredIps = if (image) JmRouteManager.resolveImageIp(endpoint.domain) else JmRouteManager.resolveLineIp(endpoint.domain)
            if (configuredIps.none { it.trim().equals(endpoint.ip, ignoreCase = true) }) return@withContext false
            if (!image && !runCatching { URI(configuredLineUrl).host.equals(endpoint.domain, true) }.getOrDefault(false)) {
                return@withContext false
            }
            val probeClient = JmNetworkModule.newProbeClient()
            val routed = newDiagnosticProbeClientForIp(probeClient, endpoint, image)
            val client = if (image) {
                routed
            } else {
                routed.newBuilder()
                    .addInterceptor(JmApiInterceptor(recordRouteHealth = false))
                    .build()
            }
            try {
                val url = if (image) {
                    "https://${endpoint.domain}${JmSpeedProbeProtocol.IMAGE_PATH}"
                } else {
                    "${configuredLineUrl.trimEnd('/')}${JmSpeedProbeProtocol.LINE_PATH}"
                }
                measure(client, url, image).delayMs < Long.MAX_VALUE
            } finally {
                client.dispatcher.cancelAll()
                routed.connectionPool.evictAll()
                probeClient.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }

    private fun isIpLiteral(value: String): Boolean = if (':' in value) {
        value.matches(Regex("[0-9a-fA-F:.]+")) &&
            runCatching { InetAddress.getByName(value) }.isSuccess
    } else {
        val parts = value.split('.')
        parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }
    }

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
            .apply { if (image) header("Range", "bytes=0-${JmSpeedProbeProtocol.IMAGE_PROBE_BYTES - 1}") }
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
                                source.request(JmSpeedProbeProtocol.IMAGE_PROBE_BYTES)
                                source.buffer.clone().readByteArray(minOf(source.buffer.size, JmSpeedProbeProtocol.IMAGE_PROBE_BYTES))
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
