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
import kotlinx.coroutines.flow.update
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
    private val _startupSummary = MutableStateFlow<String?>(null)
    val startupSummary = _startupSummary.asStateFlow()
    private val _platformStartupStatuses = MutableStateFlow(
        ContentPlatform.entries.associateWith { PlatformStartupStatus("等待测速") },
    )
    val platformStartupStatuses = _platformStartupStatuses.asStateFlow()
    private val _startupCheckStatuses = MutableStateFlow(initialStartupCheckStatuses())
    val startupCheckStatuses = _startupCheckStatuses.asStateFlow()
    private val _jmProgress = MutableStateFlow<JmSpeedTestProgress?>(null)
    val jmProgress = _jmProgress.asStateFlow()
    /** Structured Picacg stage and per-endpoint progress for startup and settings UI. */
    val picacgProgress = PicacgSpeedTester.progress
    private val _completions = MutableSharedFlow<PlatformSpeedTestReport>(extraBufferCapacity = 16)
    val completions = _completions.asSharedFlow()
    private var completedKey: SpeedTestRunKey? = null
    private var checkedConfigurationVersion: Int? = null
    private val completedPhases = CompletedSpeedTestPhases()
    private val completedReports = mutableMapOf<ContentPlatform, Pair<CompletedSpeedTestPhases.Key, PlatformSpeedTestReport>>()
    private val fullTestRequest = FullSpeedTestRequest<List<PlatformSpeedTestReport>>()

    /** Splash requests share one full test even while waiting behind the automatic startup run. */
    suspend fun retestAllEnabled(): List<PlatformSpeedTestReport> = fullTestRequest.run {
        run(forceFresh = true)
    }

    internal fun finishStartupConfigCheck(configurationUpdated: Boolean) {
        startupConfig.complete(configurationUpdated)
    }

    private fun currentKey() = SpeedTestRunKey(
        StartupPlatformPolicy.state.value.revision,
        RouteManager.configRevision,
        JmRouteManager.currentSpeedTestConfigRevision(),
        currentNetworkIdentity(),
        PicacgRouteManager.configRevision,
        DevConfig.remoteVersion(),
    )

    private fun phaseKey(key: SpeedTestRunKey, platform: ContentPlatform) = CompletedSpeedTestPhases.Key(
        remoteVersion = key.remoteVersion,
        routingRevision = when (platform) {
            ContentPlatform.MANWA -> key.manwaConfigRevision
            ContentPlatform.PICACG -> key.picacgConfigRevision
            ContentPlatform.JM -> key.jmConfigRevision
        },
        platformRevision = key.platformRevision,
        networkIdentity = key.networkIdentity,
        platform = platform.name,
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
            _running.value = true
            _progressText.value = null
            _jmProgress.value = null
            _startupSummary.value = "三平台测速进行中；各平台进度独立显示"
            var runCompleted = false
            try {
                // A changed toggle/config starts a new generation; never publish the old generation.
                while (true) {
                    _progressText.value = null
                    _jmProgress.value = null
                    val policy = StartupPlatformPolicy.state.value
                    val key = currentKey()
                    _platformStartupStatuses.value = ContentPlatform.entries.associateWith { platform ->
                        when {
                            !StartupPlatformPolicy.isEnabled(policy, platform) -> PlatformStartupStatus("未启用，已跳过")
                            onlyPlatform != null && onlyPlatform != platform -> PlatformStartupStatus("本轮未复测")
                            else -> PlatformStartupStatus("等待测速…", running = true)
                        }
                    }
                    _startupCheckStatuses.value = initialStartupCheckStatuses().map { check ->
                        val platform = check.phase.platform()
                        when {
                            !StartupPlatformPolicy.isEnabled(policy, platform) -> check.copy(result = StartupCheckResult.DISABLED)
                            onlyPlatform != null && onlyPlatform != platform -> check.copy(result = StartupCheckResult.NOT_RUN)
                            else -> check
                        }
                    }
                    // Equal versions retain the existing version-only fast test. Each tester
                    // reconciles endpoint identities only against a strictly older history.
                    // Process-local revisions/network identity still guard publication.
                    val freshConfig = remoteConfigUpdated ||
                        (startupConfig.configurationUpdated == true && checkedConfigurationVersion != key.remoteVersion)
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
                    val measuredPlatforms = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
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
                                var picacgSnapshot: PicacgSpeedTester.Snapshot? = null
                                if (enabled && !requested) reason = "本轮仅重新检测${platformName(onlyPlatform ?: platform)}"
                                if (enabled && requested) {
                                    val phaseKey = phaseKey(key, platform)
                                    if (forceFresh || onlyPlatform != null) completedPhases.invalidate(platform.name)
                                    val linesDone = completedPhases.canReuse(phaseKey, CompletedSpeedTestPhases.Phase.LINES, forceFresh, onlyPlatform != null)
                                    val imagesDone = completedPhases.canReuse(phaseKey, CompletedSpeedTestPhases.Phase.IMAGES, forceFresh, onlyPlatform != null)
                                    // Incomplete Picacg images must not invalidate other platforms or its API results.
                                    // Image ranking uses a fixed auth-free tile, so there is no longer a sample gate.
                                    if (linesDone && imagesDone) {
                                        val cached = completedReports[platform]?.takeIf { it.first == phaseKey }?.second
                                        if (cached != null) {
                                            publishPlatformStatus(platform, platformCompletionStatus(cached), running = false)
                                            return@async cached
                                        }
                                    }
                                    measuredPlatforms.add(platformName(platform))
                                    _progressText.value = "正在检测${platformName(platform)}线路与图源…"
                                    publishPlatformStatus(platform, "正在检测线路与图源…", running = true)
                                    try {
                                        reason = if (freshConfig && !forceFresh) {
                                            "新版本配置增量测速：保留身份未变的结果，新增端点测速，移除已删除端点"
                                        } else {
                                            "线路${if (reuseLines) "优先前五快测" else "完整测速"}；图源${if (reuseImages) "优先前五快测" else "完整测速"}（同版本历史仅核对网络配置版本）"
                                        }
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
                                                reconcileConfig = !forceFresh,
                                                isCurrent = ::generationIsCurrent,
                                                beforeApply = ::beforeApply,
                                                onStageProgress = { progress ->
                                                    if (generationIsCurrent()) {
                                                        _progressText.value = manwaProgressMessage(progress)
                                                        publishManwaProgress(progress)
                                                        publishManwaCheckProgress(progress)
                                                        onStageProgress?.invoke(progress)
                                                    }
                                                },
                                            )
                                            status = if (SpeedTester.lastRunSkipped) "incomplete" else "tested"
                                            if (status == "tested") {
                                                _progressText.value = manwaProgressMessage(SpeedTester.progress.value)
                                                publishManwaProgress(SpeedTester.progress.value)
                                                if (SpeedTester.progress.value.fullRetestRecommended) {
                                                    reason += "；前五快测全部失败，请在测速表中选择完整重新测速（本轮未扩测）"
                                                }
                                            } else if (SpeedTester.progress.value.phase != SpeedTester.Phase.SKIPPED) {
                                                val failed = SpeedTester.Progress(phase = SpeedTester.Phase.FAILED)
                                                _progressText.value = manwaProgressMessage(failed)
                                                publishManwaProgress(failed)
                                                onStageProgress?.invoke(failed)
                                            }
                                        } else if (platform == ContentPlatform.PICACG) {
                                            val snapshot = PicacgSpeedTester.run(
                                                reuseApi = linesDone,
                                                reuseImages = imagesDone,
                                                fastApi = reuseLines,
                                                fastImages = reuseImages,
                                                reconcileConfig = !forceFresh,
                                                forceFresh = forceFresh,
                                                isCurrent = ::generationIsCurrent,
                                                beforeApply = ::beforeApply,
                                                onProgress = { progress ->
                                                    if (generationIsCurrent()) {
                                                        _progressText.value = picacgProgressMessage(progress)
                                                        publishPicacgProgress(progress)
                                                        publishPicacgCheckProgress(progress)
                                                    }
                                                },
                                            )
                                            picacgSnapshot = snapshot.takeIf { PicacgSpeedTester.snapshot.value == it && generationIsCurrent() }
                                            picacgSnapshot?.let { current ->
                                                publishPicacgMeasuredChecks(current)
                                            }
                                            status = if (picacgSnapshot?.complete == true) "tested" else "incomplete"
                                            reason = if (snapshot.complete) {
                                                val apiAvailable = snapshot.apiProbes.count(PicacgSpeedTester.Probe::success)
                                                val imagesVerified = snapshot.imageProbes.count(PicacgSpeedTester.Probe::success)
                                                "API 根路径 HTTP 200–499（不含 3xx）可用 $apiAvailable/${snapshot.apiProbes.size}；封面瓦片 GET 200/image 且魔数有效 $imagesVerified/${snapshot.imageProbes.size}"
                                            } else {
                                                snapshot.imageBlockedReason ?: "线路或图源探测不足，图源状态未确认"
                                            }
                                            _progressText.value = if (snapshot.complete) {
                                                "哔咔线路与图源测速完成"
                                            } else {
                                                "哔咔 API 已检测；图源验证阻塞"
                                            }
                                            publishPlatformStatus(platform, _progressText.value.orEmpty(), running = false)
                                        } else {
                                            val run = JmSpeedTester.run(reuseLines = reuseLines, reuseImages = reuseImages, reconcileConfig = !forceFresh, isCurrent = ::generationIsCurrent, beforeApply = ::beforeApply) { progress ->
                                                if (generationIsCurrent()) {
                                                    _jmProgress.value = progress
                                                    _progressText.value = progress.message()
                                                    publishJmProgress(progress)
                                                    publishJmCheckProgress(progress)
                                                }
                                            }
                                            val snapshot = run.snapshot
                                            val submission = run.submission
                                            val complete = submission?.isComplete(
                                                JmRouteManager.speedTestSnapshot.value.runId,
                                                submission.identity.takeIf { generationIsCurrent() },
                                            ) == true
                                            status = if (complete) "tested" else "incomplete"
                                            if (!complete) reason = "本轮结果未完整提交：${submission?.state?.reason ?: "未启用或未返回提交结果"}"
                                            if (snapshot.lineQuickTestAllFailed || snapshot.imageQuickTestAllFailed) {
                                                reason += "；前五快测全部失败，请在测速表中选择完整重新测速（本轮未扩测）"
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        status = "incomplete"
                                        reason = "本轮检测失败，保留当前选择"
                                        failRunningChecks(platform, "本轮检测失败")
                                        if (platform == ContentPlatform.MANWA) {
                                            val failed = SpeedTester.Progress(phase = SpeedTester.Phase.FAILED)
                                            _progressText.value = manwaProgressMessage(failed)
                                            publishManwaProgress(failed)
                                            onStageProgress?.invoke(failed)
                                        }
                                    }
                                }
                                report(platform, status, reason, picacgSnapshot).also { platformReport ->
                                    publishPlatformStatus(platform, platformCompletionStatus(platformReport), running = false)
                                    blockUnresolvedChecks(platform)
                                }
                            }
                        }.awaitAll()
                    }
                    // Even all-disabled/failed warm-ups must wait for authoritative config.
                    startupConfig.awaitConfigurationUpdated()
                    if (!generationIsCurrent()) continue
                    _reports.value = result
                    _startupSummary.value = startupSummary(result)
                    listOf(ContentPlatform.MANWA, ContentPlatform.PICACG, ContentPlatform.JM).forEach { platform ->
                        val platformReport = result.first { it.platform == platformName(platform) }
                        if (platformReport.status != "skipped") {
                            val phaseKey = phaseKey(key, platform)
                            completedReports[platform] = phaseKey to platformReport
                            if (platformReport.lineStatus in setOf("tested", "quick")) {
                                completedPhases.record(phaseKey, CompletedSpeedTestPhases.Phase.LINES)
                            }
                            if (platformReport.imageStatus in setOf("tested", "quick")) {
                                completedPhases.record(phaseKey, CompletedSpeedTestPhases.Phase.IMAGES)
                            }
                        }
                    }
                    if (onlyPlatform == null) completedKey = key.takeIf { result.none { it.status == "incomplete" } }
                    checkedConfigurationVersion = version
                    persist(result)
                    if (notifyCompletion) result.filter { it.platform in measuredPlatforms && it.status != "skipped" }.forEach { _completions.emit(it) }
                    runCompleted = true
                    return@withLock result
                }
                @Suppress("UNREACHABLE_CODE")
                emptyList()
            } finally {
                if (!runCompleted) {
                    _startupSummary.value = "三平台测速未完成"
                    _platformStartupStatuses.value = _platformStartupStatuses.value.mapValues { (_, status) ->
                        if (status.running) status.copy(message = "测速中断", running = false) else status
                    }
                    _startupCheckStatuses.value = _startupCheckStatuses.value.map { check ->
                        if (check.result == StartupCheckResult.RUNNING) check.copy(result = StartupCheckResult.INTERRUPTED) else check
                    }
                }
                _running.value = false
                _progressText.value = null
                _jmProgress.value = null
            }
        }
    }

    private fun publishPlatformStatus(platform: ContentPlatform, message: String, fraction: Float? = null, running: Boolean) {
        publishPlatformStatus(platform, PlatformStartupStatus(message = message, fraction = fraction, running = running))
    }

    private fun publishPlatformStatus(platform: ContentPlatform, status: PlatformStartupStatus) {
        _platformStartupStatuses.update { current -> updatePlatformStartupStatus(current, platform, status) }
    }

    private fun publishStartupCheck(phase: StartupCheckPhase, result: StartupCheckResult, detail: String? = null) {
        _startupCheckStatuses.update { current -> updateStartupCheckStatus(current, phase, result, detail) }
    }

    private fun publishMeasuredCheck(phase: StartupCheckPhase, completed: Int, total: Int, successful: Int) {
        if (total <= 0 || completed < total) {
            publishStartupCheck(phase, StartupCheckResult.RUNNING)
        } else {
            publishStartupCheck(
                phase,
                if (successful > 0) StartupCheckResult.SUCCESS else StartupCheckResult.FAILED,
                if (successful > 0) "本轮检测到可用结果" else "本轮已检测，未发现可用结果",
            )
        }
    }

    private fun blockUnresolvedChecks(platform: ContentPlatform) {
        phasesFor(platform).forEach { phase ->
            if (_startupCheckStatuses.value.first { it.phase == phase }.result == StartupCheckResult.RUNNING) {
                publishStartupCheck(phase, StartupCheckResult.NOT_TESTED, "本轮未取得可计为通过的完整检测结果")
            }
        }
    }

    private fun failRunningChecks(platform: ContentPlatform, detail: String) {
        val phases = phasesFor(platform).toSet()
        _startupCheckStatuses.update { current ->
            current.map { check ->
                if (check.phase in phases && check.result == StartupCheckResult.RUNNING) {
                    check.copy(result = StartupCheckResult.FAILED, detail = detail)
                } else {
                    check
                }
            }
        }
    }

    private fun phasesFor(platform: ContentPlatform): List<StartupCheckPhase> = when (platform) {
        ContentPlatform.MANWA -> listOf(StartupCheckPhase.MANWA_LINE, StartupCheckPhase.MANWA_IMAGE)
        ContentPlatform.PICACG -> listOf(StartupCheckPhase.PICACG_LINE, StartupCheckPhase.PICACG_IMAGE)
        ContentPlatform.JM -> listOf(StartupCheckPhase.JM_LINE, StartupCheckPhase.JM_IMAGE)
    }

    private fun publishManwaCheckProgress(progress: SpeedTester.Progress) {
        when (progress.phase) {
            SpeedTester.Phase.PREPARING_LINES -> publishStartupCheck(StartupCheckPhase.MANWA_LINE, StartupCheckResult.RUNNING)
            SpeedTester.Phase.TESTING_LINES -> publishMeasuredCheck(StartupCheckPhase.MANWA_LINE, progress.completed, progress.total, progress.successful)
            SpeedTester.Phase.APPLYING_LINE -> publishMeasuredCheck(StartupCheckPhase.MANWA_LINE, progress.completed, progress.total, progress.successful)
            SpeedTester.Phase.FETCHING_SAMPLE, SpeedTester.Phase.PREPARING_IMAGES -> publishStartupCheck(StartupCheckPhase.MANWA_IMAGE, StartupCheckResult.RUNNING)
            SpeedTester.Phase.TESTING_IMAGES -> publishMeasuredCheck(StartupCheckPhase.MANWA_IMAGE, progress.completed, progress.total, progress.successful)
            SpeedTester.Phase.APPLYING_RESULTS -> Unit
            SpeedTester.Phase.COMPLETE -> Unit
            SpeedTester.Phase.FAILED -> _startupCheckStatuses.update { current ->
                current.map { check ->
                    if (check.phase in setOf(StartupCheckPhase.MANWA_LINE, StartupCheckPhase.MANWA_IMAGE) && check.result == StartupCheckResult.RUNNING) {
                        check.copy(result = StartupCheckResult.FAILED, detail = "本轮检测失败")
                    } else {
                        check
                    }
                }
            }
            SpeedTester.Phase.SKIPPED -> Unit
        }
    }

    private fun publishPicacgCheckProgress(progress: PicacgSpeedTester.Progress) {
        when (progress.stage) {
            PicacgSpeedTester.Stage.PREPARING_API -> publishStartupCheck(StartupCheckPhase.PICACG_LINE, StartupCheckResult.RUNNING)
            PicacgSpeedTester.Stage.TESTING_API -> publishMeasuredCheck(StartupCheckPhase.PICACG_LINE, progress.completed, progress.total, progress.successful)
            PicacgSpeedTester.Stage.PREPARING_IMAGES -> publishStartupCheck(StartupCheckPhase.PICACG_IMAGE, StartupCheckResult.RUNNING)
            PicacgSpeedTester.Stage.TESTING_IMAGES -> publishMeasuredCheck(StartupCheckPhase.PICACG_IMAGE, progress.completed, progress.total, progress.successful)
            PicacgSpeedTester.Stage.IMAGE_BLOCKED -> publishStartupCheck(
                StartupCheckPhase.PICACG_IMAGE,
                if (PicacgRouteManager.verifiedImageSampleUrl == null) StartupCheckResult.NO_SAMPLE else StartupCheckResult.BLOCKED,
                "无真实图片样本或图源 IP，未验证通过",
            )
            PicacgSpeedTester.Stage.COMPLETE -> Unit
        }
    }

    private fun publishPicacgMeasuredChecks(snapshot: PicacgSpeedTester.Snapshot) {
        fun measured(phase: StartupCheckPhase, probes: List<PicacgSpeedTester.Probe>) {
            val fresh = probes.filter { it.testedThisRound }
            publishStartupCheck(
                phase,
                when {
                    fresh.isEmpty() -> StartupCheckResult.NOT_TESTED
                    fresh.any { it.success } -> StartupCheckResult.SUCCESS
                    else -> StartupCheckResult.FAILED
                },
                if (fresh.isEmpty()) "仅复用历史结果，本轮未检测" else "仅统计本轮真实探测",
            )
        }
        measured(StartupCheckPhase.PICACG_LINE, snapshot.apiProbes)
        if (snapshot.imageBlockedReason != null) {
            publishStartupCheck(
                StartupCheckPhase.PICACG_IMAGE,
                if (PicacgRouteManager.verifiedImageSampleUrl == null) StartupCheckResult.NO_SAMPLE else StartupCheckResult.BLOCKED,
                snapshot.imageBlockedReason,
            )
        } else {
            measured(StartupCheckPhase.PICACG_IMAGE, snapshot.imageProbes)
        }
    }

    private fun publishJmCheckProgress(progress: JmSpeedTestProgress) {
        when (progress.phase) {
            JmSpeedTestProgress.Phase.PREPARING_LINES -> publishStartupCheck(StartupCheckPhase.JM_LINE, StartupCheckResult.RUNNING)
            JmSpeedTestProgress.Phase.TESTING_LINES -> publishMeasuredCheck(StartupCheckPhase.JM_LINE, progress.completed, progress.total, progress.successful)
            JmSpeedTestProgress.Phase.PREPARING_IMAGES -> publishStartupCheck(StartupCheckPhase.JM_IMAGE, StartupCheckResult.RUNNING)
            JmSpeedTestProgress.Phase.TESTING_IMAGES -> publishMeasuredCheck(StartupCheckPhase.JM_IMAGE, progress.completed, progress.total, progress.successful)
            JmSpeedTestProgress.Phase.REUSING_IMAGES -> Unit
            JmSpeedTestProgress.Phase.COMPLETE, JmSpeedTestProgress.Phase.FAILED -> Unit
        }
    }

    private fun StartupCheckPhase.platform(): ContentPlatform = when (this) {
        StartupCheckPhase.MANWA_LINE, StartupCheckPhase.MANWA_IMAGE -> ContentPlatform.MANWA
        StartupCheckPhase.PICACG_LINE, StartupCheckPhase.PICACG_IMAGE -> ContentPlatform.PICACG
        StartupCheckPhase.JM_LINE, StartupCheckPhase.JM_IMAGE -> ContentPlatform.JM
    }

    private fun publishManwaProgress(progress: SpeedTester.Progress) {
        publishPlatformStatus(ContentPlatform.MANWA, manwaStartupStatus(progress))
    }

    internal fun manwaStartupStatus(progress: SpeedTester.Progress): PlatformStartupStatus {
        val measuring = progress.phase in setOf(SpeedTester.Phase.TESTING_LINES, SpeedTester.Phase.TESTING_IMAGES)
        val running = progress.phase !in setOf(SpeedTester.Phase.COMPLETE, SpeedTester.Phase.FAILED, SpeedTester.Phase.SKIPPED)
        return PlatformStartupStatus(
            message = manwaProgressMessage(progress),
            fraction = if (measuring) platformStartupProgressFraction(progress.completed, progress.total) else null,
            running = running,
        )
    }

    private fun publishPicacgProgress(progress: PicacgSpeedTester.Progress) {
        publishPlatformStatus(ContentPlatform.PICACG, picacgStartupStatus(progress))
    }

    internal fun picacgStartupStatus(progress: PicacgSpeedTester.Progress): PlatformStartupStatus {
        val measuring = progress.stage in setOf(PicacgSpeedTester.Stage.TESTING_API, PicacgSpeedTester.Stage.TESTING_IMAGES)
        val running = progress.stage !in setOf(PicacgSpeedTester.Stage.IMAGE_BLOCKED, PicacgSpeedTester.Stage.COMPLETE)
        return PlatformStartupStatus(
            message = picacgProgressMessage(progress),
            fraction = if (measuring) platformStartupProgressFraction(progress.completed, progress.total) else null,
            running = running,
        )
    }

    private fun publishJmProgress(progress: JmSpeedTestProgress) {
        publishPlatformStatus(ContentPlatform.JM, jmStartupStatus(progress))
    }

    internal fun jmStartupStatus(progress: JmSpeedTestProgress): PlatformStartupStatus {
        val measuring = progress.phase in setOf(JmSpeedTestProgress.Phase.TESTING_LINES, JmSpeedTestProgress.Phase.TESTING_IMAGES)
        val running = progress.phase !in setOf(JmSpeedTestProgress.Phase.COMPLETE, JmSpeedTestProgress.Phase.FAILED)
        return PlatformStartupStatus(
            message = progress.message(),
            fraction = if (measuring) platformStartupProgressFraction(progress.completed, progress.total) else null,
            running = running,
        )
    }

    private fun startupSummary(reports: List<PlatformSpeedTestReport>): String {
        val tested = reports.count { it.status == "tested" || it.status == "reused" }
        val blocked = reports.count { it.status == "incomplete" }
        val skipped = reports.count { it.status == "skipped" }
        return "三平台测速完成：已完成 $tested，受阻 $blocked，跳过 $skipped"
    }

    private fun platformCompletionStatus(report: PlatformSpeedTestReport): String = when (report.status) {
        "skipped" -> "已跳过：${report.reason}"
        "incomplete" -> "测速受阻：${report.reason}"
        else -> if (report.reason.contains("前五快测全部失败")) report.reason else "测速完成"
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

    private fun report(
        platform: ContentPlatform,
        status: String,
        reason: String,
        picacgSnapshot: PicacgSpeedTester.Snapshot? = null,
    ): PlatformSpeedTestReport = when (platform) {
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
            val snapshot = picacgSnapshot
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
