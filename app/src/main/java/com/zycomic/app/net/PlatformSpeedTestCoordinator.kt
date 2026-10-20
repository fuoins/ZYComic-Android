package com.zycomic.app.net

import android.content.Context
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One orchestration entry point for startup, config changes, settings and network retests. */
object PlatformSpeedTestCoordinator {
    private val lock = Mutex()
    private val startupConfig = StartupSpeedTestGate()
    private val _reports = MutableStateFlow<List<PlatformSpeedTestReport>>(emptyList())
    val reports = _reports.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _progressText = MutableStateFlow<String?>(null)
    val progressText = _progressText.asStateFlow()
    private val _jmProgress = MutableStateFlow<JmSpeedTestProgress?>(null)
    val jmProgress = _jmProgress.asStateFlow()
    /** Structured Picacg stage and per-endpoint progress for startup and settings UI. */
    val picacgProgress = PicacgSpeedTester.progress
    private val _completions = MutableSharedFlow<PlatformSpeedTestReport>(extraBufferCapacity = 16)
    val completions = _completions.asSharedFlow()
    private var completedKey: SpeedTestRunKey? = null

    internal fun finishStartupConfigCheck(configurationUpdated: Boolean) {
        startupConfig.complete(configurationUpdated)
    }

    private fun currentKey() = SpeedTestRunKey(
        StartupPlatformPolicy.state.value.revision,
        RouteManager.configRevision,
        JmRouteManager.currentSpeedTestConfigRevision(),
        currentNetworkIdentity(),
        PicacgRouteManager.configRevision,
    )

