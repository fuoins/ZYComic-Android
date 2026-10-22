package com.zycomic.app.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ImgEndpointFailure {
    SLOW,
    TRANSIENT,
    DEAD,
}

/**
 * 线路与图源管理。
 *
 * - 控制面线路与数据面图源仅由经过校验的远程配置提供。
 * - 远程规则表是唯一的 IP 映射来源；未声明的主机使用系统 DNS。
 * - SNI 绕过域名列表，供 SniRemovingSocketFactory 移除 SNI。
 */
object RouteManager {

    data class SpeedTestSnapshot(
        val lineDelays: Map<DomainIp, Long> = emptyMap(),
        val imgDelays: Map<DomainIp, Long> = emptyMap(),
        val imgVerdicts: Map<DomainIp, SpeedTester.ImgVerdict> = emptyMap(),
        val remoteVersion: Int? = null,
        val lineDiagnostics: Map<DomainIp, SpeedTestEndpointDiagnostic> = emptyMap(),
        val imageDiagnostics: Map<DomainIp, SpeedTestEndpointDiagnostic> = emptyMap(),
        val startedAtEpochMs: Long? = null,
        val completedAtEpochMs: Long? = null,
        val networkPath: SpeedTestNetworkPath? = null,
        val isComplete: Boolean = false,
        val configFingerprint: String? = null,
        val lineUntestedEndpoints: Set<DomainIp> = emptySet(),
        val imageUntestedEndpoints: Set<DomainIp> = emptySet(),
        val lineMeasuredAtEpochMs: Map<DomainIp, Long> = emptyMap(),
        val imageMeasuredAtEpochMs: Map<DomainIp, Long> = emptyMap(),
        val lineIdentities: Map<DomainIp, String> = emptyMap(),
        val imageIdentities: Map<DomainIp, String> = emptyMap(),
    ) {
        val canExport: Boolean
            get() = isComplete &&
                lineUntestedEndpoints.isEmpty() &&
                imageUntestedEndpoints.isEmpty() &&
                startedAtEpochMs != null &&
                completedAtEpochMs != null &&
                networkPath != null &&
                lineDiagnostics.keys == lineDelays.keys &&
                imageDiagnostics.keys == imgDelays.keys &&
                lineMeasuredAtEpochMs.keys == lineDelays.keys &&
                imageMeasuredAtEpochMs.keys == imgDelays.keys &&
                imgVerdicts.keys == imgDelays.keys &&
                lineMeasuredAtEpochMs.values.all { it > 0L && it <= (completedAtEpochMs ?: 0L) } &&
                imageMeasuredAtEpochMs.values.all { it > 0L && it <= (completedAtEpochMs ?: 0L) } &&
                lineDelays.keys.all { it.domain.isNotBlank() && it.ip.isNotBlank() } &&
                imgDelays.keys.all { it.domain.isNotBlank() && it.ip.isNotBlank() } &&
                lineDelays.all { (endpoint, delay) -> lineDiagnostics[endpoint]?.success == (delay < Long.MAX_VALUE) } &&
                imgDelays.all { (endpoint, delay) ->
                    imageDiagnostics[endpoint]?.success == (delay < Long.MAX_VALUE) &&
                        imageDiagnostics[endpoint]?.imageVerdict == imgVerdicts[endpoint]?.name?.lowercase(java.util.Locale.ROOT)
                } &&
                lineDiagnostics.values.all { diagnostic ->
                    diagnostic.attempts.size == 1 && diagnostic.attempts.single().probe == "line_api"
                } &&
                imageDiagnostics.values.all { diagnostic ->
                    diagnostic.attempts.firstOrNull()?.probe == "fixed_cover" &&
                        diagnostic.fallbackAttempted == diagnostic.attempts.any { it.probe == "fallback_sample" }
                }
    }

    data class ReusableImageSpeedTestSnapshot(
        val imgDelays: Map<DomainIp, Long>,
        val imgVerdicts: Map<DomainIp, SpeedTester.ImgVerdict>,
        val remoteVersion: Int?,
        val configFingerprint: String,
        val completedAtEpochMs: Long,
        val networkIdentity: String,
    )

    private val _speedTestSnapshot = MutableStateFlow(SpeedTestSnapshot())
    val speedTestSnapshot: StateFlow<SpeedTestSnapshot> = _speedTestSnapshot.asStateFlow()
    private val _completedSpeedTestSnapshot = MutableStateFlow(SpeedTestSnapshot())
    val completedSpeedTestSnapshot: StateFlow<SpeedTestSnapshot> = _completedSpeedTestSnapshot.asStateFlow()
    private val _reusableImageSpeedTestSnapshot = MutableStateFlow<ReusableImageSpeedTestSnapshot?>(null)
    val reusableImageSpeedTestSnapshot: StateFlow<ReusableImageSpeedTestSnapshot?> = _reusableImageSpeedTestSnapshot.asStateFlow()
    private const val REUSABLE_IMAGE_SNAPSHOT_KEY = "reusable_image_speed_test_snapshot"
    private const val COMPLETED_SPEED_TEST_SNAPSHOT_KEY = "completed_speed_test_snapshot_v1"
    private const val VERSION_SPEED_TEST_HISTORY_KEY = "version_speed_test_history_v1"

    // Candidate routes and image sources are populated only from validated remote configuration.
    @Volatile var lineHosts: List<String> = emptyList()
        private set

    @Volatile private var baselineImgDomains: List<String> = emptyList()

    /** Current image-source candidates, exactly as supplied by the active remote configuration. */
    var imgDomains: List<String> by mutableStateOf(emptyList())
        private set

    /** Increased whenever an authoritative route/source configuration replaces the active lists. */
    @Volatile var configRevision: Long = 0L
        private set

    /** Remote configuration version that supplied the currently active route/source lists. */
    @Volatile var activeRemoteConfigVersion: Int? = null
        private set

    /** Whether the current line selection must be preserved instead of auto-applying test rankings. */
    val hasManualLineSelection: Boolean get() = !autoSelectEnabled || lineOverrideActive

    /** Replace image-source candidates with the exact remote list; never merge local or learned entries. */
    @Synchronized
    fun setBaselineImgDomains(sources: List<String>) {
        val previous = persistedSelectedImgDomain() ?: imgDomains.getOrNull(imgIndex)
        val out = LinkedHashMap<String, String>()
        sources.map(String::trim).filter(String::isNotBlank).forEach { out.putIfAbsent(it.lowercase(), it) }
        baselineImgDomains = out.values.toList()
        imgDomains = baselineImgDomains
        imgIndex = if (baselineImgDomains.isEmpty()) {
            0
        } else {
            baselineImgDomains.indexOfFirst { it.equals(previous, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        }
        val savedPin = pinnedImgDomain ?: runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?.getString("pinned_img_domain", null)
        }.getOrNull()
        if (!savedPin.isNullOrBlank() && baselineImgDomains.none { it.equals(savedPin, ignoreCase = true) }) {
            pinnedImgDomain = null
            pinnedImgIp = null
            imgOverrideActive = false
            persistPinnedImgDomain(null, null)
            notifyImgPinDisplayChanged()
        } else if (!savedPin.isNullOrBlank()) {
            imgIndex = baselineImgDomains.indexOfFirst { it.equals(savedPin, ignoreCase = true) }
        }
        clearPersistedCandidateLists()
    }

    /** Clear old preference keys; they are never read as candidate sources. */
    fun loadImgDomainsFromPrefs() {
        clearPersistedCandidateLists()
    }

    private fun persistedSelectedImgDomain(): String? {
        try {
            val ctx = DevConfig.appContext ?: return null
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .let { prefs ->
                    prefs.getString("selected_img_domain", null)?.takeIf { it.isNotBlank() }?.let { return it }
                    val oldDomains = LinkedHashSet<String>().apply {
                        listOf("baseline_img_domains", "chapter_img_domains", "img_domains").forEach { key ->
                            val raw = prefs.getString(key, null) ?: return@forEach
                            runCatching {
                                val arr = org.json.JSONArray(raw)
                                for (i in 0 until arr.length()) add(arr.getString(i))
                            }
                        }
                    }.toList()
                    return oldDomains.getOrNull(prefs.getInt("img_index", -1))
                }
        } catch (_: Exception) {
            return null
        }
    }

    private fun clearPersistedCandidateLists() {
        runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?.edit()
                ?.remove("baseline_img_domains")
                ?.remove("chapter_img_domains")
                ?.remove("img_domains")
                ?.remove("server_lines")
                ?.putBoolean("img_split_v1", true)
                ?.apply()
        }
    }

