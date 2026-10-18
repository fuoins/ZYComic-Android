package com.zycomic.app.net

import android.content.Context
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        val startupConfigUpdated = startupConfig.awaitConfigurationUpdated()
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
                    // Config-triggered and startup callers share one final generation. Even if
                    // the ordinary startup caller wins the lock, updated config gets fresh images.
                    val freshConfig = remoteConfigUpdated || (startupConfigUpdated && completedKey == null) ||
                        initialKey.manwaConfigRevision != key.manwaConfigRevision ||
                        initialKey.jmConfigRevision != key.jmConfigRevision ||
                        completedKey?.let {
                            it.manwaConfigRevision != key.manwaConfigRevision || it.jmConfigRevision != key.jmConfigRevision
                        } == true
                    val reuseImages = SpeedTestRunPolicy.canReuseImages(
                        policy.useLastResult,
                        forceFresh,
                        initialKey,
                        key,
                        completedKey,
                        freshConfig,
                    )
                    val result = mutableListOf<PlatformSpeedTestReport>()
                    for (platform in listOf(ContentPlatform.MANWA, ContentPlatform.JM)) {
                        if (key != currentKey()) break
                        val enabled = StartupPlatformPolicy.isEnabled(platform)
                        var status = "skipped"
                        var reason = if (platform == ContentPlatform.MANWA) "漫蛙未开启" else "禁漫天堂未开启"
                        val requested = onlyPlatform == null || onlyPlatform == platform
                        if (enabled && !requested) reason = "本轮仅重新检测${if (onlyPlatform == ContentPlatform.JM) "禁漫天堂" else "漫蛙"}"
                        if (enabled && requested) {
                            _jmProgress.value = null
                            _progressText.value = null
                            _progressText.value = if (platform == ContentPlatform.MANWA) "正在检测漫蛙线路与图源…" else "正在检测禁漫天堂线路与图源…"
                            try {
                                reason = if (reuseImages) "线路本次新测；仅复用匹配的图源结果" else "线路与图源本次重新测速"
                                if (platform == ContentPlatform.MANWA) {
                                    if (reuseImages) SpeedTester.requestPreviousImageResults()
                                    SpeedTester.runAndApply(
                                        onProgress = onProgress,
                                        allowImageReuse = !freshConfig,
                                        onStageProgress = { progress ->
                                            if (key == currentKey()) {
                                                _progressText.value = manwaProgressMessage(progress)
                                                onStageProgress?.invoke(progress)
                                            }
                                        },
                                    )
                                    status = if (SpeedTester.lastRunSkipped) "incomplete" else "tested"
                                    if (status == "tested") {
                                        _progressText.value = manwaProgressMessage(SpeedTester.progress.value)
                                    } else if (SpeedTester.progress.value.phase != SpeedTester.Phase.SKIPPED) {
                                        val failed = SpeedTester.Progress(phase = SpeedTester.Phase.FAILED)
                                        _progressText.value = manwaProgressMessage(failed)
                                        onStageProgress?.invoke(failed)
                                    }
                                } else {
                                    val snapshot = JmSpeedTester.run(reuseImages = reuseImages, isCurrent = { key == currentKey() }) { progress ->
                                        if (key == currentKey()) {
                                            _jmProgress.value = progress
                                            _progressText.value = progress.message()
                                        }
                                    }
                                    status = if (snapshot.completedAtEpochMs != null && JmRouteManager.speedTestSnapshot.value == snapshot) "tested" else "incomplete"
                                    if (status == "incomplete") reason = "本轮结果未完整提交，保留当前选择"
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
                        result += report(platform, status, reason)
                    }
                    if (key != currentKey()) continue
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
        SpeedTester.Phase.COMPLETE -> "漫蛙测速完成：图源 ${progress.completed}/${progress.total}，可用 ${progress.successful}、失败 ${progress.failed}、无图 ${progress.noImage}；本次新测 ${progress.freshImages}、复用 ${progress.reusedImages}"
        SpeedTester.Phase.FAILED -> "漫蛙测速中断，继续使用当前线路和图源"
        SpeedTester.Phase.SKIPPED -> "漫蛙已有测速任务正在运行，本轮未重复检测"
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
            imageStatus = if (status == "tested" && SpeedTester.progress.value.reusedImages > 0) "mixed" else status,
        )
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
            imageStatus = if (status == "tested" && JmRouteManager.speedTestSnapshot.value.imageResultsReused) "reused" else status,
        )
    }

    fun reportsJson(reports: List<PlatformSpeedTestReport> = _reports.value): JSONArray = JSONArray().apply {
        reports.forEach { report ->
            put(
                JSONObject().apply {
                    put("platform", report.platform)
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
                },
            )
        }
    }

    private fun delaysJson(delays: Map<String, Long>) = JSONObject().apply {
        delays.forEach { (candidate, delay) -> put(candidate, if (delay == Long.MAX_VALUE) JSONObject.NULL else delay) }
    }

    private fun persist(reports: List<PlatformSpeedTestReport>) {
        val json = reportsJson(reports).toString()
        DevConfig.appContext?.getSharedPreferences("zycomic_platform_speed_test", Context.MODE_PRIVATE)?.edit()
            ?.putString("last_result", json)?.apply()
        reports.forEach { android.util.Log.i("PlatformSpeedTest", it.summary()) }
        android.util.Log.i("PlatformSpeedTest", json)
    }
}
