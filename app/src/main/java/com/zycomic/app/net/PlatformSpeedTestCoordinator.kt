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
    private val _reports = MutableStateFlow<List<PlatformSpeedTestReport>>(emptyList())
    val reports = _reports.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _progressText = MutableStateFlow<String?>(null)
    val progressText = _progressText.asStateFlow()
    private val _completions = MutableSharedFlow<PlatformSpeedTestReport>(extraBufferCapacity = 16)
    val completions = _completions.asSharedFlow()
    private data class RunKey(val policy: Long, val manwaConfig: Long, val jmConfig: Long)
    private var completedKey: RunKey? = null
    private fun currentKey() = RunKey(StartupPlatformPolicy.state.value.revision, RouteManager.configRevision, JmRouteManager.currentSpeedTestConfigRevision())

    suspend fun run(
        forceFresh: Boolean = false,
        onlyPlatform: ContentPlatform? = null,
        notifyCompletion: Boolean = true,
        onProgress: ((Map<DomainIp, Long>, Map<DomainIp, Long>, Map<DomainIp, SpeedTester.ImgVerdict>) -> Unit)? = null,
        onStageProgress: ((SpeedTester.Progress) -> Unit)? = null,
    ): List<PlatformSpeedTestReport> = withContext(Dispatchers.IO) {
        DevConfig.awaitUsableRouteConfig()
        lock.withLock {
            val initialKey = currentKey()
            if (!forceFresh && onlyPlatform == null && completedKey == initialKey) return@withLock _reports.value
            _running.value = true
            try {
                // A changed toggle/config starts a new generation; never publish the old generation.
                while (true) {
                    val policy = StartupPlatformPolicy.state.value
                    val key = RunKey(policy.revision, RouteManager.configRevision, JmRouteManager.currentSpeedTestConfigRevision())
                    val result = mutableListOf<PlatformSpeedTestReport>()
                    for (platform in listOf(ContentPlatform.MANWA, ContentPlatform.JM)) {
                        if (key != currentKey()) break
                        val enabled = StartupPlatformPolicy.isEnabled(platform)
                        var status = "skipped"
                        var reason = if (platform == ContentPlatform.MANWA) "不用漫蛙已开启" else "不用禁漫天堂已开启"
                        val requested = onlyPlatform == null || onlyPlatform == platform
                        if (enabled && !requested) reason = "本轮仅重新检测${if (onlyPlatform == ContentPlatform.JM) "禁漫天堂" else "漫蛙"}"
                        if (enabled && requested) {
                            _progressText.value = if (platform == ContentPlatform.MANWA) "正在检测漫蛙线路与图源…" else "正在检测禁漫天堂线路与图源…"
                            try {
                                val reused = !forceFresh && policy.useLastResult && when (platform) {
                                    ContentPlatform.MANWA -> SpeedTester.reuseValidResults()
                                    ContentPlatform.JM -> JmSpeedTester.reuseValidResults()
                                }
                                if (reused) {
                                    status = "reused"
                                    reason = "配置、候选与网络策略匹配"
                                } else {
                                    reason = if (forceFresh || !policy.useLastResult) "已请求重新测速" else "缺少匹配当前配置/候选/网络策略的有效旧结果"
                                    if (platform == ContentPlatform.MANWA) {
                                        SpeedTester.runAndApply(onProgress, onStageProgress)
                                        status = if (SpeedTester.lastRunSkipped) "incomplete" else "tested"
                                    } else {
                                        val snapshot = JmSpeedTester.run()
                                        status = if (snapshot.completedAtEpochMs != null && JmRouteManager.speedTestSnapshot.value == snapshot) "tested" else "incomplete"
                                        if (status == "incomplete") reason = "本轮结果未完整提交，保留当前选择"
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                status = "incomplete"
                                reason = "本轮检测失败，保留当前选择"
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
            }
        }
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
            lineDelays = if (status == "skipped") emptyMap() else JmRouteManager.speedTestSnapshot.value.lineDelaysMs,
            imageDelays = if (status == "skipped") emptyMap() else JmRouteManager.speedTestSnapshot.value.imageDelaysMs,
            remoteVersion = JmRouteManager.state.value.remoteVersion,
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