    private fun normalizeLine(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun lineKey(url: String): String =
        runCatching { java.net.URI(url).host?.lowercase().orEmpty() }
            .getOrDefault(url.trim().substringAfter("://", url).substringBefore('/').lowercase())

    private fun sameLine(a: String, b: String): Boolean = lineKey(a) == lineKey(b)

    // ---- 当前选择 ----
    @Volatile var lineIndex: Int = 0
        private set

    @Volatile var imgIndex: Int = 0
        private set

    @Volatile var selectedLineIp: String? = null
        private set

    data class LineSelectionState(val index: Int, val ip: String?)

    private val _lineSelectionState = MutableStateFlow(LineSelectionState(lineIndex, selectedLineIp))
    val lineSelectionState: StateFlow<LineSelectionState> = _lineSelectionState.asStateFlow()

    private fun publishLineSelection() {
        _lineSelectionState.value = LineSelectionState(lineIndex, selectedLineIp)
    }

    @Volatile private var selectedLineDomain: String? = null

    @Volatile private var lineOverrideActive: Boolean = false

    @Volatile private var routePreferencesLoaded: Boolean = false

    // ---- 测速结果持久化（切换页面后不丢失）----
    /** 最近一次线路测速结果：index -> 毫秒，失败为 Long.MAX_VALUE */
    @Volatile
    var lastLineDelays: Map<Int, Long> = emptyMap()
        private set

    /** 最近一次图源测速结果：index -> 毫秒，失败为 Long.MAX_VALUE */
    @Volatile
    var lastImgDelays: Map<Int, Long> = emptyMap()
        private set

    @Volatile
    var lastLineIpDelays: Map<DomainIp, Long> = emptyMap()
        private set

    @Volatile
    var lastImgIpDelays: Map<DomainIp, Long> = emptyMap()
        private set

    @Volatile
    var lastImgIpVerdicts: Map<DomainIp, SpeedTester.ImgVerdict> = emptyMap()
        private set

    /** 写入最近一次测速结果（SettingsViewModel 测速完成后调用）。 */
    fun setLastDelays(line: Map<Int, Long>, img: Map<Int, Long>) {
        lastLineDelays = line
        lastImgDelays = img
    }

    /** Write per-IP measurements and maintain the legacy per-domain summaries. */
    @Synchronized
    fun setEndpointDelays(
        line: Map<DomainIp, Long>,
        img: Map<DomainIp, Long>,
        imgVerdicts: Map<DomainIp, SpeedTester.ImgVerdict>,
        remoteVersion: Int? = activeRemoteConfigVersion,
        expectedConfigRevision: Long? = null,
        lineDiagnostics: Map<DomainIp, SpeedTestEndpointDiagnostic> = emptyMap(),
        imageDiagnostics: Map<DomainIp, SpeedTestEndpointDiagnostic> = emptyMap(),
        startedAtEpochMs: Long? = null,
        completedAtEpochMs: Long? = null,
        networkPath: SpeedTestNetworkPath? = null,
        completeSnapshot: Boolean = false,
        invalidateCompletedSnapshot: Boolean = false,
        expectedPlatformRevision: Long? = null,
        imageCacheNetworkIdentity: String? = null,
        lineUntestedEndpoints: Set<DomainIp> = emptySet(),
        imageUntestedEndpoints: Set<DomainIp> = emptySet(),
        lineMeasuredAtEpochMs: Map<DomainIp, Long> = emptyMap(),
        imageMeasuredAtEpochMs: Map<DomainIp, Long> = emptyMap(),
        lineIdentities: Map<DomainIp, String> = emptyMap(),
        imageIdentities: Map<DomainIp, String> = emptyMap(),
        sortMergedHistory: Boolean = false,
    ): Boolean {
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)) return false
        if (expectedConfigRevision == null && expectedPlatformRevision == null && img.isNotEmpty() &&
            !SpeedTester.isStandaloneImageResultCurrent(img)
        ) {
            return false
        }
        if (expectedConfigRevision != null && expectedConfigRevision != configRevision) return false
        if (expectedPlatformRevision != null &&
            (
                StartupPlatformPolicy.state.value.revision != expectedPlatformRevision ||
                    !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)
                )
        ) {
            return false
        }
        if (completeSnapshot && (
                startedAtEpochMs == null || completedAtEpochMs == null || networkPath == null ||
                    lineDiagnostics.keys != line.keys ||
                    imageDiagnostics.keys != img.keys ||
                    lineUntestedEndpoints.isNotEmpty() ||
                    imageUntestedEndpoints.isNotEmpty() ||
                    imgVerdicts.keys != img.keys ||
                    line.any { (endpoint, delay) -> lineDiagnostics[endpoint]?.success != (delay < Long.MAX_VALUE) } ||
                    img.any { (endpoint, delay) ->
                        imageDiagnostics[endpoint]?.success != (delay < Long.MAX_VALUE) ||
                            imageDiagnostics[endpoint]?.imageVerdict != imgVerdicts[endpoint]?.name?.lowercase(java.util.Locale.ROOT) ||
                            imageDiagnostics[endpoint]?.attempts?.isEmpty() != false
                    } ||
                    lineDiagnostics.values.any { it.attempts.size != 1 || it.attempts.single().probe != "line_api" }
                )
        ) {
            return false
        }
        lastLineIpDelays = line.toMap()
        lastImgIpDelays = img.toMap()
        lastImgIpVerdicts = imgVerdicts.toMap()
        lastLineDelays = lineHosts.mapIndexedNotNull { index, url ->
            val host = hostFromLine(url)
            val values = line.filterKeys { it.domain.equals(host, ignoreCase = true) }.values
            if (values.isEmpty()) null else index to (values.filter { it < Long.MAX_VALUE }.minOrNull() ?: Long.MAX_VALUE)
        }.toMap()
        lastImgDelays = imgDomains.mapIndexedNotNull { index, domain ->
            val values = img.filterKeys { it.domain.equals(domain, ignoreCase = true) }.values
            if (values.isEmpty()) null else index to (values.filter { it < Long.MAX_VALUE }.minOrNull() ?: Long.MAX_VALUE)
        }.toMap()
        val previousHistory = if (!completeSnapshot && (lineUntestedEndpoints.isNotEmpty() || imageUntestedEndpoints.isNotEmpty())) {
            speedTestHistoryForVersion(remoteVersion)
        } else {
            null
        }
        val measurementTime = completedAtEpochMs ?: System.currentTimeMillis()
        val lineMeasuredAt = line.keys.mapNotNull { endpoint ->
            val measuredAt = lineMeasuredAtEpochMs[endpoint] ?: if (endpoint in lineUntestedEndpoints) {
                previousHistory?.lineMeasuredAtEpochMs?.get(endpoint)
            } else {
                measurementTime
            }
            measuredAt?.let { endpoint to it }
        }.toMap(LinkedHashMap())
        val imageMeasuredAt = img.keys.mapNotNull { endpoint ->
            val measuredAt = imageMeasuredAtEpochMs[endpoint] ?: if (endpoint in imageUntestedEndpoints) {
                previousHistory?.imageMeasuredAtEpochMs?.get(endpoint)
            } else {
                measurementTime
            }
            measuredAt?.let { endpoint to it }
        }.toMap(LinkedHashMap())
        val snapshot = SpeedTestSnapshot(
            lineDelays = line.toMap(),
            imgDelays = img.toMap(),
            imgVerdicts = imgVerdicts.toMap(),
            remoteVersion = remoteVersion,
            lineDiagnostics = if (completeSnapshot) lineDiagnostics.toMap() else emptyMap(),
            imageDiagnostics = if (completeSnapshot) imageDiagnostics.toMap() else emptyMap(),
            startedAtEpochMs = startedAtEpochMs.takeIf { completeSnapshot },
            completedAtEpochMs = completedAtEpochMs.takeIf { completeSnapshot },
            networkPath = networkPath.takeIf { completeSnapshot },
            isComplete = completeSnapshot,
            configFingerprint = currentSpeedTestConfigFingerprint().takeIf { completeSnapshot },
            lineUntestedEndpoints = lineUntestedEndpoints.toSet(),
            imageUntestedEndpoints = imageUntestedEndpoints.toSet(),
            lineMeasuredAtEpochMs = lineMeasuredAt,
            imageMeasuredAtEpochMs = imageMeasuredAt,
            lineIdentities = lineIdentities,
            imageIdentities = imageIdentities,
        )
        _speedTestSnapshot.value = snapshot
        if (completeSnapshot || lineUntestedEndpoints.isNotEmpty() || imageUntestedEndpoints.isNotEmpty()) {
            persistVersionSpeedTestHistory(
                remoteVersion = remoteVersion,
                line = line,
                img = img,
                imgVerdicts = imgVerdicts,
                lineUntestedEndpoints = lineUntestedEndpoints,
                imageUntestedEndpoints = imageUntestedEndpoints,
                lineMeasuredAtEpochMs = lineMeasuredAt,
                imageMeasuredAtEpochMs = imageMeasuredAt,
                lineIdentities = lineIdentities,
                imageIdentities = imageIdentities,
                sortMergedHistory = sortMergedHistory,
            )
        }
        if (completeSnapshot) {
            _completedSpeedTestSnapshot.value = snapshot
            persistReusableImageSpeedTestSnapshot(snapshot, imageCacheNetworkIdentity)
            persistCompletedSpeedTestSnapshot(snapshot)
        } else if (invalidateCompletedSnapshot) {
            _completedSpeedTestSnapshot.value = SpeedTestSnapshot()
        }
        return true
    }

    /** Read version-only history; migrate the legacy complete snapshot on first use. */
    @Synchronized
    fun speedTestHistoryForVersion(version: Int?): SpeedTestSnapshot? {
        val prefs = runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
        }.getOrNull() ?: return null
        val rawHistory = prefs.getString(VERSION_SPEED_TEST_HISTORY_KEY, null)
        val decoded = if (rawHistory != null) {
            runCatching { decodeVersionSpeedTestHistory(rawHistory) }.getOrNull()
        } else {
            runCatching {
                prefs.getString(COMPLETED_SPEED_TEST_SNAPSHOT_KEY, null)?.let(::decodeCompletedSpeedTestSnapshot)
            }.getOrNull()?.let { completed ->
                val completedAt = completed.completedAtEpochMs ?: return@let completed
                completed.copy(
                    lineMeasuredAtEpochMs = completed.lineDelays.keys.associateWith { completedAt },
                    imageMeasuredAtEpochMs = completed.imgDelays.keys.associateWith { completedAt },
                ).also(::persistVersionSpeedTestHistorySnapshot)
            }
        } ?: return null
        if (!SpeedTestHistoryPolicy.isVersionCompatible(decoded.remoteVersion, version) ||
            decoded.lineDelays.keys.any { it.domain.isBlank() || it.ip.isBlank() } || decoded.imgDelays.keys.any { it.domain.isBlank() || it.ip.isBlank() } ||
            decoded.lineMeasuredAtEpochMs.values.any { it <= 0L } || decoded.imageMeasuredAtEpochMs.values.any { it <= 0L } ||
            decoded.lineDelays.values.any { it < 0L } ||
            decoded.imgDelays.values.any { it < 0L } || decoded.imgDelays.keys != decoded.imgVerdicts.keys ||
            decoded.imgDelays.any { (endpoint, delay) ->
                (delay < Long.MAX_VALUE) != (decoded.imgVerdicts[endpoint] == SpeedTester.ImgVerdict.OK)
            }
        ) {
            return null
        }
        return decoded
    }

    /** History from the prior persisted version; legacy records without identities remain readable but cannot match. */
    @Synchronized
    fun latestSpeedTestHistory(): SpeedTestSnapshot? {
        val prefs = runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
        }.getOrNull() ?: return null
        return runCatching {
            prefs.getString(VERSION_SPEED_TEST_HISTORY_KEY, null)?.let(::decodeVersionSpeedTestHistory)
                ?: prefs.getString(COMPLETED_SPEED_TEST_SNAPSHOT_KEY, null)?.let(::decodeCompletedSpeedTestSnapshot)
        }.getOrNull()
    }

    /** Advance history only on a strictly newer config and retain endpoints whose persisted identity still matches. */
    @Synchronized
    fun reconcileSpeedTestHistory(
        version: Int?,
        lineIdentities: Map<DomainIp, String>,
        imageIdentities: Map<DomainIp, String>,
        expectedConfigRevision: Long,
        expectedPlatformRevision: Long,
    ): SpeedTestSnapshot? {
        if (version == null || expectedConfigRevision != configRevision ||
            StartupPlatformPolicy.state.value.revision != expectedPlatformRevision ||
            !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)
        ) {
            return null
        }
        val prior = latestSpeedTestHistory() ?: return null
        val priorVersion = prior.remoteVersion ?: return null
        if (priorVersion >= version) return null
        val lines = SpeedTestHistoryPolicy.matchingHistory(prior.lineDelays, prior.lineIdentities, lineIdentities)
            .filterKeys { (prior.lineMeasuredAtEpochMs[it] ?: 0L) > 0L }
        val images = SpeedTestHistoryPolicy.matchingHistory(prior.imgDelays, prior.imageIdentities, imageIdentities)
            .filterKeys { (prior.imageMeasuredAtEpochMs[it] ?: 0L) > 0L }
        val reconciled = SpeedTestSnapshot(
            lineDelays = lines,
            imgDelays = images,
            imgVerdicts = prior.imgVerdicts.filterKeys { it in images },
            remoteVersion = version,
            lineMeasuredAtEpochMs = prior.lineMeasuredAtEpochMs.filterKeys { it in lines },
            imageMeasuredAtEpochMs = prior.imageMeasuredAtEpochMs.filterKeys { it in images },
            lineIdentities = lineIdentities.filterKeys { it in lines },
            imageIdentities = imageIdentities.filterKeys { it in images },
        )
        // Preparation is read-only. Publish only after probes cross the final generation guard.
        return reconciled
    }

    private fun persistVersionSpeedTestHistory(
        remoteVersion: Int?,
        line: Map<DomainIp, Long>,
        img: Map<DomainIp, Long>,
        imgVerdicts: Map<DomainIp, SpeedTester.ImgVerdict>,
        lineUntestedEndpoints: Set<DomainIp>,
        imageUntestedEndpoints: Set<DomainIp>,
        lineMeasuredAtEpochMs: Map<DomainIp, Long>,
        imageMeasuredAtEpochMs: Map<DomainIp, Long>,
        lineIdentities: Map<DomainIp, String>,
        imageIdentities: Map<DomainIp, String>,
        sortMergedHistory: Boolean,
    ) {
        val prefs = runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        val previous = runCatching {
            prefs.getString(VERSION_SPEED_TEST_HISTORY_KEY, null)?.let(::decodeVersionSpeedTestHistory)
        }.getOrNull()?.takeIf { it.remoteVersion == remoteVersion }
            ?: if (prefs.getString(VERSION_SPEED_TEST_HISTORY_KEY, null) == null) {
                runCatching {
                    prefs.getString(COMPLETED_SPEED_TEST_SNAPSHOT_KEY, null)?.let(::decodeCompletedSpeedTestSnapshot)
                }.getOrNull()?.takeIf { it.remoteVersion == remoteVersion }
            } else {
                null
            }
        val freshLines = line.filterKeys { it !in lineUntestedEndpoints }
        val freshImages = img.filterKeys { it !in imageUntestedEndpoints }
        fun <T> sorted(values: Map<DomainIp, T>, delays: Map<DomainIp, Long>): Map<DomainIp, T> =
            values.entries.sortedWith(compareBy({ delays[it.key] ?: Long.MAX_VALUE }, { it.key.domain.lowercase(java.util.Locale.ROOT) }, { it.key.ip }))
                .associateTo(LinkedHashMap()) { it.key to it.value }
        val rankedLines = when {
            sortMergedHistory -> sorted(line, line)
            lineUntestedEndpoints.isEmpty() -> SpeedTestHistoryPolicy.rankAfterRun(emptyMap(), freshLines)
            else -> SpeedTestHistoryPolicy.rankAfterRun(previous?.lineDelays.orEmpty(), freshLines)
        }
        val rankedImages = when {
            sortMergedHistory -> sorted(img, img)
            imageUntestedEndpoints.isEmpty() -> SpeedTestHistoryPolicy.rankAfterRun(emptyMap(), freshImages)
            else -> SpeedTestHistoryPolicy.rankAfterRun(previous?.imgDelays.orEmpty(), freshImages)
        }
        val rankedLineTimes = rankedLines.keys.mapNotNull { endpoint ->
            (lineMeasuredAtEpochMs[endpoint] ?: previous?.lineMeasuredAtEpochMs?.get(endpoint))
                ?.let { endpoint to it }
        }.toMap(LinkedHashMap())
        val rankedImageTimes = rankedImages.keys.mapNotNull { endpoint ->
            (imageMeasuredAtEpochMs[endpoint] ?: previous?.imageMeasuredAtEpochMs?.get(endpoint))
                ?.let { endpoint to it }
        }.toMap(LinkedHashMap())
        val rankedVerdicts = buildMap {
            rankedImages.keys.forEach { endpoint ->
                (imgVerdicts[endpoint] ?: previous?.imgVerdicts?.get(endpoint))?.let { put(endpoint, it) }
            }
        }
        persistVersionSpeedTestHistorySnapshot(
            SpeedTestSnapshot(
                lineDelays = rankedLines,
                imgDelays = rankedImages,
                imgVerdicts = rankedVerdicts,
                remoteVersion = remoteVersion,
                lineMeasuredAtEpochMs = rankedLineTimes,
                imageMeasuredAtEpochMs = rankedImageTimes,
                lineIdentities = rankedLines.keys.mapNotNull { endpoint -> (lineIdentities[endpoint] ?: previous?.lineIdentities?.get(endpoint))?.let { endpoint to it } }.toMap(LinkedHashMap()),
                imageIdentities = rankedImages.keys.mapNotNull { endpoint -> (imageIdentities[endpoint] ?: previous?.imageIdentities?.get(endpoint))?.let { endpoint to it } }.toMap(LinkedHashMap()),
            ),
        )
    }

    private fun persistVersionSpeedTestHistorySnapshot(snapshot: SpeedTestSnapshot) {
        val json = org.json.JSONObject().apply {
            put("formatVersion", 1)
            put("remoteVersion", snapshot.remoteVersion ?: org.json.JSONObject.NULL)
            fun endpoints(
                delays: Map<DomainIp, Long>,
                measuredAt: Map<DomainIp, Long>,
                identities: Map<DomainIp, String>,
                verdicts: Map<DomainIp, SpeedTester.ImgVerdict>? = null,
            ) =
                org.json.JSONArray().apply {
                    delays.forEach { (endpoint, delay) ->
                        put(
                            org.json.JSONObject().apply {
                                put("domain", endpoint.domain)
                                put("ip", endpoint.ip)
                                put("delay", delay)
                                measuredAt[endpoint]?.let { put("measuredAtEpochMs", it) }
                                identities[endpoint]?.let { put("identity", it) }
                                verdicts?.get(endpoint)?.let { put("verdict", it.name) }
                            },
                        )
                    }
                }
            put("lines", endpoints(snapshot.lineDelays, snapshot.lineMeasuredAtEpochMs, snapshot.lineIdentities))
            put("images", endpoints(snapshot.imgDelays, snapshot.imageMeasuredAtEpochMs, snapshot.imageIdentities, snapshot.imgVerdicts))
        }.toString()
        runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?.edit()?.putString(VERSION_SPEED_TEST_HISTORY_KEY, json)?.apply()
        }
    }

    private fun decodeVersionSpeedTestHistory(raw: String): SpeedTestSnapshot {
        val json = org.json.JSONObject(raw)
        require(json.optInt("formatVersion", -1) == 1)
        val version = json.opt("remoteVersion").takeUnless { it == null || it == org.json.JSONObject.NULL }
            ?.let { (it as Number).toInt() }
        data class Endpoints(
            val delays: Map<DomainIp, Long>,
            val verdicts: Map<DomainIp, SpeedTester.ImgVerdict>,
            val measuredAt: Map<DomainIp, Long>,
            val identities: Map<DomainIp, String>,
        )
        fun readEndpoints(key: String, image: Boolean): Endpoints {
            val delays = LinkedHashMap<DomainIp, Long>()
            val verdicts = LinkedHashMap<DomainIp, SpeedTester.ImgVerdict>()
            val measuredAt = LinkedHashMap<DomainIp, Long>()
            val identities = LinkedHashMap<DomainIp, String>()
            val array = json.getJSONArray(key)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val endpoint = DomainIp(item.getString("domain"), item.getString("ip"))
                val delay = item.getLong("delay")
                require(endpoint.domain.isNotBlank() && endpoint.ip.isNotBlank() && delay >= 0L && delays.put(endpoint, delay) == null)
                if (item.has("measuredAtEpochMs")) {
                    val timestamp = item.getLong("measuredAtEpochMs")
                    require(timestamp > 0L)
                    measuredAt[endpoint] = timestamp
                }
                if (image) verdicts[endpoint] = SpeedTester.ImgVerdict.valueOf(item.getString("verdict"))
                item.optString("identity").takeIf { it.isNotBlank() }?.let { identities[endpoint] = it }
            }
            return Endpoints(delays, verdicts, measuredAt, identities)
        }
        val lines = readEndpoints("lines", false)
        val images = readEndpoints("images", true)
        require(images.delays.keys == images.verdicts.keys)
        require(images.delays.all { (endpoint, delay) -> (delay < Long.MAX_VALUE) == (images.verdicts[endpoint] == SpeedTester.ImgVerdict.OK) })
        return SpeedTestSnapshot(
            lineDelays = lines.delays,
            imgDelays = images.delays,
            imgVerdicts = images.verdicts,
            remoteVersion = version,
            lineMeasuredAtEpochMs = lines.measuredAt,
            imageMeasuredAtEpochMs = images.measuredAt,
            lineIdentities = lines.identities,
            imageIdentities = images.identities,
        )
    }

    /** Load a completed image-only snapshot and discard it if the active configuration changed. */
    @Synchronized
    fun refreshReusableImageSpeedTestSnapshot() {
        if (lineHosts.isEmpty() || imgDomains.isEmpty()) {
            _reusableImageSpeedTestSnapshot.value = null
            return
        }
        val prefs = runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
        }.getOrNull() ?: run {
            _reusableImageSpeedTestSnapshot.value = null
            return
        }
        val raw = prefs.getString(REUSABLE_IMAGE_SNAPSHOT_KEY, null)
        val snapshot = runCatching { raw?.let(::decodeReusableImageSpeedTestSnapshot) }.getOrNull()
        if (snapshot == null || !isReusableImageSpeedTestSnapshotValid(snapshot)) {
            prefs.edit().remove(REUSABLE_IMAGE_SNAPSHOT_KEY).apply()
            _reusableImageSpeedTestSnapshot.value = null
        } else {
            _reusableImageSpeedTestSnapshot.value = snapshot
        }
    }

    fun reusableImageSpeedTestSnapshotOrNull(): ReusableImageSpeedTestSnapshot? {
        val current = _reusableImageSpeedTestSnapshot.value
        if (current != null && isReusableImageSpeedTestSnapshotValid(current)) return current
        refreshReusableImageSpeedTestSnapshot()
        return _reusableImageSpeedTestSnapshot.value
    }

    fun isReusableImageSpeedTestSnapshotValid(snapshot: ReusableImageSpeedTestSnapshot): Boolean =
        ReusableImageSpeedTestPolicy.canReuse(
            snapshotFingerprint = snapshot.configFingerprint,
            currentFingerprint = currentImageSpeedTestFingerprint(),
            snapshotNetworkIdentity = snapshot.networkIdentity,
            currentNetworkIdentity = PlatformSpeedTestCoordinator.currentNetworkIdentity(),
            completedAtEpochMs = snapshot.completedAtEpochMs,
            nowEpochMs = System.currentTimeMillis(),
        ) &&
            imgDomains.isNotEmpty() &&
            snapshot.imgDelays.isNotEmpty() &&
            snapshot.imgDelays.keys == snapshot.imgVerdicts.keys &&
            snapshot.imgDelays.all { (endpoint, delay) ->
                val verdict = snapshot.imgVerdicts[endpoint]
                delay >= 0L &&
                    imgDomains.any { it.equals(endpoint.domain, ignoreCase = true) } &&
                    when (verdict) {
                        SpeedTester.ImgVerdict.OK -> delay < Long.MAX_VALUE
                        SpeedTester.ImgVerdict.UNREACHABLE,
                        SpeedTester.ImgVerdict.NO_IMAGE,
                        -> delay == Long.MAX_VALUE
                        null -> false
                    }
            }

    private fun currentImageSpeedTestFingerprint(): String =
        ReusableImageSpeedTestPolicy.fingerprint(imgDomains, customRule)

    private fun currentSpeedTestConfigFingerprint(): String {
        val configText = buildString {
            append(activeRemoteConfigVersion ?: "builtin").append('\n')
            lineHosts.forEach { append(it.trim().lowercase(java.util.Locale.ROOT)).append('\n') }
            append("--sources--\n")
            imgDomains.forEach { append(it.trim().lowercase(java.util.Locale.ROOT)).append('\n') }
            append("--rules--\n")
            customRule.entries.sortedBy { it.key.lowercase(java.util.Locale.ROOT) }.forEach { (domain, ips) ->
                append(domain.lowercase(java.util.Locale.ROOT)).append('=')
                ips.sorted().forEach { append(it).append(',') }
                append('\n')
            }
            append("--raw-config--\n").append(DevConfig.getConfigJson())
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(configText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun persistReusableImageSpeedTestSnapshot(snapshot: SpeedTestSnapshot, imageCacheNetworkIdentity: String?) {
        if (!snapshot.canExport || snapshot.remoteVersion != activeRemoteConfigVersion || snapshot.imgDelays.isEmpty()) return
        val reusable = ReusableImageSpeedTestSnapshot(
            imgDelays = snapshot.imgDelays.toMap(),
            imgVerdicts = snapshot.imgVerdicts.toMap(),
            remoteVersion = snapshot.remoteVersion,
            configFingerprint = currentImageSpeedTestFingerprint(),
            completedAtEpochMs = snapshot.completedAtEpochMs ?: return,
            networkIdentity = imageCacheNetworkIdentity ?: PlatformSpeedTestCoordinator.currentNetworkIdentity(),
        )
        if (!isReusableImageSpeedTestSnapshotValid(reusable)) return
        val json = org.json.JSONObject().apply {
            put("formatVersion", 3)
            put("remoteVersion", reusable.remoteVersion ?: org.json.JSONObject.NULL)
            put("configFingerprint", reusable.configFingerprint)
            put("completedAtEpochMs", reusable.completedAtEpochMs)
            put("networkIdentity", reusable.networkIdentity)
            put(
                "endpoints",
                org.json.JSONArray().apply {
                    reusable.imgDelays.entries.sortedWith(
                        compareBy<Map.Entry<DomainIp, Long>>({ it.key.domain.lowercase(java.util.Locale.ROOT) }, { it.key.ip }),
                    ).forEach { (endpoint, delay) ->
                        put(
                            org.json.JSONObject().apply {
                                put("domain", endpoint.domain)
                                put("ip", endpoint.ip)
                                put("delayMs", delay)
                                put("verdict", reusable.imgVerdicts.getValue(endpoint).name)
                            },
                        )
                    }
                },
            )
        }.toString()
        runCatching {
            val prefs = DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?: return@runCatching
            val editor = prefs.edit().putString(REUSABLE_IMAGE_SNAPSHOT_KEY, json)
            if (!editor.commit()) editor.apply()
        }
        _reusableImageSpeedTestSnapshot.value = reusable
    }

    private fun decodeReusableImageSpeedTestSnapshot(raw: String): ReusableImageSpeedTestSnapshot {
        val json = org.json.JSONObject(raw)
        require(json.optInt("formatVersion", -1) == 3)
        val delays = LinkedHashMap<DomainIp, Long>()
        val verdicts = LinkedHashMap<DomainIp, SpeedTester.ImgVerdict>()
        val endpoints = json.getJSONArray("endpoints")
        for (index in 0 until endpoints.length()) {
            val item = endpoints.getJSONObject(index)
            val endpoint = DomainIp(item.getString("domain"), item.getString("ip"))
            val delay = item.getLong("delayMs")
            val verdict = SpeedTester.ImgVerdict.valueOf(item.getString("verdict"))
            require(delay >= 0L)
            require(delays.put(endpoint, delay) == null)
            verdicts[endpoint] = verdict
        }
        val version = json.opt("remoteVersion")
            .takeUnless { it == null || it == org.json.JSONObject.NULL }
            ?.let { (it as Number).toInt() }
        return ReusableImageSpeedTestSnapshot(
            imgDelays = delays,
            imgVerdicts = verdicts,
            remoteVersion = version,
            configFingerprint = json.getString("configFingerprint"),
            completedAtEpochMs = json.getLong("completedAtEpochMs"),
            networkIdentity = json.getString("networkIdentity"),
        )
    }

    /** Reuse a full persisted test only when config, resolved endpoints, VPN policy and platform revision still match. */
    suspend fun reuseValidResults(): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val platformRevision = StartupPlatformPolicy.state.value.revision
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)) return@withContext false
        val cached = runCatching {
            val prefs = DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?: return@runCatching null
            prefs.getString(COMPLETED_SPEED_TEST_SNAPSHOT_KEY, null)?.let(::decodeCompletedSpeedTestSnapshot)
        }.getOrNull() ?: return@withContext false
        val currentRevision = configRevision
        val currentPath = currentNetworkPath()
        val previousLineIndex = lineIndex
        val previousLineIp = selectedLineIp
        val previousImageTarget = imgRewriteTarget ?: imgHost
        val previousImageIp = selectedImgIp
        if (platformRevision != StartupPlatformPolicy.state.value.revision ||
            !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) ||
            cached.remoteVersion != activeRemoteConfigVersion ||
            cached.configFingerprint != currentSpeedTestConfigFingerprint() ||
            cached.networkPath != currentPath ||
            cached.lineDelays.keys != SpeedTester.currentLineEndpoints() ||
            cached.imgDelays.keys != SpeedTester.currentImageEndpoints() ||
            currentRevision != configRevision
        ) {
            return@withContext false
        }
        val accepted = setEndpointDelays(
            line = cached.lineDelays,
            img = cached.imgDelays,
            imgVerdicts = cached.imgVerdicts,
            remoteVersion = cached.remoteVersion,
            expectedConfigRevision = currentRevision,
            lineDiagnostics = cached.lineDiagnostics,
            imageDiagnostics = cached.imageDiagnostics,
            startedAtEpochMs = cached.startedAtEpochMs,
            completedAtEpochMs = cached.completedAtEpochMs,
            networkPath = cached.networkPath,
            completeSnapshot = false,
            expectedPlatformRevision = platformRevision,
            lineMeasuredAtEpochMs = cached.lineMeasuredAtEpochMs,
            imageMeasuredAtEpochMs = cached.imageMeasuredAtEpochMs,
        )
        if (!accepted || platformRevision != StartupPlatformPolicy.state.value.revision ||
            !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) || currentRevision != configRevision
        ) {
            return@withContext false
        }
        if (!hasManualLineSelection) {
            SpeedTester.fastestLineEndpoint(cached.lineDelays)?.let {
                applyFastestLineEndpoint(it, currentRevision, platformRevision)
            }
        }
        if (platformRevision != StartupPlatformPolicy.state.value.revision ||
            !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) || currentRevision != configRevision
        ) {
            return@withContext false
        }
        fastestImgEndpoint?.let { applyFastestImgEndpoint(it, currentRevision, platformRevision) }
        if (platformRevision != StartupPlatformPolicy.state.value.revision ||
            !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) || currentRevision != configRevision
        ) {
            return@withContext false
        }
        val lineChanged = lineIndex != previousLineIndex || selectedLineIp != previousLineIp
        if (lineChanged && !hasManualLineSelection && platformRevision == StartupPlatformPolicy.state.value.revision &&
            StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) && currentRevision == configRevision
        ) {
            NetworkModule.rebuildApi("复用有效测速结果")
        }
        val imageTarget = imgRewriteTarget ?: imgHost
        val imageChanged = !previousImageTarget.equals(imageTarget, ignoreCase = true) || previousImageIp != selectedImgIp
        if (imageChanged && platformRevision == StartupPlatformPolicy.state.value.revision &&
            StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) && currentRevision == configRevision
        ) {
            NetworkModule.rebindImageRoute("复用有效测速结果")
        }
        true
    }

    private fun currentNetworkPath(): SpeedTestNetworkPath {
        val followVpn = DevConfig.isFollowSystemVpnEnabled()
        val vpnActive = NetworkModule.isSystemVpnActive()
        return SpeedTestNetworkPath(
            followSystemVpnEnabled = followVpn,
            systemVpnActive = vpnActive,
            directStrategy = !followVpn || !vpnActive,
            androidApiLevel = android.os.Build.VERSION.SDK_INT,
            androidRelease = android.os.Build.VERSION.RELEASE,
        )
    }

    private fun persistCompletedSpeedTestSnapshot(snapshot: SpeedTestSnapshot) {
        if (!snapshot.canExport || snapshot.remoteVersion != activeRemoteConfigVersion) return
        val json = org.json.JSONObject().apply {
            put("formatVersion", 1)
            put("remoteVersion", snapshot.remoteVersion ?: org.json.JSONObject.NULL)
            put("configFingerprint", snapshot.configFingerprint ?: return)
            put("started", snapshot.startedAtEpochMs)
            put("completed", snapshot.completedAtEpochMs)
            put(
                "network",
                org.json.JSONObject().apply {
                    val path = requireNotNull(snapshot.networkPath)
                    put("followVpn", path.followSystemVpnEnabled)
                    put("vpnActive", path.systemVpnActive)
                    put("direct", path.directStrategy)
                    put("api", path.androidApiLevel ?: org.json.JSONObject.NULL)
                    put("release", path.androidRelease ?: org.json.JSONObject.NULL)
                },
            )
            fun endpoints(
                delays: Map<DomainIp, Long>,
                measuredAt: Map<DomainIp, Long>,
                verdicts: Map<DomainIp, SpeedTester.ImgVerdict>? = null,
            ) =
                org.json.JSONArray().apply {
                    delays.entries.sortedWith(
                        compareBy<Map.Entry<DomainIp, Long>>(
                            { it.value >= Long.MAX_VALUE },
                            { it.value },
                            { it.key.domain.lowercase(java.util.Locale.ROOT) },
                            { it.key.ip },
                        ),
                    ).forEach { (endpoint, delay) ->
                        put(
                            org.json.JSONObject().apply {
                                put("domain", endpoint.domain)
                                put("ip", endpoint.ip)
                                put("delay", delay)
                                measuredAt[endpoint]?.let { put("measuredAtEpochMs", it) }
                                verdicts?.get(endpoint)?.let { put("verdict", it.name) }
                            },
                        )
                    }
                }
            put("lines", endpoints(snapshot.lineDelays, snapshot.lineMeasuredAtEpochMs))
            put("images", endpoints(snapshot.imgDelays, snapshot.imageMeasuredAtEpochMs, snapshot.imgVerdicts))
        }.toString()
        runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?.edit()?.putString(COMPLETED_SPEED_TEST_SNAPSHOT_KEY, json)?.apply()
        }
    }

    private fun decodeCompletedSpeedTestSnapshot(raw: String): SpeedTestSnapshot {
        val json = org.json.JSONObject(raw)
        require(json.optInt("formatVersion", -1) == 1)
        val version = json.opt("remoteVersion").takeUnless { it == null || it == org.json.JSONObject.NULL }
            ?.let { (it as Number).toInt() }
        val fingerprint = json.getString("configFingerprint")
        val pathJson = json.getJSONObject("network")
        val path = SpeedTestNetworkPath(
            followSystemVpnEnabled = pathJson.getBoolean("followVpn"),
            systemVpnActive = pathJson.getBoolean("vpnActive"),
            directStrategy = pathJson.getBoolean("direct"),
            androidApiLevel = pathJson.opt("api").takeUnless { it == null || it == org.json.JSONObject.NULL }?.let { (it as Number).toInt() },
            androidRelease = pathJson.optString("release").takeIf(String::isNotBlank),
        )
        val completedAt = json.getLong("completed")
        fun readEndpoints(key: String, isImage: Boolean): Triple<Map<DomainIp, Long>, Map<DomainIp, SpeedTester.ImgVerdict>, Map<DomainIp, Long>> {
            val delays = LinkedHashMap<DomainIp, Long>()
            val verdicts = LinkedHashMap<DomainIp, SpeedTester.ImgVerdict>()
            val measuredAt = LinkedHashMap<DomainIp, Long>()
            val array = json.getJSONArray(key)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val endpoint = DomainIp(item.getString("domain"), item.getString("ip"))
                val delay = item.getLong("delay")
                require(endpoint.domain.isNotBlank() && endpoint.ip.isNotBlank() && delay >= 0L && delays.put(endpoint, delay) == null)
                val timestamp = if (item.has("measuredAtEpochMs")) item.getLong("measuredAtEpochMs") else completedAt
                require(timestamp > 0L && timestamp <= completedAt)
                measuredAt[endpoint] = timestamp
                if (isImage) verdicts[endpoint] = SpeedTester.ImgVerdict.valueOf(item.getString("verdict"))
            }
            return Triple(delays, verdicts, measuredAt)
        }
        val (lines, _, lineTimes) = readEndpoints("lines", false)
        val (images, verdicts, imageTimes) = readEndpoints("images", true)
        return SpeedTestSnapshot(
            lineDelays = lines,
            imgDelays = images,
            imgVerdicts = verdicts,
            remoteVersion = version,
            startedAtEpochMs = json.getLong("started"),
            completedAtEpochMs = completedAt,
            networkPath = path,
            isComplete = false,
            configFingerprint = fingerprint,
            lineMeasuredAtEpochMs = lineTimes,
            imageMeasuredAtEpochMs = imageTimes,
        ).also {
            require(fingerprint.isNotBlank())
            require(it.startedAtEpochMs != null && it.completedAtEpochMs != null && it.completedAtEpochMs >= it.startedAtEpochMs)
            require(it.imgVerdicts.keys == it.imgDelays.keys)
            require(it.imgDelays.all { (endpoint, delay) -> (delay < Long.MAX_VALUE) == (it.imgVerdicts[endpoint] == SpeedTester.ImgVerdict.OK) })
        }
    }

    private fun invalidateCompletedSpeedTestSnapshot() {
        runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?.edit()?.remove(COMPLETED_SPEED_TEST_SNAPSHOT_KEY)?.apply()
        }
    }

    fun isSpeedTestToday(): Boolean = try {
        val ctx = DevConfig.appContext ?: return false
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            .getString("last_speed_test_date", "") == today
    } catch (_: Exception) {
        false
    }

    fun markSpeedTestToday() {
        try {
            val ctx = DevConfig.appContext ?: return
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putString("last_speed_test_date", today).apply()
        } catch (_: Exception) {}
    }

    /** Current API base URL; empty until validated remote configuration is available. */
    val baseUrl: String get() = lineHosts.getOrNull(lineIndex).orEmpty()

    /** Current image host, or empty until remote configuration is available. */
    val imgHost: String get() = imgDomains.getOrNull(imgIndex).orEmpty()

    /** Current route host, or empty until remote configuration is available. */
    val lineHost: String
        get() = hostFromLine(baseUrl)

    val hasRemoteConfig: Boolean
        get() = lineHosts.isNotEmpty() && imgDomains.isNotEmpty() && customRule.isNotEmpty()

    /** Clear every route/rule when no valid cached remote configuration exists. */
    @Synchronized
    fun clearConfiguration() {
        lineHosts = emptyList()
        baselineImgDomains = emptyList()
        imgDomains = emptyList()
        lineIndex = 0
        imgIndex = 0
        selectedLineIp = null
        selectedLineDomain = null
        lineOverrideActive = false
        publishLineSelection()
        setCustomRule(emptyMap())
        setSniDomains(emptySet())
        activeRemoteConfigVersion = null
        clearSpeedTestResults()
        invalidateCompletedSpeedTestSnapshot()
        clearPersistedCandidateLists()
        configRevision++
        invalidateReusableImageSpeedTestSnapshot()
    }

    private fun hostFromLine(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')

    private fun lineIndexForDomain(domain: String): Int =
        lineHosts.indexOfFirst { hostFromLine(it).equals(domain, ignoreCase = true) }

    // DNS/IP rules are authoritative only when supplied by validated remote configuration.
    // ---- 运行时自定义 rule（域名 -> IP 列表），优先级高于预设 ----
    @Volatile
    var customRule: Map<String, List<String>> = emptyMap()
        private set

    // ---- 运行时 SNI 绕过域名列表 ----
    @Volatile
    var sniDomains: Set<String> = emptySet()
        private set

    /** 切换线路；域名级选择清除旧 IP pin，避免把另一线路的地址误用于新 host。 */
    @Synchronized
    fun setLine(index: Int) {
        if (lineHosts.isEmpty()) return
        lineIndex = index.coerceIn(0, lineHosts.lastIndex)
        selectedLineIp = null
        selectedLineDomain = hostFromLine(lineHosts[lineIndex])
        lineOverrideActive = autoSelectEnabled
        persistLineEndpoint(selectedLineDomain.takeUnless { autoSelectEnabled }, null)
        publishLineSelection()
    }

    /** 精确选择线路域名/IP组合；自动模式下只覆盖本次运行，手动模式下持久化。 */
    @Synchronized
    fun setLineEndpoint(domain: String, ip: String) {
        val index = lineIndexForDomain(domain)
        if (index < 0) return
        lineIndex = index
        selectedLineIp = ip
        selectedLineDomain = hostFromLine(lineHosts[index])
        lineOverrideActive = autoSelectEnabled
        persistLineEndpoint(selectedLineDomain.takeUnless { autoSelectEnabled }, ip.takeUnless { autoSelectEnabled })
        publishLineSelection()
    }

    /** Apply the measured fastest endpoint only while automatic selection is enabled and unoverridden. */
    @Synchronized
    fun applyFastestLineEndpoint(
        endpoint: DomainIp?,
        expectedConfigRevision: Long? = null,
        expectedPlatformRevision: Long? = null,
    ): Boolean {
        endpoint ?: return false
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) ||
            (expectedConfigRevision != null && expectedConfigRevision != configRevision) ||
            (expectedPlatformRevision != null && expectedPlatformRevision != StartupPlatformPolicy.state.value.revision)
        ) {
            return false
        }
        if (!autoSelectEnabled || lineOverrideActive) return false
        val index = lineIndexForDomain(endpoint.domain)
        if (index < 0) return false
        val changed = lineIndex != index || selectedLineIp != endpoint.ip
        lineIndex = index
        selectedLineIp = endpoint.ip
        selectedLineDomain = hostFromLine(lineHosts[index])
        persistLineEndpoint(null, null)
        publishLineSelection()
        return changed
    }

    /** 切换图源 */
    @Synchronized
    fun setImgHost(index: Int) {
        if (imgDomains.isEmpty()) return
        imgIndex = index.coerceIn(0, imgDomains.lastIndex)
        saveImgIndex()
    }

    /** 从 SharedPreferences 加载图源索引（App启动时调用）。 */
    @Synchronized
    fun loadImgIndex() {
        try {
            val ctx = DevConfig.appContext ?: return
            if (imgDomains.isEmpty()) {
                imgIndex = 0
                return
            }
            if (useFastestImgForAll) {
                imgOverrideActive = false
                imgIndex = fastestImgDomain?.let { domain ->
                    imgDomains.indexOfFirst { it.equals(domain, ignoreCase = true) }.takeIf { it >= 0 }
                } ?: 0
                saveImgIndex()
                return
            }
            val prefs = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            val savedDomain = prefs.getString("selected_img_domain", null)
            val savedIndex = savedDomain?.let { domain ->
                imgDomains.indexOfFirst { it.equals(domain, ignoreCase = true) }.takeIf { it >= 0 }
            }
            imgIndex = savedIndex ?: 0
            saveImgIndex()
        } catch (_: Exception) {}
    }

    /** 保存图源索引到 SharedPreferences。 */
    @Synchronized
    private fun saveImgIndex() {
        try {
            val ctx = DevConfig.appContext ?: return
            val editor = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE).edit()
            if (useFastestImgForAll) {
                editor.remove("img_index").remove("selected_img_domain")
            } else {
                editor.putInt("img_index", imgIndex)
                    .putString("selected_img_domain", imgDomains.getOrNull(imgIndex))
            }
            editor.apply()
        } catch (_: Exception) {}
    }

    // ---- 自动选线 ----
    @Volatile
    var autoSelectEnabled: Boolean = true
        private set

    @Volatile
    var lastFastestLineIndex: Int = 0
        private set

    /** 从 SP 恢复选线偏好及最近选择的线路/IP。 */
    @Synchronized
    fun loadAutoSelectPrefs() {
        lineOverrideActive = false
        try {
            val ctx = DevConfig.appContext ?: return
            val prefs = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            autoSelectEnabled = prefs.getBoolean("auto_select_enabled", prefs.getBoolean("auto_select", true))
            prefs.edit().putBoolean("auto_select_enabled", autoSelectEnabled).remove("auto_select").apply()
            lastFastestLineIndex = prefs.getInt("last_fastest_line", 0)
            routePreferencesLoaded = true
            if (lineHosts.isEmpty()) return
            val savedDomain = prefs.getString("selected_line_domain", null)
            val savedIp = prefs.getString("selected_line_ip", null)
            val savedIndex = savedDomain?.let(::lineIndexForDomain)?.takeIf { it >= 0 }
            if (autoSelectEnabled) {
                lineIndex = lastFastestLineIndex.takeIf { it in lineHosts.indices } ?: 0
                selectedLineIp = null
                selectedLineDomain = null
                persistLineEndpoint(null, null)
            } else if (savedIndex != null) {
                lineIndex = savedIndex
                selectedLineIp = savedIp
                selectedLineDomain = hostFromLine(lineHosts[savedIndex])
            } else {
                lineIndex = 0
                selectedLineIp = null
                selectedLineDomain = null
                if (!savedDomain.isNullOrBlank()) persistLineEndpoint(null, null)
            }
        } catch (_: Exception) {
        } finally {
            publishLineSelection()
        }
    }

    @Synchronized
    fun setAutoSelectEnabled(v: Boolean) {
        autoSelectEnabled = v
        routePreferencesLoaded = true
        lineOverrideActive = false
        if (v) {
            selectedLineIp = null
            selectedLineDomain = null
            persistLineEndpoint(null, null)
            if (lastFastestLineIndex in lineHosts.indices) {
                lineIndex = lastFastestLineIndex
                val fastestEndpoint = lastLineIpDelays.entries
                    .asSequence()
                    .filter { (endpoint, delay) -> lineIndexForDomain(endpoint.domain) == lineIndex && delay < Long.MAX_VALUE }
                    .minByOrNull { it.value }
                    ?.key
                if (fastestEndpoint != null) applyFastestLineEndpoint(fastestEndpoint)
            }
        } else {
            selectedLineDomain = lineHosts.getOrNull(lineIndex)?.let(::hostFromLine)
            persistLineEndpoint(selectedLineDomain, selectedLineIp)
        }
        publishLineSelection()
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("auto_select_enabled", v).apply()
        } catch (_: Exception) {}
    }

    fun saveLastFastestLine(index: Int) {
        lastFastestLineIndex = index
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putInt("last_fastest_line", index).apply()
        } catch (_: Exception) {}
    }

    private fun persistLineEndpoint(domain: String?, ip: String?) {
        try {
            val ctx = DevConfig.appContext ?: return
            val editor = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE).edit()
            if (domain == null) editor.remove("selected_line_domain") else editor.putString("selected_line_domain", domain)
            if (ip == null) editor.remove("selected_line_ip") else editor.putString("selected_line_ip", ip)
            editor.apply()
        } catch (_: Exception) {}
    }

    /** Current selected API IP, only for the active API hostname. */
    fun lineIpFor(domain: String): String? =
        selectedLineIp?.takeIf {
            lineHost.equals(domain, ignoreCase = true) && selectedLineDomain.equals(domain, ignoreCase = true)
        }

    // ---- 封面/章节统一用测速最快图源 ----
    var useFastestImgForAll: Boolean by androidx.compose.runtime.mutableStateOf(true)
        private set

    @Volatile private var imgOverrideActive: Boolean = false

    @Synchronized
    fun loadUseFastestImgForAll() {
        try {
            val ctx = DevConfig.appContext ?: return
            useFastestImgForAll = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .getBoolean("use_fastest_img_for_all", true)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun updateUseFastestImgForAll(v: Boolean) {
        useFastestImgForAll = v
        if (v) {
            imgOverrideActive = false
            clearPinnedImgDomain()
        } else {
            if (imgOverrideActive && !pinnedImgDomain.isNullOrBlank()) {
                persistPinnedImgDomain(pinnedImgDomain, pinnedImgIp)
                saveImgIndex()
            }
            imgOverrideActive = false
        }
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("use_fastest_img_for_all", v).apply()
        } catch (_: Exception) {}
    }

    /** Successful configured image endpoints ordered exactly like the speed table. */
    fun rankedSuccessfulImgEndpoints(): List<DomainIp> =
        ImageEndpointSelectionPolicy.rankedSuccessfulEndpoints(
            delays = lastImgIpDelays,
            verdicts = lastImgIpVerdicts,
            configuredDomains = imgDomains,
        )

    /** Fastest reachable image domain/IP combination from the most recent run. */
    val fastestImgEndpoint: DomainIp?
        get() = rankedSuccessfulImgEndpoints().firstOrNull { it !in _speedTestSnapshot.value.imageUntestedEndpoints }

    /** 从逐 IP 测速结果派生最快图源域名，无数据返回 null。 */
    val fastestImgDomain: String?
        get() = fastestImgEndpoint?.domain

    /** 当前生效图源的精确 IP（手动 pin 优先，否则跟随自动最快组合）。 */
    val selectedImgIp: String?
        get() {
            val target = imgRewriteTarget ?: imgHost
            pinnedImgIp?.takeIf { pinnedImgDomain.equals(target, ignoreCase = true) }?.let { return it }
            runtimeRecoveryImgEndpoint?.takeIf { pinnedImgDomain.isNullOrBlank() && it.domain.equals(target, true) }?.let { return it.ip }
            if (useFastestImgForAll) {
                return fastestImgEndpoint?.takeIf { it.domain.equals(target, ignoreCase = true) }?.ip
            }
            return null
        }

    // ---- 手动 pin 图源（自动模式中仅覆盖本次运行；手动模式中持久化）----
    /**
     * 用户手动钉住的图源域名。
     *
     * 修既有 bug：`useFastestImgForAll` 为 true 时拦截器只读派生的 [fastestImgDomain]，
     * 手动 `setImgHost` 改的 imgIndex 根本不参与 URL 改写 —— 阅读器/设置页里的手动切图源
     * 对实际请求是空操作。现在手动选择写入本字段，并由 [imgRewriteTarget] 置于最高优先级。
     * 优先级判定本身在 [ImgDomainPolicy]（纯函数，可单测）。
     */
    @Volatile var pinnedImgDomain: String? = null
        private set

    @Volatile private var runtimeRecoveryImgDomain: String? = null

    @Volatile private var runtimeRecoveryImgEndpoint: DomainIp? = null

    /** Keep configured domain ordering; only previously successful endpoints are eligible. */
    fun runtimeImageRecoveryCandidates(expectedSource: String): List<DomainIp> {
        if (!useFastestImgForAll || !pinnedImgDomain.isNullOrBlank()) return emptyList()
        val currentIndex = imgDomains.indexOfFirst { it.equals(expectedSource, true) }
        if (currentIndex < 0) return emptyList()
        val measured = rankedSuccessfulImgEndpoints()
        return (1 until imgDomains.size).mapNotNull { offset ->
            val domain = imgDomains[(currentIndex + offset) % imgDomains.size]
            measured.firstOrNull {
                it.domain.equals(domain, true) && it !in _speedTestSnapshot.value.imageUntestedEndpoints
            }
        }
    }

    @Synchronized
    fun advanceRuntimeImageSourceAfterRecovery(
        expectedSource: String,
        expectedConfigRevision: Long,
        expectedPlatformRevision: Long,
        validatedEndpoint: DomainIp,
    ): Boolean {
        if (!RuntimeNetworkGate.isOnline() || !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) ||
            configRevision != expectedConfigRevision ||
            StartupPlatformPolicy.state.value.revision != expectedPlatformRevision ||
            !(imgRewriteTarget ?: imgHost).equals(expectedSource, true) ||
            !useFastestImgForAll || !pinnedImgDomain.isNullOrBlank() || imgDomains.size < 2
        ) {
            return false
        }
        if (validatedEndpoint !in runtimeImageRecoveryCandidates(expectedSource)) return false
        runtimeRecoveryImgEndpoint = validatedEndpoint
        runtimeRecoveryImgDomain = validatedEndpoint.domain
        return true
    }

    /** Compose-only invalidation for display consumers that read the volatile pin fields. */
    var imgPinDisplayRevision: Int by mutableStateOf(0)
        private set

    private fun notifyImgPinDisplayChanged() {
        imgPinDisplayRevision++
    }

    /** 手动选定图源：同时更新 imgIndex（UI 显示）与 pin（实际生效）。 */
    @Synchronized
    fun pinImgHost(index: Int, ip: String? = null) {
        if (imgDomains.isEmpty()) return
        val safe = index.coerceIn(0, imgDomains.lastIndex)
        val domain = imgDomains.getOrNull(safe) ?: return
        setImgHost(safe)
        pinnedImgDomain = domain
        pinnedImgIp = ip
        imgOverrideActive = useFastestImgForAll
        if (useFastestImgForAll) {
            persistPinnedImgDomain(null, null)
        } else {
            persistPinnedImgDomain(domain, ip)
        }
        notifyImgPinDisplayChanged()
    }

    /** Apply the fastest measured combination unless the user has pinned a source. */
    @Synchronized
    fun applyFastestImgEndpoint(
        endpoint: DomainIp?,
        expectedConfigRevision: Long? = null,
        expectedPlatformRevision: Long? = null,
    ): Boolean {
        endpoint ?: return false
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) ||
            (expectedConfigRevision != null && expectedConfigRevision != configRevision) ||
            (expectedPlatformRevision != null && expectedPlatformRevision != StartupPlatformPolicy.state.value.revision)
        ) {
            return false
        }
        if (!useFastestImgForAll || imgOverrideActive || !pinnedImgDomain.isNullOrBlank()) return false
        val index = imgDomains.indexOfFirst { it.equals(endpoint.domain, ignoreCase = true) }
        if (index < 0) return false
        runtimeRecoveryImgDomain = null
        runtimeRecoveryImgEndpoint = null
        if (pinnedImgDomain.isNullOrBlank()) setImgHost(index)
        return true
    }

    /** 恢复自动：清除手动 pin，重新由测速结果决定。 */
    @Synchronized
    fun clearPinnedImgDomain() {
        pinnedImgDomain = null
        pinnedImgIp = null
        runtimeRecoveryImgDomain = null
        runtimeRecoveryImgEndpoint = null
        imgOverrideActive = false
        persistPinnedImgDomain(null, null)
        notifyImgPinDisplayChanged()
        if (useFastestImgForAll) fastestImgEndpoint?.let(::applyFastestImgEndpoint)
    }

    /** 启动时恢复 pin；域名已被移出当前图源列表时由策略自动忽略。 */
    @Synchronized
    fun loadPinnedImgDomain() {
        try {
            val ctx = DevConfig.appContext ?: return
            val prefs = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            imgOverrideActive = false
            if (useFastestImgForAll) {
                pinnedImgDomain = null
                pinnedImgIp = null
                persistPinnedImgDomain(null, null)
            } else {
                pinnedImgDomain = prefs.getString("pinned_img_domain", null)?.takeIf { it.isNotBlank() }
                pinnedImgIp = prefs.getString("pinned_img_ip", null)?.takeIf { it.isNotBlank() }
            }
            notifyImgPinDisplayChanged()
        } catch (_: Exception) {}
    }

    @Volatile private var pinnedImgIp: String? = null

    private fun persistPinnedImgDomain(domain: String?, ip: String?) {
        try {
            val ctx = DevConfig.appContext ?: return
            val editor = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE).edit()
            if (domain == null) editor.remove("pinned_img_domain") else editor.putString("pinned_img_domain", domain)
            if (ip == null) editor.remove("pinned_img_ip") else editor.putString("pinned_img_ip", ip)
            editor.apply()
        } catch (_: Exception) {}
    }

    /** Return an exact user/auto-selected address only for the corresponding host. */
    fun preferredIp(domain: String): String? {
        lineIpFor(domain)?.let { return it }
        val target = imgRewriteTarget ?: return null
        if (!target.equals(domain, ignoreCase = true)) return null
        pinnedImgIp?.takeIf { pinnedImgDomain.equals(target, ignoreCase = true) }?.let { return it }
        if (!useFastestImgForAll) return null
        runtimeRecoveryImgEndpoint?.takeIf { pinnedImgDomain.isNullOrBlank() && it.domain.equals(target, true) }?.let { return it.ip }
        return fastestImgEndpoint?.takeIf { it.domain.equals(target, ignoreCase = true) }?.ip
    }

    /**
     * 统一决策入口：这次图片/封面请求应当改写到哪个图源域名；返回 null 表示**不接管**，
     * 保持服务端给的原始域名。所有改写点只读这一个属性，避免"手动选择被派生结果静默覆盖"
     * 再出现在某个新增调用点上。
     */
    val imgRewriteTarget: String?
        get() = ImgDomainPolicy.rewriteTarget(
            pinned = pinnedImgDomain,
            imgDomains = imgDomains,
            useFastestImgForAll = useFastestImgForAll,
            derivedFastest = fastestImgDomain,
            runtimeRecovery = runtimeRecoveryImgDomain,
        )

    // ---- 图域探活样本路径（真实封面相对路径，测速用）----
    /** 读取上次成功获取的探活图片相对路径（如 /static/upload/xxx.jpg）。 */
    fun loadProbeImgPath(): String? = try {
        val ctx = DevConfig.appContext ?: return null
        ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            .getString("probe_img_path", null)?.takeIf { it.startsWith("/") && it.length > 1 }
    } catch (_: Exception) {
        null
    }

    /** 持久化探活图片相对路径。 */
    fun saveProbeImgPath(path: String) {
        try {
            val ctx = DevConfig.appContext ?: return
            if (!path.startsWith("/") || path.length <= 1) return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putString("probe_img_path", path).apply()
        } catch (_: Exception) {}
    }

    // ---- 图域端点分级冷却（与 API 线路健康分离）----
    private data class ImgEndpointHealth(val until: Long, val deadCount: Int)
    private val imgEndpointHealth = java.util.concurrent.ConcurrentHashMap<DomainIp, ImgEndpointHealth>()
    private val imgEndpointDeadCounts = java.util.concurrent.ConcurrentHashMap<DomainIp, Int>()
    private val imgProbeLastAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val IMG_PROBE_INTERVAL_MS = 60L * 1000L
    private const val IMG_TRANSIENT_COOLDOWN_MS = 60L * 1000L
    private const val IMG_DEAD_BASE_COOLDOWN_MS = 30L * 1000L
    private const val IMG_DEAD_MAX_COOLDOWN_MS = 10L * 60L * 1000L

    private fun normalizedEndpoint(endpoint: DomainIp): DomainIp =
        endpoint.normalizedForComparison()

    /** Mark only transport failures; HTTP/content/decryption failures stay page-local. */
    fun markImgEndpointFailed(endpoint: DomainIp, kind: ImgEndpointFailure = ImgEndpointFailure.TRANSIENT) {
        val key = normalizedEndpoint(endpoint)
        val deadCount = if (kind == ImgEndpointFailure.DEAD) {
            synchronized(imgEndpointDeadCounts) {
                ((imgEndpointDeadCounts[key] ?: 0) + 1).coerceAtMost(6).also { imgEndpointDeadCounts[key] = it }
            }
        } else {
            imgEndpointDeadCounts[key] ?: 0
        }
        val cooldown = when (kind) {
            ImgEndpointFailure.SLOW, ImgEndpointFailure.TRANSIENT -> IMG_TRANSIENT_COOLDOWN_MS
            ImgEndpointFailure.DEAD -> (IMG_DEAD_BASE_COOLDOWN_MS * (1L shl (deadCount - 1).coerceAtMost(5)))
                .coerceAtMost(IMG_DEAD_MAX_COOLDOWN_MS)
        }
        imgEndpointHealth[key] = ImgEndpointHealth(System.currentTimeMillis() + cooldown, deadCount)
    }

    /** A fully validated image clears the exact endpoint's failure history. */
    fun markImgEndpointHealthy(endpoint: DomainIp) {
        val key = normalizedEndpoint(endpoint)
        imgEndpointHealth.remove(key)
        imgEndpointDeadCounts.remove(key)
    }

    /** Reserve one recovery probe per image-source domain per minute, using only a known-good endpoint. */
    fun reserveImgRecoveryProbe(domain: String): DomainIp? {
        val key = domain.lowercase(java.util.Locale.ROOT)
        val now = System.currentTimeMillis()
        val endpoint = rankedSuccessfulImgEndpoints().firstOrNull { it.domain.equals(domain, ignoreCase = true) }
            ?: return null
        val acquired = synchronized(imgProbeLastAt) {
            val previous = imgProbeLastAt[key]
            if (previous == null || now - previous >= IMG_PROBE_INTERVAL_MS) {
                imgProbeLastAt[key] = now
                true
            } else {
                false
            }
        }
        return endpoint.takeIf { acquired }
    }

    /** Whether one exact domain/IP endpoint is not in cooldown. */
    fun isImgEndpointAvailable(endpoint: DomainIp): Boolean {
        val key = normalizedEndpoint(endpoint)
        val health = imgEndpointHealth[key] ?: return true
        if (health.until <= System.currentTimeMillis()) {
            imgEndpointHealth.remove(key, health)
            return true
        }
        return false
    }

    /**
     * Build the per-request order: selected/manual endpoint first, then successful endpoints by
     * speed. A null entry means the current request host has no measured endpoint, so the caller
     * may resolve one IP for its first attempt before continuing with measured candidates.
     */
    fun imageRetryEndpoints(requestedDomain: String, excluded: Set<DomainIp> = emptySet()): List<DomainIp?> {
        val ranked = rankedSuccessfulImgEndpoints()
        val selectedIp = preferredIp(requestedDomain)
        val preferred = selectedIp?.let { DomainIp(requestedDomain, it) }
            ?: ranked.firstOrNull { it.domain.equals(requestedDomain, ignoreCase = true) }
        val ordered = if (preferred == null) ranked else ImageEndpointSelectionPolicy.retryOrder(preferred, ranked)
        val normalizedExcluded = excluded.mapTo(HashSet()) { it.normalizedForComparison() }
        val available = ordered.filterNotNull()
            .filterNot { it.normalizedForComparison() in normalizedExcluded }
            .filter(::isImgEndpointAvailable)
        if (available.isNotEmpty()) return available
        // An explicit manual domain pin may be attempted before it has a successful per-IP measurement.
        val domainHasMeasuredEndpoints = ranked.any { it.domain.equals(requestedDomain, ignoreCase = true) }
        if (pinnedImgDomain.equals(requestedDomain, ignoreCase = true) && selectedIp == null && !domainHasMeasuredEndpoints) {
            return listOf(null)
        }
        return emptyList()
    }

    /** 临时切换到下一条线路（不持久化）。 */
    @Synchronized
    fun selectNextLine() {
        if (lineHosts.isEmpty()) return
        lineIndex = (lineIndex + 1) % lineHosts.size
        selectedLineIp = null
        selectedLineDomain = null
        lineOverrideActive = false
        persistLineEndpoint(null, null)
        publishLineSelection()
    }

    // ---- 故障自动切换 ----
    @Volatile
    var consecutiveFailures: Int = 0
        private set

    @Volatile private var apiRecoveryInFlight = false

    @Volatile private var apiRecoveryNextAllowedAt = 0L
    private var apiFailureEndpoint: DomainIp? = null
    private var apiFailureRevision: Long? = null
    private var apiFailureNetworkRevision: Long? = null
    private val apiRetryAfter = RuntimeRetryAfterPolicy()

    @Synchronized
    fun recordApiConnectionFailure(
        method: String,
        error: java.io.IOException,
        requestEndpoint: DomainIp,
        requestRevision: Long,
        requestNetworkRevision: Long = RuntimeNetworkGate.revision,
    ) {
        if (!RuntimeNetworkGate.isOnline() || RuntimeNetworkGate.revision != requestNetworkRevision) {
            consecutiveFailures = 0
            apiFailureEndpoint = null
            apiFailureRevision = null
            apiFailureNetworkRevision = null
            return
        }
        if (!isCurrentApiRequest(requestEndpoint, requestRevision)) return
        if (apiRetryAfter.isBlocked(requestEndpoint)) return
        if (!RuntimeRecoveryPolicy.mayRecoverApi(method, error)) return
        if (apiFailureEndpoint != requestEndpoint || apiFailureRevision != requestRevision ||
            apiFailureNetworkRevision != requestNetworkRevision
        ) {
            consecutiveFailures = 0
            apiFailureEndpoint = requestEndpoint
            apiFailureRevision = requestRevision
            apiFailureNetworkRevision = requestNetworkRevision
        }
        if (++consecutiveFailures < 3 || !autoSelectEnabled || lineOverrideActive) return
        consecutiveFailures = 0
        val now = System.nanoTime() / 1_000_000L
        if (apiRecoveryInFlight || now < apiRecoveryNextAllowedAt) return
        val failedDomain = lineHost
        val failedIp = lineIpFor(failedDomain) ?: resolveIp(failedDomain).firstOrNull().orEmpty()
        val expectedRevision = configRevision
        val expectedPlatformRevision = StartupPlatformPolicy.state.value.revision
        val expectedNetworkRevision = RuntimeNetworkGate.revision
        apiRecoveryInFlight = true
        CoroutineScope(Dispatchers.IO).launch {
            var switched = false
            try {
                val endpoint = lastLineIpDelays.entries
                    .asSequence()
                    .filter { (endpoint, delay) ->
                        delay < Long.MAX_VALUE &&
                            !(endpoint.domain.equals(failedDomain, true) && endpoint.ip.equals(failedIp, true))
                    }
                    .sortedBy { it.value }
                    .map { it.key }
                    .firstOrNull()
                if (endpoint != null && RuntimeNetworkGate.isOnline() && RuntimeNetworkGate.revision == expectedNetworkRevision &&
                    NetworkModule.validateApiEndpoint(endpoint) &&
                    switchToValidatedApiEndpoint(endpoint, failedDomain, failedIp, expectedRevision, expectedPlatformRevision, expectedNetworkRevision)
                ) {
                    NetworkModule.rebuildApi("API connection recovery")
                    NetworkModule.cookieJar.syncToAllLines()
                    switched = true
                }
            } finally {
                synchronized(this@RouteManager) {
                    apiRecoveryInFlight = false
                    apiRecoveryNextAllowedAt = System.nanoTime() / 1_000_000L + 30_000L
                }
            }
        }
    }

    @Synchronized
    private fun switchToValidatedApiEndpoint(
        endpoint: DomainIp,
        expectedDomain: String,
        expectedIp: String,
        expectedRevision: Long,
        expectedPlatformRevision: Long,
        expectedNetworkRevision: Long,
    ): Boolean {
        if (!RuntimeNetworkGate.isOnline() || RuntimeNetworkGate.revision != expectedNetworkRevision ||
            !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) ||
            StartupPlatformPolicy.state.value.revision != expectedPlatformRevision ||
            configRevision != expectedRevision || !autoSelectEnabled || lineOverrideActive ||
            apiRetryAfter.isBlocked(DomainIp(expectedDomain, expectedIp)) ||
            !lineHost.equals(expectedDomain, true) ||
            !(lineIpFor(expectedDomain) ?: resolveIp(expectedDomain).firstOrNull().orEmpty()).equals(expectedIp, true)
        ) {
            return false
        }
        val index = lineIndexForDomain(endpoint.domain)
        if (index < 0) return false
        lineIndex = index
        selectedLineIp = endpoint.ip
        selectedLineDomain = hostFromLine(lineHosts[index])
        persistLineEndpoint(null, null)
        publishLineSelection()
        return true
    }

    private fun isCurrentApiRequest(endpoint: DomainIp, revision: Long): Boolean =
        StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA) && revision == configRevision &&
            endpoint.domain.equals(lineHost, true) &&
            endpoint.ip.equals(lineIpFor(lineHost) ?: resolveIp(lineHost).firstOrNull().orEmpty(), true)

    @Synchronized
    fun recordApiSuccess(requestEndpoint: DomainIp, requestRevision: Long) {
        if (isCurrentApiRequest(requestEndpoint, requestRevision)) consecutiveFailures = 0
    }

    /** An HTTP response is connectivity evidence, but 429 additionally blocks endpoint probes until Retry-After. */
    @Synchronized
    fun recordApiResponse(
        endpoint: DomainIp,
        requestRevision: Long,
        requestNetworkRevision: Long,
        code: Int,
        retryAfterHeader: String?,
    ) {
        if (!RuntimeNetworkGate.isOnline() || RuntimeNetworkGate.revision != requestNetworkRevision ||
            !isCurrentApiRequest(endpoint, requestRevision)
        ) {
            return
        }
        if (code == 429) apiRetryAfter.record(endpoint, retryAfterHeader)
        consecutiveFailures = 0
        apiFailureEndpoint = null
        apiFailureRevision = null
        apiFailureNetworkRevision = requestNetworkRevision
    }

    /** Set the DNS/IP rules from the active remote configuration. */
    @Synchronized
    fun setCustomRule(rule: Map<String, List<String>>) {
        val normalized = rule.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }
            .mapValues { (_, ips) -> ips.toList() }
        if (customRule == normalized) return
        customRule = normalized
        configRevision++
        clearSpeedTestResults()
        // Keep the reusable image cache until applyBaseLists has installed the complete
        // configuration; its image-only fingerprint ignores line and unrelated rule changes.
    }

    /**
     * 应用显式的配置线路与图源列表，精确替换当前集合；不得合并内置、本地缓存或运行时学习项。
     * 若当前选中线路/图源仍存在则保留，否则回到首项并清理失效 pin。
     */
    @Synchronized
    fun applyBaseLists(lineUrls: List<String>, sourceDomains: List<String>, remoteVersion: Int? = null) {
        val lists = RouteListPolicy.replace(lineUrls, sourceDomains)
        activeRemoteConfigVersion = remoteVersion
        val prefsLoaded = routePreferencesLoaded
        val oldLine = lineHosts.getOrNull(lineIndex)
        val savedSelection = runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
        }.getOrNull()
        val oldDomain = selectedLineDomain ?: oldLine?.let(::hostFromLine)
            ?: savedSelection?.getString("selected_line_domain", null)
        val oldIp = selectedLineIp ?: savedSelection?.getString("selected_line_ip", null)
        val newLines = lists.lines
        lineHosts = newLines
        val keepTemporaryLine = prefsLoaded && autoSelectEnabled && lineOverrideActive
        val keptLine = oldDomain?.let { domain -> newLines.indexOfFirst { hostFromLine(it).equals(domain, true) } }
            ?.takeIf { it >= 0 }
        when {
            keepTemporaryLine && keptLine != null -> {
                lineIndex = keptLine
                selectedLineIp = oldIp
                selectedLineDomain = hostFromLine(newLines[keptLine])
                persistLineEndpoint(null, null)
            }
            prefsLoaded && autoSelectEnabled -> {
                // A changed candidate list invalidates old rankings; use a safe default until the new test completes.
                lineIndex = 0
                selectedLineIp = null
                selectedLineDomain = null
                lineOverrideActive = false
                persistLineEndpoint(null, null)
            }
            prefsLoaded && !autoSelectEnabled && keptLine != null -> {
                lineIndex = keptLine
                selectedLineIp = oldIp
                selectedLineDomain = hostFromLine(newLines[keptLine])
                persistLineEndpoint(selectedLineDomain, selectedLineIp)
            }
            !prefsLoaded && keptLine != null && oldIp != null -> {
                // Startup configuration is applied before the saved auto/manual preference is loaded.
                lineIndex = keptLine
                selectedLineIp = oldIp
                selectedLineDomain = hostFromLine(newLines[keptLine])
                persistLineEndpoint(selectedLineDomain, selectedLineIp)
            }
            !prefsLoaded && keptLine != null -> {
                lineIndex = keptLine
                selectedLineIp = null
                selectedLineDomain = null
            }
            else -> {
                lineIndex = 0
                selectedLineIp = null
                selectedLineDomain = null
                lineOverrideActive = false
                if (prefsLoaded) persistLineEndpoint(null, null)
            }
        }
        publishLineSelection()
        if (prefsLoaded) saveLastFastestLine(0)
        setBaselineImgDomains(lists.sources)
        clearSpeedTestResults()
        configRevision++
        refreshReusableImageSpeedTestSnapshot()
        // Persisted results are inert until reuse validates the final version, pools and rules.
    }

    internal fun invalidateReusableImageSpeedTestSnapshot() {
        runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?.edit()?.remove(REUSABLE_IMAGE_SNAPSHOT_KEY)?.apply()
        }
        _reusableImageSpeedTestSnapshot.value = null
    }

    /** Remove speed rows for endpoints that may have been removed by the latest configuration. */
    @Synchronized
    fun clearSpeedTestResults() {
        runtimeRecoveryImgDomain = null
        runtimeRecoveryImgEndpoint = null
        lastLineDelays = emptyMap()
        lastImgDelays = emptyMap()
        lastLineIpDelays = emptyMap()
        lastImgIpDelays = emptyMap()
        lastImgIpVerdicts = emptyMap()
        _speedTestSnapshot.value = SpeedTestSnapshot()
        _completedSpeedTestSnapshot.value = SpeedTestSnapshot()
    }

    /** 设置 SNI 绕过域名列表（由 lines+sources 派生，调用方负责计算） */
    fun setSniDomains(domains: Set<String>) {
        sniDomains = domains
    }

    /** 判断某 host 是否需要 SNI 绕过 */
    fun isSniBypass(host: String): Boolean = sniDomains.contains(host)

    /** Resolve only from the active remote rule; an unlisted host uses system DNS. */
    fun resolveIp(host: String): List<String> =
        customRule.entries.firstOrNull { it.key.equals(host, ignoreCase = true) }?.value.orEmpty()
}