    internal fun currentNetworkIdentity(): String {
        val manager = DevConfig.appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val active = manager?.activeNetwork
        val underlying = manager?.allNetworks.orEmpty().filter { network ->
            manager?.getNetworkCapabilities(network)?.let { caps ->
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }.map { it.toString() }.sorted()
        return "$active|$underlying|${NetworkModule.shouldUseDirectStrategy()}|${DevConfig.isFollowSystemVpnEnabled()}"
    }

    suspend fun run(
        forceFresh: Boolean = false,
        remoteConfigUpdated: Boolean = false,
        onlyPlatform: ContentPlatform? = null,
        notifyCompletion: Boolean = true,
        onProgress: ((Map<DomainIp, Long>, Map<DomainIp, Long>, Map<DomainIp, SpeedTester.ImgVerdict>) -> Unit)? = null,
        onStageProgress: ((SpeedTester.Progress) -> Unit)? = null,
    ): List<PlatformSpeedTestReport> = withContext(Dispatchers.IO) {
        // First install and explicit retests still wait. A cached automatic run may
        // measure speculatively, but each tester must cross beforeApply before selection.
        if (!DevConfig.hasUsableRouteConfig || forceFresh || onlyPlatform != null) {
            startupConfig.awaitConfigurationUpdated()
        }
        DevConfig.awaitUsableRouteConfig()
        lock.withLock {
            val initialKey = currentKey()
            if (SpeedTestRunPolicy.canSkip(
                    completedKey,
                    initialKey,
                    forceFresh,
                    onlyPlatform != null,
                )
            ) {
                return@withLock _reports.value
            }
            _running.value = true
            _progressText.value = null
            _jmProgress.value = null
            try {
                // A changed toggle/config starts a new generation; never publish the old generation.
                while (true) {
                    _progressText.value = null
                    _jmProgress.value = null
                    val policy = StartupPlatformPolicy.state.value
                    val key = currentKey()
                    // History eligibility is version-only inside each tester. Process-local
                    // revisions/network identity still guard publication and deduplicate runs.
                    val freshConfig = remoteConfigUpdated || (startupConfig.configurationUpdated == true && completedKey == null)
                    val reuseLines = SpeedTestRunPolicy.canFastTest(
                        policy.useLastLineResult,
                        forceFresh,
                        freshConfig,
                    )
                    val reuseImages = SpeedTestRunPolicy.canFastTest(
                        policy.useLastResult,
                        forceFresh,
                        freshConfig,
                    )
                    val version = DevConfig.remoteVersion()
                    fun generationIsCurrent() = key == currentKey() && version == DevConfig.remoteVersion()
                    suspend fun beforeApply(): Boolean {
                        startupConfig.awaitConfigurationUpdated()
                        return generationIsCurrent()
                    }
                    val result = coroutineScope {
                        listOf(ContentPlatform.MANWA, ContentPlatform.PICACG, ContentPlatform.JM).map { platform ->
                            async {
                                if (!generationIsCurrent()) return@async report(platform, "incomplete", "配置已变化，丢弃预热结果")
                                val enabled = StartupPlatformPolicy.isEnabled(platform)
                                var status = "skipped"
                                var reason = when (platform) {
                                    ContentPlatform.MANWA -> "漫蛙未开启"
                                    ContentPlatform.PICACG -> "哔咔未开启"
                                    ContentPlatform.JM -> "禁漫天堂未开启"
                                }
                                val requested = onlyPlatform == null || onlyPlatform == platform
                                if (enabled && !requested) reason = "本轮仅重新检测${platformName(onlyPlatform ?: platform)}"
                                if (enabled && requested) {
                                    _progressText.value = "正在检测${platformName(platform)}线路与图源…"
                                    try {
                                        reason = "线路${if (reuseLines) "优先前五快测" else "完整测速"}；图源${if (reuseImages) "优先前五快测" else "完整测速"}（历史仅核对网络配置版本）"
                                        if (platform == ContentPlatform.MANWA) {
                                            SpeedTester.runAndApply(
                                                onProgress = if (onProgress == null) {
                                                    null
                                                } else {
                                                    { lines, images, verdicts ->
                                                        if (startupConfig.configurationUpdated != null && generationIsCurrent()) {
                                                            onProgress.invoke(lines, images, verdicts)
                                                        }
                                                    }
                                                },
                                                reuseLines = reuseLines,
                                                allowImageReuse = reuseImages,
                                                isCurrent = ::generationIsCurrent,
                                                beforeApply = ::beforeApply,
                                                onStageProgress = { progress ->
                                                    if (generationIsCurrent()) {
                                                        _progressText.value = manwaProgressMessage(progress)
                                                        onStageProgress?.invoke(progress)
                                                    }
                                                },
                                            )
                                            status = if (SpeedTester.lastRunSkipped) "incomplete" else "tested"
                                            if (status == "tested") {
                                                _progressText.value = manwaProgressMessage(SpeedTester.progress.value)
                                                if (SpeedTester.progress.value.fullRetestRecommended) {
                                                    reason += "；前五快测全部失败，请在测速表中选择完整重新测速（本轮未扩测）"
                                                }
                                            } else if (SpeedTester.progress.value.phase != SpeedTester.Phase.SKIPPED) {
                                                val failed = SpeedTester.Progress(phase = SpeedTester.Phase.FAILED)
                                                _progressText.value = manwaProgressMessage(failed)
                                                onStageProgress?.invoke(failed)
                                            }
                                        } else if (platform == ContentPlatform.PICACG) {
                                            val snapshot = PicacgSpeedTester.run(
                                                isCurrent = ::generationIsCurrent,
                                                beforeApply = ::beforeApply,
                                                onProgress = { progress ->
                                                    if (generationIsCurrent()) _progressText.value = picacgProgressMessage(progress)
                                                },
                                            )
                                            status = if (snapshot.complete) "tested" else "incomplete"
                                            reason = if (snapshot.complete) {
                                                val apiAvailable = snapshot.apiProbes.count(PicacgSpeedTester.Probe::success)
                                                val imagesVerified = snapshot.imageProbes.count(PicacgSpeedTester.Probe::success)
                                                "API 根路径 HTTP 200–499 可用 $apiAvailable/${snapshot.apiProbes.size}；真实图片 GET 200/image 且解码成功 $imagesVerified/${snapshot.imageProbes.size}"
                                            } else {
                                                snapshot.imageBlockedReason ?: "线路或真实图源样本不足，图源状态未确认"
                                            }
                                            _progressText.value = if (snapshot.complete) {
                                                "哔咔线路与真实图源测速完成"
                                            } else {
                                                "哔咔 API 已检测；图源验证阻塞（无真实图片样本）"
                                            }
                                        } else {
                                            val snapshot = JmSpeedTester.run(reuseLines = reuseLines, reuseImages = reuseImages, isCurrent = ::generationIsCurrent, beforeApply = ::beforeApply) { progress ->
                                                if (generationIsCurrent()) {
                                                    _jmProgress.value = progress
                                                    _progressText.value = progress.message()
                                                }
                                            }
                                            status = if (snapshot.completedAtEpochMs != null && JmRouteManager.speedTestSnapshot.value == snapshot) "tested" else "incomplete"
                                            if (status == "incomplete") reason = "本轮结果未完整提交，保留当前选择"
                                            if (snapshot.lineQuickTestAllFailed || snapshot.imageQuickTestAllFailed) {
                                                reason += "；前五快测全部失败，请在测速表中选择完整重新测速（本轮未扩测）"
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        status = "incomplete"
                                        reason = "本轮检测失败，保留当前选择"
                                        if (platform == ContentPlatform.MANWA) {
                                            val failed = SpeedTester.Progress(phase = SpeedTester.Phase.FAILED)
                                            _progressText.value = manwaProgressMessage(failed)
                                            onStageProgress?.invoke(failed)
                                        }
                                    }
                                }
                                report(platform, status, reason)
                            }
                        }.awaitAll()
                    }
                    // Even all-disabled/failed warm-ups must wait for authoritative config.
                    startupConfig.awaitConfigurationUpdated()
                    if (!generationIsCurrent()) continue
                    _reports.value = result
                    if (onlyPlatform == null) completedKey = key.takeIf { result.none { it.status == "incomplete" } }
                    persist(result)
                    if (notifyCompletion) result.filter { it.status != "skipped" }.forEach { _completions.emit(it) }
                    return@withLock result
                }
                @Suppress("UNREACHABLE_CODE")
                emptyList()
            } finally {
                _running.value = false
                _progressText.value = null
                _jmProgress.value = null
            }
        }
    }

    internal fun manwaProgressMessage(progress: SpeedTester.Progress): String = when (progress.phase) {
        SpeedTester.Phase.PREPARING_LINES -> "漫蛙 · 正在解析线路域名和 IP…"
        SpeedTester.Phase.TESTING_LINES -> SpeedTestUiTextPolicy.platformEndpointProgress(
            platform = "漫蛙",
            endpointKind = "线路",
            completed = progress.completed,
            total = progress.total,
            successful = progress.successful,
            failed = progress.failed,
        )
        SpeedTester.Phase.APPLYING_LINE -> "漫蛙 · 线路检测完成（可用 ${progress.successful}，失败 ${progress.failed}），正在更新测速排名"
        SpeedTester.Phase.FETCHING_SAMPLE -> "漫蛙 · 正在获取图源验证样本…"
        SpeedTester.Phase.PREPARING_IMAGES -> "漫蛙 · 正在解析图源域名和 IP…"
        SpeedTester.Phase.TESTING_IMAGES -> SpeedTestUiTextPolicy.platformEndpointProgress(
            platform = "漫蛙",
            endpointKind = "图源",
            completed = progress.completed,
            total = progress.total,
            successful = progress.successful,
            failed = progress.failed,
            noImage = progress.noImage,
        )
        SpeedTester.Phase.APPLYING_RESULTS -> "漫蛙 · 端点检测完成，正在更新排名并应用结果…"
        SpeedTester.Phase.COMPLETE -> if (progress.fullRetestRecommended) {
            "漫蛙前五快测全部失败，本轮不再扩测；请在测速表中选择完整重新测速"
        } else {
            "漫蛙测速完成：图源 ${progress.completed}/${progress.total}，可用 ${progress.successful}、失败 ${progress.failed}、无图 ${progress.noImage}；本次新测 ${progress.freshImages}、保留历史 ${progress.reusedImages}（本轮未测）"
        }
        SpeedTester.Phase.FAILED -> "漫蛙测速中断，继续使用当前线路和图源"
        SpeedTester.Phase.SKIPPED -> "漫蛙已有测速任务正在运行，本轮未重复检测"
    }

    internal fun picacgProgressMessage(progress: PicacgSpeedTester.Progress): String = when (progress.stage) {
        PicacgSpeedTester.Stage.PREPARING_API -> "哔咔 · 正在解析 API 线路与 IP…"
        PicacgSpeedTester.Stage.TESTING_API -> SpeedTestUiTextPolicy.platformEndpointProgress(
            platform = "哔咔",
            endpointKind = "API 线路",
            completed = progress.completed,
            total = progress.total,
            successful = progress.successful,
            failed = progress.failed,
        )
        PicacgSpeedTester.Stage.PREPARING_IMAGES -> "哔咔 · 正在准备真实图源样本与图源 IP…"
        PicacgSpeedTester.Stage.TESTING_IMAGES -> SpeedTestUiTextPolicy.platformEndpointProgress(
            platform = "哔咔",
            endpointKind = "图源",
            completed = progress.completed,
            total = progress.total,
            successful = progress.successful,
            failed = progress.failed,
        )
        PicacgSpeedTester.Stage.IMAGE_BLOCKED -> "哔咔 · 图源验证受阻：无 API 返回的真实图片样本或可用图源 IP"
        PicacgSpeedTester.Stage.COMPLETE -> "哔咔测速完成：API ${progress.successful}/${progress.total} 可用；图源按真实图片样本验证"
    }

    private fun report(platform: ContentPlatform, status: String, reason: String): PlatformSpeedTestReport = when (platform) {
        ContentPlatform.MANWA -> PlatformSpeedTestReport(
            platform = "漫蛙",
            status = status,
            reason = reason,
            selectedLine = RouteManager.baseUrl + (RouteManager.selectedLineIp?.let { " [$it]" } ?: ""),
            selectedImage = RouteManager.imgRewriteTarget?.let { domain -> domain + (RouteManager.selectedImgIp?.let { " [$it]" } ?: "") }
                ?: "服务端原始图源（当前无域名改写）",
            manualLine = RouteManager.hasManualLineSelection,
            manualImage = RouteManager.pinnedImgDomain != null,
            lineCandidates = RouteManager.lineHosts.toList(),
            imageCandidates = RouteManager.imgDomains.toList(),
            lineDelays = if (status == "skipped") emptyMap() else RouteManager.lastLineIpDelays.mapKeys { "${it.key.domain} [${it.key.ip}]" },
            imageDelays = if (status == "skipped") emptyMap() else RouteManager.lastImgIpDelays.mapKeys { "${it.key.domain} [${it.key.ip}]" },
            remoteVersion = RouteManager.activeRemoteConfigVersion,
            lineStatus = if (status == "tested" && SpeedTester.progress.value.lineQuickTest) "quick" else status,
            imageStatus = if (status == "tested" && SpeedTester.progress.value.imageQuickTest) "quick" else status,
        )
        ContentPlatform.PICACG -> {
            val snapshot = PicacgSpeedTester.snapshot.value
            PlatformSpeedTestReport(
                platform = "哔咔",
                status = status,
                reason = reason,
                selectedLine = PicacgRouteManager.currentBaseUrl() + (PicacgRouteManager.selectedApiEndpoint?.let { " [${it.ip}]" } ?: ""),
                selectedImage = PicacgRouteManager.currentImageHost.orEmpty() + (PicacgRouteManager.selectedImageEndpoint?.let { " [${it.ip}]" } ?: ""),
                manualLine = PicacgRouteManager.isApiManuallyPinned,
                manualImage = PicacgRouteManager.isImageManuallyPinned,
                lineCandidates = PicacgRouteManager.apiUrls.toList(),
                imageCandidates = PicacgRouteManager.imageHosts.toList(),
                lineDelays = if (status == "skipped") emptyMap() else snapshot?.apiProbes.orEmpty().associate { "${it.endpoint.domain} [${it.endpoint.ip}]" to if (it.success) it.elapsedMs else Long.MAX_VALUE },
                imageDelays = if (status == "skipped") emptyMap() else snapshot?.imageProbes.orEmpty().associate { "${it.endpoint.domain} [${it.endpoint.ip}]" to if (it.success) it.elapsedMs else Long.MAX_VALUE },
                remoteVersion = PicacgRouteManager.remoteVersion,
                lineStatus = if (snapshot?.apiComplete == true) "tested" else "incomplete",
                imageStatus = if (snapshot?.imageComplete == true) "tested" else "incomplete",
            )
        }
        ContentPlatform.JM -> PlatformSpeedTestReport(
            platform = "禁漫天堂",
            status = status,
            reason = reason,
            selectedLine = JmRouteManager.baseUrl,
            selectedImage = JmRouteManager.activeImageDomain,
            manualLine = !JmRouteManager.isAutoSelectEnabled,
            manualImage = JmRouteManager.pinnedImageDomain != null,
            lineCandidates = JmRouteManager.lineHosts.toList(),
            imageCandidates = JmRouteManager.imageDomains.toList(),
            lineDelays = if (status == "skipped") emptyMap() else JmRouteManager.speedTestSnapshot.value.lineIpDelaysMs.mapKeys { "${it.key.domain} [${it.key.ip}]" },
            imageDelays = if (status == "skipped") emptyMap() else JmRouteManager.speedTestSnapshot.value.imageIpDelaysMs.mapKeys { "${it.key.domain} [${it.key.ip}]" },
            remoteVersion = JmRouteManager.state.value.remoteVersion,
            lineStatus = if (status == "tested" && JmRouteManager.speedTestSnapshot.value.lineQuickTest) "quick" else status,
            imageStatus = if (status == "tested" && JmRouteManager.speedTestSnapshot.value.imageQuickTest) "quick" else status,
        )
    }

    private fun platformName(platform: ContentPlatform): String = when (platform) {
        ContentPlatform.MANWA -> "漫蛙"
        ContentPlatform.PICACG -> "哔咔"
        ContentPlatform.JM -> "禁漫天堂"
    }

    fun reportsJson(reports: List<PlatformSpeedTestReport> = _reports.value): JSONArray = JSONArray().apply {
        reports.forEach { report ->
            put(
                JSONObject().apply {
                    put("platform", report.platform)
                    // Wall-clock spans overlap; TOTAL is measured, never a sum of phases.
                    val timingScope = when (report.platform) {
                        "漫蛙" -> SpeedTestTimingLog.Scope.MANWA
                        "哔咔" -> SpeedTestTimingLog.Scope.PICACG
                        else -> SpeedTestTimingLog.Scope.JM
                    }
                    put("phaseTimingsMs", SpeedTestTimingLog.json(timingScope))
                    put("startupTimingsMs", SpeedTestTimingLog.json(SpeedTestTimingLog.Scope.STARTUP))
                    put("configTimingsMs", SpeedTestTimingLog.json(SpeedTestTimingLog.Scope.CONFIG))
                    put("status", report.status)
                    put("reason", report.reason)
                    put("lineResultStatus", report.lineStatus)
                    put("imageResultStatus", report.imageStatus)
                    put("remoteConfigVersion", report.remoteVersion ?: JSONObject.NULL)
                    put("selectedLine", report.selectedLine)
                    put("selectedImage", report.selectedImage)
                    put("lineSelection", if (report.manualLine) "manual" else "automatic")
                    put("imageSelection", if (report.manualImage) "manual" else "automatic")
                    put("lineCandidates", JSONArray(report.lineCandidates))
                    put("imageCandidates", JSONArray(report.imageCandidates))
                    put("lineDelaysMs", delaysJson(report.lineDelays))
                    put("imageDelaysMs", delaysJson(report.imageDelays))
                    if (report.platform == "哔咔") {
                        PicacgSpeedTester.snapshot.value?.let { put("picacgProbeLog", JSONObject(PicacgSpeedTester.exportJson(it))) }
                    }
                },
            )
        }
    }

    private fun delaysJson(delays: Map<String, Long>) = JSONObject().apply {
        delays.forEach { (candidate, delay) -> put(candidate, if (delay == Long.MAX_VALUE) JSONObject.NULL else delay) }
    }

    private fun persist(reports: List<PlatformSpeedTestReport>) {
        val json = reportsJson(reports).toString()
        DevConfig.appContext?.getSharedPreferences("zycomic_platform_speed_test", Context.MODE_PRIVATE)?.edit()?.apply {
            putString("last_result", json)
            PicacgSpeedTester.snapshot.value?.let { putString("picacg_last_speed_test", PicacgSpeedTester.exportJson(it)) }
        }?.apply()
        reports.forEach { android.util.Log.i("PlatformSpeedTest", it.summary()) }
        android.util.Log.i("PlatformSpeedTest", json)
    }

    /** Export only fixed Picacg route/HTTP/image-decode diagnostics; never includes a sample URL or token. */
    fun picacgSpeedTestExport(): String? = PicacgSpeedTester.snapshot.value?.let(PicacgSpeedTester::exportJson)
}
