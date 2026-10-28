package com.zycomic.app.net

import android.content.Context
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 完整测速结束后，这段时间内的自动轮直接复用结果（避免立刻又跑一轮快测）。 */
private const val FULL_ROUND_QUIET_MS = 90_000L

/** One orchestration entry point for startup, config changes, settings and network retests. */
object PlatformSpeedTestCoordinator {
    private val lock = Mutex()
    private val startupConfig = StartupSpeedTestGate()
    private val _reports = MutableStateFlow<List<PlatformSpeedTestReport>>(emptyList())
    val reports = _reports.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _testMode = MutableStateFlow(StartupTestMode.FULL)
    val testMode = _testMode.asStateFlow()
    private val _foregroundReady = MutableStateFlow(false)
    val foregroundReady = _foregroundReady.asStateFlow()
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
    /** 最近一次完整（非快测）轮完成的时间/key：避免首次完整测速后立刻又自动跑一轮快测。 */
    private var lastFullRoundKey: SpeedTestRunKey? = null
    private var lastFullRoundCompletedAt: Long = 0L
    private var checkedConfigurationVersion: Int? = null
    private val completedPhases = CompletedSpeedTestPhases()
    private val completedReports = mutableMapOf<ContentPlatform, Pair<CompletedSpeedTestPhases.Key, PlatformSpeedTestReport>>()
    private val completedChecks = mutableMapOf<ContentPlatform, Pair<CompletedSpeedTestPhases.Key, List<StartupCheckStatus>>>()
    private val fullTestRequest = FullSpeedTestRequest<List<PlatformSpeedTestReport>>()
    /** 宿主注册：整轮开始/完整完成时用于持久化“是否测完”，未注册时为空操作。 */
    @Volatile internal var roundStartedHook: ((networkIdentity: String, configRevision: Long, startedAt: Long) -> Unit)? = null
    @Volatile internal var roundCompletedHook: (() -> Unit)? = null

    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maccmsHealthJob: Job? = null

