package com.zycomic.app.net

import android.content.Context
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.flow.MutableStateFlow

object DevConfig {
    var hasUsableRouteConfig = true
    var version = 17
    var appContext: Context? = null
    suspend fun awaitUsableRouteConfig() = Unit
    fun remoteVersion() = version
    fun isFollowSystemVpnEnabled() = false
}

object NetworkModule {
    var direct = true
    fun shouldUseDirectStrategy() = direct
}

object RouteManager {
    var configRevision = 0L
    var baseUrl = "https://manwa.test"
    var selectedLineIp: String? = null
    var imgRewriteTarget: String? = null
    var selectedImgIp: String? = null
    var hasManualLineSelection = false
    var pinnedImgDomain: String? = null
    var lineHosts = listOf("https://manwa.test")
    var imgDomains = listOf("img.manwa.test")
    var lastLineIpDelays: Map<DomainIp, Long> = emptyMap()
    var lastImgIpDelays: Map<DomainIp, Long> = emptyMap()
    var activeRemoteConfigVersion: Int? = 17
}

object PicacgRouteManager {
    var configRevision = 0L
    var remoteVersion: Int? = 17
    var verifiedImageSampleUrl: String? = null
    var apiUrls = listOf("https://api.picacg.test")
    var imageHosts = listOf("img.picacg.test")
    var currentImageHost: String? = "img.picacg.test"
    var selectedApiEndpoint: DomainIp? = null
    var selectedImageEndpoint: DomainIp? = null
    var isApiManuallyPinned = false
    var isImageManuallyPinned = false
    fun currentBaseUrl() = apiUrls.first()
}

object JmRouteManager {
    data class State(val remoteVersion: Int? = 17)
    data class SpeedTestSnapshot(
        val lineDelaysMs: Map<String, Long> = emptyMap(),
        val imageDelaysMs: Map<String, Long> = emptyMap(),
        val startedAtEpochMs: Long? = null,
        val completedAtEpochMs: Long? = null,
        val remoteVersion: Int? = null,
        val configFingerprint: String? = null,
        val imageResultsReused: Boolean = false,
        val lineIpDelaysMs: Map<DomainIp, Long> = emptyMap(),
        val imageIpDelaysMs: Map<DomainIp, Long> = emptyMap(),
        val lineQuickTest: Boolean = false,
        val imageQuickTest: Boolean = false,
        val lineQuickTestAllFailed: Boolean = false,
        val imageQuickTestAllFailed: Boolean = false,
        val runId: String? = null,
    )
    val state = MutableStateFlow(State())
    val speedTestSnapshot = MutableStateFlow(SpeedTestSnapshot())
    var revision = 0L
    fun currentSpeedTestConfigRevision() = revision
    var baseUrl = "https://jm.test"
    var activeImageDomain = "img.jm.test"
    var isAutoSelectEnabled = true
    var pinnedImageDomain: String? = null
    var lineHosts = listOf("https://jm.test")
    var imageDomains = listOf("img.jm.test")
}

object SpeedTester {
    enum class Phase { PREPARING_LINES, TESTING_LINES, APPLYING_LINE, FETCHING_SAMPLE, PREPARING_IMAGES, TESTING_IMAGES, APPLYING_RESULTS, COMPLETE, FAILED, SKIPPED }
    data class Progress(
        val phase: Phase = Phase.COMPLETE,
        val completed: Int = 0,
        val total: Int = 0,
        val successful: Int = 0,
        val failed: Int = 0,
        val noImage: Int = 0,
        val freshImages: Int = 0,
        val reusedImages: Int = 0,
        val lineQuickTest: Boolean = false,
        val imageQuickTest: Boolean = false,
        val fullRetestRecommended: Boolean = false,
    )
    data class ImgVerdict(val ok: Boolean = true)
    val progress = MutableStateFlow(Progress())
    var lastRunSkipped = false
    var runCount = 0
    val reuseLines = mutableListOf<Boolean>()
    val reuseImages = mutableListOf<Boolean>()
    val reconcileConfigFlags = mutableListOf<Boolean>()
    var beforeApplyHook: (() -> Unit)? = null
    var emitQualifiedEndpoint = false
    var suspendBeforeQualifiedEndpointHook: (suspend () -> Unit)? = null
    var suspendAfterQualifiedEndpointHook: (suspend (Int) -> Unit)? = null
    var progressEvents = emptyList<Progress>()
    var emitRouteReady = false
    var suspendBeforeRouteReadyHook: (suspend () -> Unit)? = null
    var suspendAfterRouteReadyHook: (suspend () -> Unit)? = null

    suspend fun runAndApply(
        onProgress: ((Map<DomainIp, Long>, Map<DomainIp, Long>, Map<DomainIp, ImgVerdict>) -> Unit)? = null,
        reuseLines: Boolean = false,
        allowImageReuse: Boolean = false,
        reconcileConfig: Boolean = true,
        isCurrent: () -> Boolean = { true },
        beforeApply: suspend () -> Boolean = { true },
        onQualifiedEndpoint: (() -> Unit)? = null,
        onRouteReady: (() -> Unit)? = null,
        onStageProgress: ((Progress) -> Unit)? = null,
    ): Pair<Int, Int> {
        runCount++
        this.reuseLines += reuseLines
        reuseImages += allowImageReuse
        reconcileConfigFlags += reconcileConfig
        beforeApplyHook?.invoke()
        lastRunSkipped = !(beforeApply() && isCurrent())
        if (!lastRunSkipped && emitQualifiedEndpoint) {
            suspendBeforeQualifiedEndpointHook?.invoke()
            onQualifiedEndpoint?.invoke()
            suspendAfterQualifiedEndpointHook?.invoke(runCount)
        }
        progress.value = if (lastRunSkipped) {
            Progress(Phase.SKIPPED)
        } else {
            progressEvents.forEach { event -> onStageProgress?.invoke(event) }
            if (emitRouteReady) {
                suspendBeforeRouteReadyHook?.invoke()
                onRouteReady?.invoke()
                suspendAfterRouteReadyHook?.invoke()
            }
            Progress(
                lineQuickTest = reuseLines,
                imageQuickTest = allowImageReuse,
            )
        }
        return 0 to 0
    }
}

object PicacgSpeedTester {
    enum class Stage { PREPARING_API, TESTING_API, PREPARING_IMAGES, TESTING_IMAGES, IMAGE_BLOCKED, COMPLETE }
    data class Progress(val stage: Stage, val completed: Int = 0, val total: Int = 0, val successful: Int = 0, val failed: Int = 0)
    data class Probe(
        val endpoint: DomainIp,
        val success: Boolean,
        val elapsedMs: Long,
        val httpStatus: Int?,
        val testedThisRound: Boolean = true,
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
        val roundSelectedLine: DomainIp? = null,
    ) {
        val apiComplete get() = apiProbes.isNotEmpty()
        val imageComplete get() = imageProbes.isNotEmpty()
        val complete get() = apiComplete && imageComplete
    }
    val snapshot = MutableStateFlow<Snapshot?>(null)
    val progress = MutableStateFlow<Progress?>(null)
    var runCount = 0
    var apiProbeCount = 0
    var imageProbeCount = 0
    val reuseFlags = mutableListOf<Pair<Boolean, Boolean>>()
    val fastFlags = mutableListOf<Pair<Boolean, Boolean>>()
    val reconcileConfigFlags = mutableListOf<Boolean>()
    val forceFreshFlags = mutableListOf<Boolean>()
    var failNextRun = false
    var doNotPublishNextRun = false
    var beforeApplyHook: (() -> Unit)? = null
    var suspendBeforeApplyHook: (suspend () -> Unit)? = null
    var emitQualifiedEndpoint = false
    var suspendBeforeQualifiedEndpointHook: (suspend () -> Unit)? = null
    var suspendAfterQualifiedEndpointHook: (suspend (Int) -> Unit)? = null
    var suspendBeforeImagesHook: (suspend () -> Unit)? = null
    var emitRouteReady = false
    var suspendBeforeRouteReadyHook: (suspend () -> Unit)? = null
    var suspendAfterRouteReadyHook: (suspend () -> Unit)? = null
    var progressHook: ((Progress) -> Unit)? = null
    var apiProbesOverride: List<Probe>? = null
    var imageProbesOverride: List<Probe>? = null
    fun logSummary(probes: List<Probe>) = "${probes.count { it.success }}/${probes.size} fixture probes"