    private val startupRequest = StartupSpeedTestRequest<SpeedTestRunKey, List<PlatformSpeedTestReport>>(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
    private val fullStartupRequest = StartupSpeedTestRequest<SpeedTestRunKey, List<PlatformSpeedTestReport>>(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        reuseCompleted = false,
    )

    /**
     * 前台进入只要求“同一网络身份”。
     *
     * 测速途中如果权威配置刚好应用（配置代变化）或平台开关代变化，已实测可用的端点仍然让用户进入；
     * 否则前台会等到整轮后台测速结束，表现为“点了很久没反应”。真正的代校验仍在 execute 内部完成，
     * 旧代的结果不会用于自动选路。
     */
    private val foregroundCompatible: (SpeedTestRunKey, SpeedTestRunKey) -> Boolean =
        { published, current -> published.networkIdentity == current.networkIdentity }

    /** App warm-up and UI waiters share the same startup worker without resetting progress. */
    suspend fun awaitStartup(): List<PlatformSpeedTestReport> = startupRequest.runUntilReady(
        ::currentKey,
        compatible = foregroundCompatible,
    ) { ready ->
        var publishedKey: SpeedTestRunKey? = null
        val result = execute(isStartupRun = true, onCompleted = { publishedKey = it }, onForegroundReady = { ready(it, emptyList()) })
        checkNotNull(publishedKey) to result
    }

    /** Explicit full work supersedes the low-priority startup tail, never its usable routes. */
    suspend fun retestAllEnabled(): List<PlatformSpeedTestReport> = fullTestRequest.run {
        awaitFullStartup()
    }

    private suspend fun awaitFullStartup(reuseCompleted: Boolean = false): List<PlatformSpeedTestReport> =
        fullStartupRequest.runUntilReady(
            ::currentKey,
            reuseCompletedResult = reuseCompleted,
            compatible = foregroundCompatible,
        ) { ready ->
            _testMode.value = StartupTestMode.FULL
            _foregroundReady.value = false
            startupRequest.supersedeWith { awaitFullStartup(reuseCompleted = true) }
            var publishedKey: SpeedTestRunKey? = null
            val result = execute(
                forceFresh = true,
                isStartupRun = true,
                onCompleted = { publishedKey = it },
                onForegroundReady = { ready(it, emptyList()) },
            )
            checkNotNull(publishedKey) to result
        }

    /**
     * 后台配置检查在“应用新配置”前等待当前启动轮结束。
     *
     * 中途换配置会让正在跑的整轮判为过期并从头重来（首屏进入被推迟一整轮）。
     * 非首次启动已经有可用配置，先测完再换配置，下一轮再按增量核对补新端点。
     */
    suspend fun awaitStartupRoundIdle(timeoutMs: Long = 60_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (_running.value && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(200L)
        }
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
        val activeCapabilities = active?.let { network -> manager?.getNetworkCapabilities(network) }
        val activeType = activeCapabilities?.let {
            networkTypeLabel(
                isVpn = it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN),
                isWifi = it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                isCellular = it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        } ?: "unknown"
        val underlying = manager?.allNetworks.orEmpty().mapNotNull { network ->
            val capabilities = manager?.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return@mapNotNull null
            }
            val type = networkTypeLabel(
                isVpn = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN),
                isWifi = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                isCellular = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
            )
            "$type@$network"
        }.sorted()
        return "active=$activeType@$active|underlying=$underlying|${NetworkModule.shouldUseDirectStrategy()}|${DevConfig.isFollowSystemVpnEnabled()}"
    }

    internal fun networkTypeLabel(isVpn: Boolean, isWifi: Boolean, isCellular: Boolean): String = buildList {
        if (isVpn) add("vpn")
        if (isWifi) add("WiFi")
        if (isCellular) add("cellular")
        if (isEmpty()) add("other")
    }.joinToString("+")

    suspend fun run(
        forceFresh: Boolean = false,
        remoteConfigUpdated: Boolean = false,
        onlyPlatform: ContentPlatform? = null,
        notifyCompletion: Boolean = true,
        isStartupRun: Boolean = false,
        onProgress: ((Map<DomainIp, Long>, Map<DomainIp, Long>, Map<DomainIp, SpeedTester.ImgVerdict>) -> Unit)? = null,
        onStageProgress: ((SpeedTester.Progress) -> Unit)? = null,
    ): List<PlatformSpeedTestReport> {
        startupRequest.invalidate()
        _foregroundReady.value = false
        return execute(forceFresh, remoteConfigUpdated, onlyPlatform, notifyCompletion, isStartupRun, onProgress, onStageProgress)
    }

    private suspend fun execute(
        forceFresh: Boolean = false,
        remoteConfigUpdated: Boolean = false,
        onlyPlatform: ContentPlatform? = null,
        notifyCompletion: Boolean = true,
        isStartupRun: Boolean = false,
        onProgress: ((Map<DomainIp, Long>, Map<DomainIp, Long>, Map<DomainIp, SpeedTester.ImgVerdict>) -> Unit)? = null,
        onStageProgress: ((SpeedTester.Progress) -> Unit)? = null,
        onCompleted: ((SpeedTestRunKey) -> Unit)? = null,
        onForegroundReady: ((SpeedTestRunKey) -> Unit)? = null,
    ): List<PlatformSpeedTestReport> = withContext(Dispatchers.IO) {
        // First install and explicit retests still wait. A cached automatic run may
        // measure speculatively, but each tester must cross beforeApply before selection.
        if (!DevConfig.hasUsableRouteConfig || forceFresh || onlyPlatform != null) {
            startupConfig.awaitConfigurationUpdated()
        }
        DevConfig.awaitUsableRouteConfig()
        lock.withLock {
            val requestedKey = currentKey()
            if (SpeedTestRunPolicy.canSkip(completedKey, requestedKey, forceFresh, onlyPlatform != null, remoteConfigUpdated)) {
                // Check after acquiring the lock: queued duplicates reuse the completed round
                // without clearing its progress, checkmarks or current-round shared IP.
                _foregroundReady.value = true
                onCompleted?.invoke(requestedKey)
                return@withLock _reports.value
            }
            // 刚跑完一轮完整测速（90 秒内、网络/远程版本/平台开关都没变）时，
            // 进入主界面触发的自动轮直接复用，不再补一轮快测。
            // 首次安装最容易踩到：完整测速刚结束，awaitStartup 又起一轮 QUICK。
            if (!forceFresh && onlyPlatform == null && !remoteConfigUpdated) {
                val lastFull = lastFullRoundKey
                val quiet = System.currentTimeMillis() - lastFullRoundCompletedAt <= FULL_ROUND_QUIET_MS
                if (quiet && lastFull != null && _reports.value.isNotEmpty() &&
                    lastFull.networkIdentity == requestedKey.networkIdentity &&
                    lastFull.remoteVersion == requestedKey.remoteVersion &&
                    lastFull.platformRevision == requestedKey.platformRevision
                ) {
                    _foregroundReady.value = true
                    onCompleted?.invoke(requestedKey)
                    return@withLock _reports.value
                }
            }
            completedKey = null
            // 整轮开始：由宿主注册的钩子落盘本轮起点（读上一轮是否未完成用于补测）。
            // 用钩子而不是直接依赖历史实现，离线 harness 不注册时自动降级为空操作。
            roundStartedHook?.invoke(currentNetworkIdentity(), RouteManager.configRevision, System.currentTimeMillis())
            _running.value = true
            _progressText.value = null
            _jmProgress.value = null
            _startupSummary.value = null
            var runCompleted = false
            try {
                // A changed toggle/config starts a new generation; never publish the old generation.
                while (true) {
                    _progressText.value = null
                    _jmProgress.value = null
                    val policy = StartupPlatformPolicy.state.value
                    val key = currentKey()
                    _foregroundReady.value = false
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
                            else -> reusableChecks(key, platform, forceFresh, onlyPlatform != null)
                                .firstOrNull { it.phase == check.phase } ?: check
                        }
                    }
                    // History ranks adaptive fast-test candidates; only this round's valid successes
                    // may drive automatic selection. Process-local revisions/network identity guard publication.
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
                    _testMode.value = if (reuseLines || reuseImages) StartupTestMode.QUICK else StartupTestMode.FULL
                    val version = DevConfig.remoteVersion()
                    val runContext = currentCoroutineContext()
                    fun generationMatches() = key == currentKey() && version == DevConfig.remoteVersion()
                    fun generationIsCurrent() = runContext.isActive && generationMatches()
                    val requiredPlatforms = ContentPlatform.entries.filter {
                        StartupPlatformPolicy.isEnabled(policy, it) && (onlyPlatform == null || onlyPlatform == it)
                    }.toSet()
                    // 放行判定：每个启用平台都必须同时具备“可用线路”和“可用图源”，
                    // 这样进入后任意平台标签页都不是坏的。阶段内已经发出的探测不会被取消，
                    // 只是继续在后台跑完；确认没有可用端点的平台按“已得出结论”记，避免卡死开屏。
                    val stageReadyLock = Any()
                    val stageReady = ContentPlatform.entries.associateWith { mutableSetOf<StartupTestStage>() }
                    val foregroundReleased = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun releaseIfAllPlatformsReady() {
                        if (startupConfig.configurationUpdated == null || !generationIsCurrent()) return
                        val ready = synchronized(stageReadyLock) {
                            requiredPlatforms.all { stageReady.getValue(it).size == StartupTestStage.entries.size }
                        }
                        if (!ready) return
                        if (!foregroundReleased.compareAndSet(false, true)) return
                        _foregroundReady.value = true
                        onForegroundReady?.invoke(key)
                    }
                    fun stageUsable(platform: ContentPlatform, stage: StartupTestStage) {
                        // 先记下来再判放行：权威配置门控可能晚于第一个可用端点完成，
                        // 记下来才能在门控完成时立刻放行，而不是丢掉这个阶段信号。
                        if (!generationIsCurrent() || platform !in requiredPlatforms) return
                        val added = synchronized(stageReadyLock) { stageReady.getValue(platform).add(stage) }
                        if (added) releaseIfAllPlatformsReady()
                    }
                    /** 平台本轮的线路/图源阶段都已经有结论（可用，或确认本轮没有可用端点）。 */
                    fun platformConcluded(platform: ContentPlatform) {
                        if (!generationIsCurrent() || platform !in requiredPlatforms) return
                        val added = synchronized(stageReadyLock) {
                            val stages = stageReady.getValue(platform)
                            val before = stages.size
                            stages.addAll(StartupTestStage.entries)
                            stages.size != before
                        }
                        if (added) releaseIfAllPlatformsReady()
                    }
                    suspend fun beforeApply(): Boolean {
                        startupConfig.awaitConfigurationUpdated()
                        return generationIsCurrent()
                    }
                    // A new Picacg generation retires both the binding and its health worker.
                    if (onlyPlatform == null || onlyPlatform == ContentPlatform.PICACG) {
                        maccmsHealthJob?.cancel()
                        MaccmsSpeedTester.invalidateLines3Ip()
                    }
                    fun publishMaccmsRoute(lines3Ip: String?) {
                        if (!generationIsCurrent()) return
                        maccmsHealthJob?.cancel()
                        val binding = MaccmsSpeedTester.bindLines3Ip(lines3Ip)
                        publishStartupCheck(
                            StartupCheckPhase.MACCMS_LINE,
                            if (binding != null) StartupCheckResult.SUCCESS else StartupCheckResult.NOT_TESTED,
                            if (binding != null) "本轮共享 lines3 IP 已就绪；域名真实连通性另行后台检测，不代表推荐 API 通过" else "本轮未选出有效 lines3 IP；不回退旧 IP 或 DNS",
                        )
                        // Not a child of the startup coroutineScope: host timeouts never delay entry.
                        // Only real probes update the independent Maccms health history.
                        maccmsHealthJob = healthScope.launch {
                            try {
                                MaccmsSpeedTester.run(
                                    quick = reuseLines,
                                    lines3Ip = lines3Ip,
                                    isCurrent = { generationMatches() && (binding == null || MaccmsSpeedTester.isLines3BindingCurrent(binding)) },
                                    beforeApply = {
                                        startupConfig.awaitConfigurationUpdated()
                                        generationMatches()
                                    },
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("PlatformSpeedTest", "Maccms background health check failed", e)
                            }
                        }
                    }
                    val measuredPlatforms = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                    // 本轮各平台是否把候选全部测完。快测早停/预算截断 → false，剩余候选交给下一轮补测；
                    // 未测（跳过/复用缓存）的平台默认 true，不阻塞“本轮测完”判定。
                    val drainedFlags = java.util.concurrent.ConcurrentHashMap<ContentPlatform, Boolean>()
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
                                var manwaLineProgress: SpeedTester.Progress? = null
                                var manwaImageProgress: SpeedTester.Progress? = null
                                var jmLineProgress: JmSpeedTestProgress? = null
                                var jmImageProgress: JmSpeedTestProgress? = null
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
                                        // A cached report is not a current-round lines3 selection. Picacg
                                        // must revalidate its lines before binding the independent Maccms pool.
                                        if (cached != null && platform != ContentPlatform.PICACG) {
                                            publishPlatformStatus(platform, platformCompletionStatus(cached), running = false)
                                            platformConcluded(platform)
                                            return@async cached
                                        }
                                    }
                                    measuredPlatforms.add(platformName(platform))
                                    _progressText.value = "正在检测${platformName(platform)}线路与图源…"
                                    publishPlatformStatus(platform, "正在检测线路与图源…", running = true)
                                    try {
                                        reason = "线路${if (reuseLines) "历史排序自适应快测" else "完整测速"}；图源${if (reuseImages) "历史排序自适应快测" else "完整测速"}（历史仅用于候选排序；健康与自动选路仅依据本轮有效实测）"
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
                                                onStageUsable = { stage -> stageUsable(platform, stage) },
                                                onStageProgress = { progress ->
                                                    if (generationIsCurrent()) {
                                                        when (progress.phase) {
                                                            SpeedTester.Phase.TESTING_LINES -> manwaLineProgress = progress
                                                            SpeedTester.Phase.TESTING_IMAGES -> manwaImageProgress = progress
                                                            else -> Unit
                                                        }
                                                        _progressText.value = manwaProgressMessage(progress)
                                                        publishManwaProgress(progress)
                                                        publishManwaCheckProgress(progress)
                                                        onStageProgress?.invoke(progress)
                                                    }
                                                },
                                            )
                                            if (!generationIsCurrent()) return@async report(platform, "incomplete", "配置已变化，丢弃预热结果")
                                            status = if (SpeedTester.lastRunSkipped) "incomplete" else "tested"
                                            drainedFlags[ContentPlatform.MANWA] =
                                                SpeedTester.progress.value.lineDrainedAll && SpeedTester.progress.value.imageDrainedAll
                                            if (status == "tested") {
                                                manwaLineProgress?.let { publishFinalMeasuredCheck(StartupCheckPhase.MANWA_LINE, it.completed, it.successful) }
                                                manwaImageProgress?.let { publishFinalMeasuredCheck(StartupCheckPhase.MANWA_IMAGE, it.completed, it.successful) }
                                                _progressText.value = manwaProgressMessage(SpeedTester.progress.value)
                                                publishManwaProgress(SpeedTester.progress.value)
                                                if (SpeedTester.progress.value.fullRetestRecommended) {
                                                    reason += "；历史排序自适应快测未取得可自动选择的有效成功结果；如需覆盖全部候选，请选择完整重新测速"
                                                }
                                            } else if (SpeedTester.progress.value.phase != SpeedTester.Phase.SKIPPED) {
                                                val failed = SpeedTester.Progress(phase = SpeedTester.Phase.FAILED)
                                                _progressText.value = manwaProgressMessage(failed)
                                                publishManwaProgress(failed)
                                                onStageProgress?.invoke(failed)
                                            }
                                        } else if (platform == ContentPlatform.PICACG) {
                                            val snapshot = PicacgSpeedTester.run(
                                                // Both API and image endpoints are probed each run; history only
                                                // affects adaptive candidate ordering.
                                                reuseApi = false,
                                                reuseImages = false,
                                                fastApi = reuseLines,
                                                fastImages = reuseImages,
                                                reconcileConfig = !forceFresh,
                                                forceFresh = forceFresh,
                                                isCurrent = ::generationIsCurrent,
                                                beforeApply = ::beforeApply,
                                                onStageUsable = { stage -> stageUsable(platform, stage) },
                                                onApiPublished = { endpoint -> publishMaccmsRoute(endpoint?.ip) },
                                                onProgress = { progress ->
                                                    if (generationIsCurrent()) {
                                                        _progressText.value = picacgProgressMessage(progress)
                                                        publishPicacgProgress(progress)
                                                        publishPicacgCheckProgress(progress)
                                                    }
                                                },
                                            )
                                            if (!generationIsCurrent()) return@async report(platform, "incomplete", "配置已变化，丢弃预热结果")
                                            picacgSnapshot = snapshot.takeIf { PicacgSpeedTester.snapshot.value == it && generationIsCurrent() }
                                            picacgSnapshot?.let { current ->
                                                publishPicacgMeasuredChecks(current)
                                            }
                                            // The API callback already bound the current round before image work.
                                            if (picacgSnapshot == null && MaccmsSpeedTester.routeSelection().lines3Ip == null) {
                                                publishMaccmsRoute(null)
                                            }
                                            // 哔咔 API 只有 5 个内置端点、图源全量探测，没有早停，视为测完。
                                            drainedFlags[ContentPlatform.PICACG] = true
                                            status = if (picacgSnapshot?.let { snapshot ->
                                                    snapshot.apiProbes.any { it.testedThisRound } && snapshot.imageProbes.any { it.testedThisRound }
                                                } == true
                                            ) {
                                                "tested"
                                            } else {
                                                "incomplete"
                                            }
                                            reason = if (snapshot.complete) {
                                                "API 根路径 HTTP 200–499（不含 3xx）：${PicacgSpeedTester.logSummary(snapshot.apiProbes)}；" +
                                                    "封面瓦片 GET 200/image 且魔数有效：${PicacgSpeedTester.logSummary(snapshot.imageProbes)}"
                                            } else {
                                                "线路：${PicacgSpeedTester.logSummary(snapshot.apiProbes)}；" +
                                                    "图源：${PicacgSpeedTester.logSummary(snapshot.imageProbes)}；" +
                                                    (snapshot.imageBlockedReason ?: "线路或图源探测不足，图源状态未确认")
                                            }
                                            _progressText.value = if (snapshot.complete) {
                                                "哔咔线路与图源测速完成"
                                            } else {
                                                "哔咔 API 已检测；图源验证阻塞"
                                            }
                                            publishPlatformStatus(platform, _progressText.value.orEmpty(), running = false)
                                        } else {
                                            val run = JmSpeedTester.run(reuseLines = reuseLines, reuseImages = reuseImages, reconcileConfig = !forceFresh, isCurrent = ::generationIsCurrent, beforeApply = ::beforeApply, onStageUsable = { stage -> stageUsable(platform, stage) }) { progress ->
                                                if (generationIsCurrent()) {
                                                    when (progress.phase) {
                                                        JmSpeedTestProgress.Phase.TESTING_LINES -> jmLineProgress = progress
                                                        JmSpeedTestProgress.Phase.TESTING_IMAGES -> jmImageProgress = progress
                                                        else -> Unit
                                                    }
                                                    _jmProgress.value = progress
                                                    _progressText.value = progress.message()
                                                    publishJmProgress(progress)
                                                    publishJmCheckProgress(progress)
                                                }
                                            }
                                            if (!generationIsCurrent()) return@async report(platform, "incomplete", "配置已变化，丢弃预热结果")
                                            val snapshot = run.snapshot
                                            val submission = run.submission
                                            val complete = submission?.isComplete(
                                                JmRouteManager.speedTestSnapshot.value.runId,
                                                submission.identity.takeIf { generationIsCurrent() },
                                            ) == true
                                            status = if (complete) "tested" else "incomplete"
                                            drainedFlags[ContentPlatform.JM] =
                                                run.snapshot.lineDrainedAll && run.snapshot.imageDrainedAll
                                            if (complete) {
                                                jmLineProgress?.let { publishFinalMeasuredCheck(StartupCheckPhase.JM_LINE, it.completed, it.successful) }
                                                jmImageProgress?.let { publishFinalMeasuredCheck(StartupCheckPhase.JM_IMAGE, it.completed, it.successful) }
                                            }
                                            if (!complete) reason = "本轮结果未完整提交：${submission?.state?.reason ?: "未启用或未返回提交结果"}"
                                            if (snapshot.lineQuickTestAllFailed || snapshot.imageQuickTestAllFailed) {
                                                reason += "；历史排序自适应快测未取得可自动选择的有效成功结果；如需覆盖全部候选，请选择完整重新测速"
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        if (!generationIsCurrent()) return@async report(platform, "incomplete", "配置已变化，丢弃预热结果")
                                        status = "incomplete"
                                        reason = "本轮检测失败，保留当前选择"
                                        drainedFlags[platform] = false
                                        if (platform == ContentPlatform.PICACG && MaccmsSpeedTester.routeSelection().lines3Ip == null) publishMaccmsRoute(null)
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
                                    if (generationIsCurrent()) {
                                        // 本轮结论已定（成功或确认没有可用端点）：不再阻塞进入。
                                        platformConcluded(platform)
                                        publishPlatformStatus(platform, platformCompletionStatus(platformReport), running = false)
                                        blockUnresolvedChecks(platform)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    // Even all-disabled/failed warm-ups must wait for authoritative config.
                    startupConfig.awaitConfigurationUpdated()
                    if (!generationIsCurrent()) continue
                    // 门控完成得比阶段信号晚时，这里补一次放行判定。
                    releaseIfAllPlatformsReady()
                    _foregroundReady.value = true
                    _reports.value = result
                    _startupSummary.value = startupSummary(result)
                    listOf(ContentPlatform.MANWA, ContentPlatform.PICACG, ContentPlatform.JM).forEach { platform ->
                        val platformReport = result.first { it.platform == platformName(platform) }
                        if (platformReport.status != "skipped") {
                            val phaseKey = phaseKey(key, platform)
                            completedReports[platform] = phaseKey to platformReport
                            completedChecks[platform] = phaseKey to _startupCheckStatuses.value.filter { it.phase in phasesFor(platform) }
                            // 只有“候选全部测完”的阶段才允许下一轮整轮复用；快测早停留下的未测候选
                            // 不能把这一阶段标记为完成，否则补测（carry-over）永远不会发生。
                            val drained = drainedFlags[platform] == true
                            if (platformReport.lineStatus in setOf("tested", "quick") && drained) {
                                completedPhases.record(phaseKey, CompletedSpeedTestPhases.Phase.LINES)
                            }
                            if (platformReport.imageStatus in setOf("tested", "quick") && drained) {
                                completedPhases.record(phaseKey, CompletedSpeedTestPhases.Phase.IMAGES)
                            }
                        }
                    }
                    if (onlyPlatform == null) {
                        val allDrained = ContentPlatform.entries.filter { it in requiredPlatforms }
                            .all { drainedFlags[it] == true }
                        val complete = result.none { it.status == "incomplete" } && allDrained
                        completedKey = if (complete) {
                            // 本轮测速会应用自己选出的线路/图源，并对各平台配置做增量核对，
                            // 这些应用会推进 routing 版本。刚测完的这一轮不能被“自己造成的版本变化”
                            // 判成过期——否则进入主界面时的 awaitStartup 会再起一轮，表现为
                            // “首次完整测速完又自动快测一次”。同一网络 / 远程版本 / 平台开关代下接受新 key。
                            val applied = currentKey()
                            if (applied.remoteVersion == key.remoteVersion &&
                                applied.platformRevision == key.platformRevision &&
                                applied.networkIdentity == key.networkIdentity
                            ) {
                                applied
                            } else {
                                key
                            }
                        } else {
                            null
                        }
                        if (completedKey != null) roundCompletedHook?.invoke()
                        // 记录“完整轮刚结束”，供上面的短期抑制使用；快测轮不参与。
                        if (!reuseLines && !reuseImages) {
                            lastFullRoundKey = completedKey ?: key
                            lastFullRoundCompletedAt = System.currentTimeMillis()
                        }
                    }
                    checkedConfigurationVersion = version
                    persist(result)
                    if (notifyCompletion) {
                        result.filter {
                            it.platform in measuredPlatforms && SpeedTestUiTextPolicy.shouldShowCompletionToast(isStartupRun, it.status)
                        }.forEach { _completions.emit(it) }
                    }
                    onCompleted?.invoke(completedKey ?: key)
                    runCompleted = true
                    return@withLock result
                }
                @Suppress("UNREACHABLE_CODE")
                emptyList()
            } finally {
                if (!runCompleted) {
                    if (onlyPlatform == null || onlyPlatform == ContentPlatform.PICACG) {
                        maccmsHealthJob?.cancel()
                        MaccmsSpeedTester.invalidateLines3Ip()
                        if (StartupPlatformPolicy.isEnabled(ContentPlatform.PICACG)) {
                            publishStartupCheck(StartupCheckPhase.MACCMS_LINE, StartupCheckResult.INTERRUPTED, "本轮中断，共享线路已失效")
                        }
                    }
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

    private fun reusableChecks(key: SpeedTestRunKey, platform: ContentPlatform, forceFresh: Boolean, platformOnly: Boolean): List<StartupCheckStatus> {
        if (forceFresh || platformOnly || platform == ContentPlatform.PICACG) return emptyList()
        val phaseKey = phaseKey(key, platform)
        if (!completedPhases.canReuse(phaseKey, CompletedSpeedTestPhases.Phase.LINES, false, false) ||
            !completedPhases.canReuse(phaseKey, CompletedSpeedTestPhases.Phase.IMAGES, false, false)
        ) {
            return emptyList()
        }
        return completedChecks[platform]?.takeIf { it.first == phaseKey }?.second.orEmpty()
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

    /** Finalize after the tester stops, including adaptive early-stop samples. */
    private fun publishFinalMeasuredCheck(phase: StartupCheckPhase, completed: Int, successful: Int) {
        publishStartupCheck(
            phase,
            when {
                completed <= 0 -> StartupCheckResult.NOT_TESTED
                successful > 0 -> StartupCheckResult.SUCCESS
                else -> StartupCheckResult.FAILED
            },
            when {
                completed <= 0 -> "本轮没有有效实测样本"
                successful > 0 -> "本轮实测取得有效成功结果"
                else -> "本轮已实测，但没有有效成功结果"
            },
        )
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
                StartupCheckResult.BLOCKED,
                "固定封面瓦片探测未通过或图源无可用 IP",
            )
            PicacgSpeedTester.Stage.COMPLETE -> Unit
        }
    }

    private fun publishPicacgMeasuredChecks(snapshot: PicacgSpeedTester.Snapshot) {
        fun measured(phase: StartupCheckPhase, probes: List<PicacgSpeedTester.Probe>) {
            val fresh = probes.filter { it.testedThisRound }
            val coverSucceeded = fresh.any { it.success && !it.content }
            val contentSucceeded = fresh.any { it.success && it.content }
            publishStartupCheck(
                phase,
                when {
                    phase == StartupCheckPhase.PICACG_IMAGE && coverSucceeded && contentSucceeded -> StartupCheckResult.SUCCESS
                    phase == StartupCheckPhase.PICACG_IMAGE && fresh.isEmpty() -> StartupCheckResult.NOT_TESTED
                    phase == StartupCheckPhase.PICACG_IMAGE -> StartupCheckResult.FAILED
                    fresh.any { it.success } -> StartupCheckResult.SUCCESS
                    fresh.isNotEmpty() -> StartupCheckResult.FAILED
                    else -> StartupCheckResult.NOT_TESTED
                },
                when {
                    phase == StartupCheckPhase.PICACG_IMAGE && coverSucceeded && contentSucceeded -> "本轮封面与正文图源均取得有效成功结果"
                    fresh.isEmpty() -> snapshot.imageBlockedReason ?: "本轮没有有效实测样本；历史结果仅供展示与排序"
                    phase == StartupCheckPhase.PICACG_IMAGE -> "本轮封面和正文图源均需各自取得有效成功结果；本轮实测未满足"
                    fresh.any { it.success } -> "仅统计本轮真实探测"
                    else -> "本轮真实探测均未通过"
                },
            )
        }
        measured(StartupCheckPhase.PICACG_LINE, snapshot.apiProbes)
        measured(StartupCheckPhase.PICACG_IMAGE, snapshot.imageProbes)
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
        StartupCheckPhase.MACCMS_LINE -> ContentPlatform.PICACG
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
        else -> if (report.reason.contains("自适应快测未取得可自动选择的有效成功结果")) report.reason else "测速完成"
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
            "漫蛙历史排序自适应快测未取得本轮可自动选择的有效成功结果；如需覆盖全部候选，请选择完整重新测速"
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
                lineStatus = if (snapshot?.apiProbes?.any { it.testedThisRound } == true) "tested" else "incomplete",
                imageStatus = if (snapshot?.imageProbes?.any { it.testedThisRound } == true) "tested" else "incomplete",
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