    suspend fun run(
        reuseApi: Boolean = false,
        reuseImages: Boolean = false,
        fastApi: Boolean = false,
        fastImages: Boolean = false,
        reconcileConfig: Boolean = true,
        forceFresh: Boolean = false,
        isCurrent: () -> Boolean = { true },
        beforeApply: suspend () -> Boolean = { true },
        onQualifiedEndpoint: (() -> Unit)? = null,
        onApiPublished: (DomainIp?) -> Unit = {},
        onRouteReady: (() -> Unit)? = null,
        onProgress: (Progress) -> Unit = {},
    ): Snapshot {
        runCount++
        reuseFlags += reuseApi to reuseImages
        fastFlags += fastApi to fastImages
        reconcileConfigFlags += reconcileConfig
        forceFreshFlags += forceFresh
        val previous = snapshot.value
        val sampleAvailable = PicacgRouteManager.verifiedImageSampleUrl != null
        if (!reuseApi) apiProbeCount++
        if (!reuseImages && sampleAvailable) imageProbeCount++
        Progress(Stage.PREPARING_API).also {
            onProgress(it)
            progressHook?.invoke(it)
        }
        if (!reuseApi) {
            Progress(Stage.TESTING_API, 1, 1, 1, 0).also {
                onProgress(it)
                progressHook?.invoke(it)
            }
        }
        val selectedLine = if (reuseApi) previous?.roundSelectedLine else DomainIp("api.picacg.test", "192.0.2.${10 + runCount}")
        val api = apiProbesOverride ?: if (reuseApi) {
            previous?.apiProbes.orEmpty()
        } else {
            listOfNotNull(
                selectedLine?.let { Probe(it, true, 4, 200) },
            )
        }
        val images = imageProbesOverride ?: if (reuseImages) {
            previous?.imageProbes.orEmpty().map { it.copy(testedThisRound = false) }
        } else if (sampleAvailable) {
            listOf(
                Probe(DomainIp("img.picacg.test", "192.0.2.20"), true, 5, 200),
                Probe(DomainIp("img.picacg.test", "192.0.2.21"), true, 6, 200, content = true),
            )
        } else {
            emptyList()
        }
        if (sampleAvailable) {
            Progress(Stage.TESTING_IMAGES, images.size, images.size, images.size, 0).also {
                onProgress(it)
                progressHook?.invoke(it)
            }
        }
        val result = Snapshot(
            startedAtEpochMs = runCount.toLong(),
            completedAtEpochMs = runCount.toLong() + 1,
            remoteVersion = PicacgRouteManager.remoteVersion,
            apiProbes = api,
            imageProbes = images,
            imageBlockedReason = if (images.isEmpty()) "no verified image sample" else null,
            revision = PicacgRouteManager.configRevision,
            roundSelectedLine = selectedLine,
        )
        if (failNextRun) {
            failNextRun = false
            return Snapshot(runCount.toLong(), runCount.toLong(), PicacgRouteManager.remoteVersion, emptyList(), emptyList(), "fixture failed", PicacgRouteManager.configRevision)
        }
        suspendBeforeApplyHook?.invoke()
        beforeApplyHook?.invoke()
        val canCommitApi = !doNotPublishNextRun && beforeApply() && isCurrent()
        if (canCommitApi) {
            PicacgRouteManager.selectedApiEndpoint = selectedLine
            if (emitQualifiedEndpoint && api.any { it.success && it.testedThisRound }) {
                suspendBeforeQualifiedEndpointHook?.invoke()
            onQualifiedEndpoint?.invoke()
                suspendAfterQualifiedEndpointHook?.invoke(runCount)
            }
            onApiPublished(selectedLine)
            if (emitRouteReady) {
                suspendBeforeRouteReadyHook?.invoke()
                onRouteReady?.invoke()
                suspendAfterRouteReadyHook?.invoke()
            }
            suspendBeforeImagesHook?.invoke()
        }
        if (canCommitApi && beforeApply() && isCurrent()) snapshot.value = result
        doNotPublishNextRun = false
        return result
    }

    fun exportJson(snapshot: Snapshot) = "{}"
}

data class JmSpeedTestProgress(
    val phase: Phase = Phase.COMPLETE,
    val completed: Int = 0,
    val total: Int = 0,
    val successful: Int = 0,
    val failed: Int = 0,
    val endpoint: String? = null,
    val endpointSuccessful: Boolean? = null,
    val endpointElapsedMs: Long? = null,
    val elapsedMs: Long = 0,
) {
    enum class Phase { PREPARING_LINES, TESTING_LINES, PREPARING_IMAGES, TESTING_IMAGES, REUSING_IMAGES, COMPLETE, FAILED }
    fun message(): String {
        val stage = when (phase) {
            Phase.PREPARING_LINES -> "正在解析线路"
            Phase.TESTING_LINES -> "线路测速"
            Phase.PREPARING_IMAGES -> "正在解析图源"
            Phase.TESTING_IMAGES -> "图源测速"
            Phase.REUSING_IMAGES -> "复用图源测速"
            Phase.COMPLETE -> "测速完成"
            Phase.FAILED -> "测速中断"
        }
        return "禁漫天堂 · $stage $completed/$total · 成功 $successful / 失败 $failed · 已用 ${elapsedMs / 1000}秒"
    }
}

object JmSpeedTester {
    var runCount = 0
    val reuseFlags = mutableListOf<Pair<Boolean, Boolean>>()
    val reconcileConfigFlags = mutableListOf<Boolean>()
    var progressEvents = emptyList<JmSpeedTestProgress>()
    var lineIpDelaysForRun = emptyMap<DomainIp, Long>()
    var imageIpDelaysForRun = emptyMap<DomainIp, Long>()
    var emitRouteReady = false
    var emitQualifiedEndpoint = false
    var suspendBeforeQualifiedEndpointHook: (suspend () -> Unit)? = null
    var suspendAfterQualifiedEndpointHook: (suspend (Int) -> Unit)? = null
    var suspendBeforeRouteReadyHook: (suspend () -> Unit)? = null
    var suspendAfterRouteReadyHook: (suspend () -> Unit)? = null
    data class RunResult(val snapshot: JmRouteManager.SpeedTestSnapshot, val submission: JmSpeedTestSubmission?)
    suspend fun run(
        reuseLines: Boolean = false,
        reuseImages: Boolean = false,
        reconcileConfig: Boolean = true,
        isCurrent: () -> Boolean = { true },
        beforeApply: suspend () -> Boolean = { true },
        onQualifiedEndpoint: (() -> Unit)? = null,
        onRouteReady: (() -> Unit)? = null,
        onProgress: ((JmSpeedTestProgress) -> Unit)? = null,
    ): RunResult {
        runCount++
        reuseFlags += reuseLines to reuseImages
        reconcileConfigFlags += reconcileConfig
        val runId = "offline-jm-$runCount"
        val result = if (beforeApply() && isCurrent()) {
            progressEvents.forEach { event -> onProgress?.invoke(event) }
            if (emitQualifiedEndpoint) {
                suspendBeforeQualifiedEndpointHook?.invoke()
            onQualifiedEndpoint?.invoke()
                suspendAfterQualifiedEndpointHook?.invoke(runCount)
            }
            if (emitRouteReady) {
                suspendBeforeRouteReadyHook?.invoke()
                onRouteReady?.invoke()
                suspendAfterRouteReadyHook?.invoke()
            }
            JmRouteManager.SpeedTestSnapshot(
                lineDelaysMs = mapOf("jm.test" to 2L),
                imageDelaysMs = mapOf("img.jm.test" to 3L),
                lineIpDelaysMs = lineIpDelaysForRun,
                imageIpDelaysMs = imageIpDelaysForRun,
                startedAtEpochMs = 1,
                completedAtEpochMs = 2,
                remoteVersion = DevConfig.version,
                lineQuickTest = reuseLines,
                imageQuickTest = reuseImages,
                runId = runId,
            )
        } else {
            JmRouteManager.SpeedTestSnapshot()
        }
        JmRouteManager.speedTestSnapshot.value = result
        val identity = JmSpeedTestRunIdentity(runId, 0, JmRouteManager.revision, DevConfig.version, "", "", "", "offline")
        val submission = if (result.runId != null) {
            JmSpeedTestSubmission(
                identity,
                JmSpeedTestSubmission.State.COMPLETE,
                snapshotSubmitted = true,
                historiesSaved = true,
            )
        } else {
            null
        }
        return RunResult(result, submission)
    }
}

data class JmSpeedTestRunIdentity(
    val runId: String,
    val platformRevision: Long,
    val configRevision: Long,
    val remoteVersion: Int?,
    val configFingerprint: String,
    val imageConfigFingerprint: String,
    val imageNetworkPolicy: String,
    val networkIdentity: String,
)

data class JmSpeedTestSubmission(
    val identity: JmSpeedTestRunIdentity,
    val state: State,
    val snapshotSubmitted: Boolean,
    val historiesSaved: Boolean,
) {
    enum class State(val reason: String) { COMPLETE("complete"), FAILED("failed") }
    fun isComplete(publishedRunId: String?, currentIdentity: JmSpeedTestRunIdentity?) =
        state == State.COMPLETE && snapshotSubmitted && historiesSaved && publishedRunId == identity.runId && currentIdentity == identity
}
