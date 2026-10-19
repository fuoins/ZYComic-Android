package com.zycomic.app.net

import android.content.Context
import android.content.SharedPreferences
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** JM 的运行时网络状态；与 Manwa 的 RouteManager、Cookie、测速和日志完全分开。 */
object JmRouteManager {

    data class State(
        val lines: List<String> = OFFLINE_LINES,
        val imageDomains: List<String> = DEFAULT_IMAGE_DOMAINS,
        val lineIndex: Int = 0,
        val imageDomain: String = DEFAULT_IMAGE_DOMAINS.first(),
        val autoSelect: Boolean = true,
        val usingFallbackLines: Boolean = true,
        val remoteVersion: Int? = null,
    )

    data class SpeedTestSnapshot(
        val lineDelaysMs: Map<String, Long> = emptyMap(),
        val imageDelaysMs: Map<String, Long> = emptyMap(),
        val startedAtEpochMs: Long? = null,
        val completedAtEpochMs: Long? = null,
        val remoteVersion: Int? = null,
        val configFingerprint: String? = null,
        val imageConfigFingerprint: String? = null,
        val imageNetworkPolicy: String? = null,
        val imageResultsReused: Boolean = false,
        val lineIpDelaysMs: Map<DomainIp, Long> = emptyMap(),
        val imageIpDelaysMs: Map<DomainIp, Long> = emptyMap(),
        val imageIpVerdicts: Map<DomainIp, JmSpeedTester.ImgVerdict> = emptyMap(),
    )

    data class LogEntry(
        val atEpochMs: Long,
        val kind: String,
        val host: String,
        val message: String,
    )

    data class EndpointSelectionState(val revision: Long, val line: DomainIp?, val image: DomainIp?)

    private const val PREFS_NAME = "zycomic_jm_route"
    private const val KEY_SNAPSHOT = "speed_test_snapshot"
    private const val KEY_IMAGE_SNAPSHOT = "image_speed_test_snapshot"
    private const val KEY_LOGS = "route_logs"
    private const val MAX_LOGS = 200
    private const val API_FAILURE_THRESHOLD = 3
    private const val BASE_COOLDOWN_MS = 30_000L
    private const val MAX_COOLDOWN_MS = 10 * 60_000L

    /** 官方 App 报告中的离线兜底 4 条；不包含 Venera 社区镜像。 */
    val OFFLINE_LINES = listOf(
        "https://www.cdnaspa.vip",
        "https://www.cdnaspa.club",
        "https://www.cdnplaystation6.vip",
        "https://www.cdnplaystation6.cc",
    )

    /** sources2 为空或整池不可用时的官方静态 fallback（无 msp1）。 */
    val DEFAULT_IMAGE_DOMAINS = JmImagePolicy.DEFAULT_IMAGE_DOMAINS

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _speedTestSnapshot = MutableStateFlow(SpeedTestSnapshot())
    val speedTestSnapshot: StateFlow<SpeedTestSnapshot> = _speedTestSnapshot.asStateFlow()
    private val _endpointSelection = MutableStateFlow(EndpointSelectionState(0, null, null))
    val endpointSelection: StateFlow<EndpointSelectionState> = _endpointSelection.asStateFlow()

    @Volatile private var reusableImageSnapshot: SpeedTestSnapshot? = null
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Volatile private var lineRouteRules: Map<String, List<String>> = emptyMap()

    @Volatile private var imageRouteRules: Map<String, List<String>> = emptyMap()

    @Volatile private var manualLineEndpoint: DomainIp? = null

    @Volatile private var manualImageEndpoint: DomainIp? = null

    @Volatile private var lastLineEndpoint: DomainIp? = null

    @Volatile private var lastImageEndpoint: DomainIp? = null
    private val failedLineEndpoints = ConcurrentHashMap.newKeySet<DomainIp>()
    private val failedImageEndpoints = ConcurrentHashMap.newKeySet<DomainIp>()

    @Volatile private var configRevision: Long = 0L

    @Volatile private var jmSniDomains: Set<String> = emptySet()

    @Volatile private var consecutiveFailures = 0
    private data class Health(val failures: Int, val untilEpochMs: Long)
    private val lineHealth = ConcurrentHashMap<String, Health>()
    private val imageHealth = ConcurrentHashMap<String, Health>()

    val lineHosts: List<String> get() = _state.value.lines
    val imageDomains: List<String> get() = _state.value.imageDomains
    val baseUrl: String get() = lineHosts.getOrNull(_state.value.lineIndex) ?: lineHosts.firstOrNull().orEmpty()
    val lineHost: String get() = hostFromUrl(baseUrl)
    val activeImageDomain: String get() = _state.value.imageDomain
    /** Persisted manual image selection, if it still belongs to the active candidate pool. */
    val pinnedImageDomain: String?
        get() = prefs()?.getString("selected_image", null)?.takeIf { pin -> imageDomains.any { it.equals(pin, true) } }
    val selectedLineIndex: Int get() = _state.value.lineIndex
    val isAutoSelectEnabled: Boolean get() = _state.value.autoSelect
    val isUsingFallbackLines: Boolean get() = _state.value.usingFallbackLines
    val sniDomains: Set<String> get() = jmSniDomains

    fun setSniDomains(domains: Set<String>) {
        jmSniDomains = domains.map { it.lowercase(Locale.ROOT) }.toSet()
    }

    /** 从已验证配置应用 JM 的可选 host 列表；绝不写入 Manwa 的 RouteManager。 */
    @Synchronized
    fun applyRemoteConfig(
        lines: List<String>,
        sources: List<String>,
        rules: Map<String, List<String>>,
        version: Int?,
    ) {
        val normalizedLines = normalizeLines(lines)
        val normalizedSources = normalizeHosts(sources)
        val useFallback = normalizedLines.isEmpty()
        val activeLines = if (useFallback) OFFLINE_LINES else normalizedLines
        val activeSources = normalizedSources.ifEmpty { DEFAULT_IMAGE_DOMAINS }
        val apiHosts = normalizedLines.map(::hostFromUrl).map { it.lowercase(Locale.ROOT) }.toSet()
        val imageHosts = normalizedSources.map { it.lowercase(Locale.ROOT) }.toSet()
        lineRouteRules = rules.entries
            .filter { (host, _) -> host.lowercase(Locale.ROOT) in apiHosts }
            .associate { (host, ips) -> host.lowercase(Locale.ROOT) to ips.toList() }
        imageRouteRules = rules.entries
            .filter { (host, _) -> host.lowercase(Locale.ROOT) in imageHosts }
            .associate { (host, ips) -> host.lowercase(Locale.ROOT) to ips.toList() }
        jmSniDomains = (activeLines.map(::hostFromUrl) + activeSources + lineRouteRules.keys + imageRouteRules.keys)
            .map { it.lowercase(Locale.ROOT) }
            .filter { it != "dns.google" }
            .toSet()

        val old = _state.value
        val poolsChanged = old.lines.map { hostFromUrl(it).lowercase(Locale.ROOT) } !=
            activeLines.map { hostFromUrl(it).lowercase(Locale.ROOT) } ||
            old.imageDomains.map { it.lowercase(Locale.ROOT) } != activeSources.map { it.lowercase(Locale.ROOT) }
        val prefs = prefs()
        val auto = prefs?.getBoolean("auto_select", true) ?: old.autoSelect
        val savedLine = prefs?.getString("selected_line", null)
        val savedImage = prefs?.getString("selected_image", null)
        val savedLineEndpoint = storedEndpoint(prefs?.getString("manual_line_endpoint_domain", null), prefs?.getString("manual_line_endpoint_ip", null), activeLines.map(::hostFromUrl))
        val savedImageEndpoint = storedEndpoint(prefs?.getString("manual_image_endpoint_domain", null), prefs?.getString("manual_image_endpoint_ip", null), activeSources)
        manualLineEndpoint = savedLineEndpoint
        manualImageEndpoint = savedImageEndpoint
        if (lastLineEndpoint == null) {
            lastLineEndpoint = storedEndpoint(
                prefs?.getString("selected_line_endpoint_domain", null),
                prefs?.getString("selected_line_endpoint_ip", null),
                activeLines.map(::hostFromUrl),
            )
        }
        if (lastImageEndpoint == null) {
            lastImageEndpoint = storedEndpoint(
                prefs?.getString("selected_image_endpoint_domain", null),
                prefs?.getString("selected_image_endpoint_ip", null),
                activeSources,
            )
        }
        if (lastLineEndpoint?.let { endpoint -> activeLines.none { hostFromUrl(it).equals(endpoint.domain, true) } } == true ||
            (lastLineEndpoint == null && !prefs?.getString("selected_line_endpoint_domain", null).isNullOrBlank())
        ) {
            lastLineEndpoint = null
            prefs?.edit()?.remove("selected_line_endpoint_domain")?.remove("selected_line_endpoint_ip")?.apply()
        }
        if (lastImageEndpoint?.let { endpoint -> activeSources.none { it.equals(endpoint.domain, true) } } == true ||
            (lastImageEndpoint == null && !prefs?.getString("selected_image_endpoint_domain", null).isNullOrBlank())
        ) {
            lastImageEndpoint = null
            prefs?.edit()?.remove("selected_image_endpoint_domain")?.remove("selected_image_endpoint_ip")?.apply()
        }
        if (savedLineEndpoint == null) prefs?.edit()?.remove("manual_line_endpoint_domain")?.remove("manual_line_endpoint_ip")?.apply()
        if (savedImageEndpoint == null) prefs?.edit()?.remove("manual_image_endpoint_domain")?.remove("manual_image_endpoint_ip")?.apply()
        val lineIndex = when {
            auto -> 0
            savedLine != null -> activeLines.indexOfFirst { hostFromUrl(it).equals(savedLine, true) }.coerceAtLeast(0)
            else -> old.lines.getOrNull(old.lineIndex)?.let { prior -> activeLines.indexOfFirst { hostFromUrl(it).equals(hostFromUrl(prior), true) } }
                ?.takeIf { it >= 0 } ?: 0
        }
        val matchedSavedImage = savedImage?.let { saved -> activeSources.firstOrNull { it.equals(saved, true) } }
        val imageDomain = matchedSavedImage
            ?: activeSources.firstOrNull { it.equals(old.imageDomain, true) }
            ?: activeSources.first()

        _state.value = State(
            lines = activeLines,
            imageDomains = activeSources,
            lineIndex = lineIndex,
            imageDomain = imageDomain,
            autoSelect = auto,
            usingFallbackLines = useFallback,
            remoteVersion = version,
        )
        prefs?.edit()
            ?.putString("configured_lines", JSONArray(activeLines).toString())
            ?.putString("configured_images", JSONArray(activeSources).toString())
            ?.putBoolean("using_fallback", useFallback)
            ?.putInt("remote_version", version ?: -1)
            ?.apply {
                if (!auto) putString("selected_line", hostFromUrl(activeLines[lineIndex]))
                if (savedImage != null && matchedSavedImage == null) remove("selected_image")
            }
            ?.apply()
        lineHealth.clear()
        imageHealth.clear()
        consecutiveFailures = 0
        configRevision++
        val updatedFingerprint = currentSpeedTestConfigFingerprint()
        if (_speedTestSnapshot.value.configFingerprint != updatedFingerprint ||
            !isSpeedTestSnapshotValid(_speedTestSnapshot.value)
        ) {
            _speedTestSnapshot.value = SpeedTestSnapshot(remoteVersion = version)
        }
        failedLineEndpoints.clear()
        failedImageEndpoints.clear()
        persistSnapshot()
        if (!poolsChanged && auto) applyFastestLine()
        JmNetworkModule.rebindApiEndpoint()
        JmNetworkModule.rebindImageEndpoint()
        publishEndpointSelection()
        record("config", hostFromUrl(activeLines.first()), if (useFallback) "使用 JM 官方内置兜底线路" else "应用 JM 远程线路配置 v${version ?: "?"}")
    }

    /** 恢复单独存储的 JM 线路、图源偏好和最近测速快照。 */
    @Synchronized
    fun loadPreferences() {
        val prefs = prefs() ?: return
        val current = _state.value
        val restoredLines = decodeStringList(prefs.getString("configured_lines", null))
            .let(::normalizeLines).ifEmpty { current.lines }
        val restoredImages = decodeStringList(prefs.getString("configured_images", null))
            .let(::normalizeHosts).ifEmpty { current.imageDomains }
        val auto = prefs.getBoolean("auto_select", true)
        val savedLine = prefs.getString("selected_line", null)
        val savedImage = prefs.getString("selected_image", null)
        val idx = if (auto) {
            prefs.getString("selected_line_endpoint_domain", null)?.let { target ->
                restoredLines.indexOfFirst { hostFromUrl(it).equals(target, true) }.takeIf { it >= 0 }
            } ?: 0
        } else {
            savedLine?.let { target ->
                restoredLines.indexOfFirst { hostFromUrl(it).equals(target, true) }.takeIf { it >= 0 }
            } ?: 0
        }
        val img = (savedImage ?: prefs.getString("selected_image_endpoint_domain", null))?.let { target -> restoredImages.firstOrNull { it.equals(target, true) } }
            ?: restoredImages.first()
        _state.value = current.copy(
            lines = restoredLines,
            imageDomains = restoredImages,
            lineIndex = idx,
            imageDomain = img,
            autoSelect = auto,
            usingFallbackLines = prefs.getBoolean("using_fallback", current.usingFallbackLines),
            remoteVersion = prefs.getInt("remote_version", -1).takeIf { it >= 0 },
        )
        configRevision++
        _speedTestSnapshot.value = decodeSnapshot(prefs.getString(KEY_SNAPSHOT, null))
        reusableImageSnapshot = decodeSnapshot(prefs.getString(KEY_IMAGE_SNAPSHOT, null))
        failedLineEndpoints.clear()
        failedLineEndpoints.addAll(decodeFailedEndpoints(prefs.getString("failed_line_endpoints", null), restoredLines.map(::hostFromUrl)))
        failedImageEndpoints.clear()
        failedImageEndpoints.addAll(decodeFailedEndpoints(prefs.getString("failed_image_endpoints", null), restoredImages))
        lastLineEndpoint = storedEndpoint(
            prefs.getString("selected_line_endpoint_domain", null),
            prefs.getString("selected_line_endpoint_ip", null),
            restoredLines.map(::hostFromUrl),
        )
        lastImageEndpoint = storedEndpoint(
            prefs.getString("selected_image_endpoint_domain", null),
            prefs.getString("selected_image_endpoint_ip", null),
            restoredImages,
        )
        manualLineEndpoint = storedEndpoint(
            prefs.getString("manual_line_endpoint_domain", null),
            prefs.getString("manual_line_endpoint_ip", null),
            restoredLines.map(::hostFromUrl),
        )
        manualImageEndpoint = storedEndpoint(
            prefs.getString("manual_image_endpoint_domain", null),
            prefs.getString("manual_image_endpoint_ip", null),
            restoredImages,
        )
        _logs.value = decodeLogs(prefs.getString(KEY_LOGS, null))
        publishEndpointSelection()
    }

    /** 选择 JM API 线路并切换到持久化手动模式。 */
    @Synchronized
    fun selectLine(index: Int) {
        val current = _state.value
        if (current.lines.isEmpty()) return
        val safe = index.coerceIn(0, current.lines.lastIndex)
        val host = hostFromUrl(current.lines[safe])
        _state.value = current.copy(lineIndex = safe, autoSelect = false)
        manualLineEndpoint = currentLineEndpoint(host)
        prefs()?.edit()
            ?.putBoolean("auto_select", false)
            ?.putString("selected_line", host)
            ?.also { editor ->
                persistEndpoint(editor, "manual_line_endpoint", manualLineEndpoint)
                persistEndpoint(editor, "selected_line_endpoint", manualLineEndpoint)
            }
            ?.apply()
        consecutiveFailures = 0
        JmNetworkModule.rebindApiEndpoint()
        publishEndpointSelection()
        record("line", host, "手动选择 JM API 线路${manualLineEndpoint?.let { " · ${it.ip}" }.orEmpty()}")
    }

    /** Exactly one measured successful IP for a lines2 host, honoring an available manual pin. */
    fun currentLineEndpoint(domain: String = lineHost): DomainIp? {
        val snapshot = _speedTestSnapshot.value
        val selected = JmEndpointSelectionPolicy.select(
            domain = domain,
            configuredDomains = lineHosts.map(::hostFromUrl),
            delays = snapshot.lineIpDelaysMs,
            successful = { true },
            manualEndpoint = manualLineEndpoint,
            excluded = failedLineEndpoints,
        )
        if (selected != null) return selected
        // Once a fresh result exists for this host it is authoritative; do not retry failed IPs via DNS rules.
        if (snapshot.lineIpDelaysMs.keys.any { it.domain.equals(domain, true) }) return null
        manualLineEndpoint?.takeIf { it.domain.equals(domain, true) && !isFailed(it, failedLineEndpoints) }?.let { return it }
        lastLineEndpoint?.takeIf { it.domain.equals(domain, true) && !isFailed(it, failedLineEndpoints) }?.let { return it }
        return lineRouteRules.entries.firstOrNull { it.key.equals(domain, true) }?.value
            ?.firstOrNull { isLiteralIp(it) && !isFailed(DomainIp(domain, it), failedLineEndpoints) }
            ?.let { DomainIp(domain, it) }
    }

    val selectedLineEndpoint: DomainIp? get() = currentLineEndpoint(lineHost)

    /** Pin one exact, currently successful lines2 endpoint and persist domain+IP together. */
    @Synchronized
    fun selectLineEndpoint(domain: String, ip: String): Boolean {
        val host = lineHosts.map(::hostFromUrl).firstOrNull { it.equals(domain, true) } ?: return false
        val endpoint = _speedTestSnapshot.value.lineIpDelaysMs.keys.firstOrNull {
            it.domain.equals(host, true) && it.ip.equals(ip.trim(), true) &&
                (_speedTestSnapshot.value.lineIpDelaysMs[it] ?: Long.MAX_VALUE) < Long.MAX_VALUE && it !in failedLineEndpoints
        } ?: return false
        val index = _state.value.lines.indexOfFirst { hostFromUrl(it).equals(host, true) }
        if (index < 0) return false
        manualLineEndpoint = endpoint
        _state.value = _state.value.copy(lineIndex = index, autoSelect = false)
        prefs()?.edit()?.putBoolean("auto_select", false)?.putString("selected_line", host)
            ?.also {
                persistEndpoint(it, "manual_line_endpoint", endpoint)
                persistEndpoint(it, "selected_line_endpoint", endpoint)
            }?.apply()
        JmNetworkModule.rebindApiEndpoint()
        publishEndpointSelection()
        record("line", host, "手动固定 JM API 端点 ${endpoint.ip}")
        return true
    }

    /** Pin a successful sources2 row; reader markers and transport DNS use this same state. */
    @Synchronized
    fun selectImageEndpoint(domain: String, ip: String): Boolean {
        val host = imageDomains.firstOrNull { it.equals(domain, true) } ?: return false
        val endpoint = _speedTestSnapshot.value.imageIpDelaysMs.keys.firstOrNull {
            it.domain.equals(host, true) && it.ip.equals(ip.trim(), true) &&
                (_speedTestSnapshot.value.imageIpDelaysMs[it] ?: Long.MAX_VALUE) < Long.MAX_VALUE &&
                _speedTestSnapshot.value.imageIpVerdicts[it] == JmSpeedTester.ImgVerdict.OK && it !in failedImageEndpoints
        } ?: return false
        manualImageEndpoint = endpoint
        _state.value = _state.value.copy(imageDomain = host)
        prefs()?.edit()?.putString("selected_image", host)
            ?.also {
                persistEndpoint(it, "manual_image_endpoint", endpoint)
                persistEndpoint(it, "selected_image_endpoint", endpoint)
            }?.apply()
        JmNetworkModule.rebindImageEndpoint()
        publishEndpointSelection()
        record("image", host, "手动固定 JM 图源端点 ${endpoint.ip}")
        return true
    }

    @Synchronized
    fun setAutoSelectEnabled(enabled: Boolean) {
        val current = _state.value
        _state.value = current.copy(autoSelect = enabled)
        prefs()?.edit()?.putBoolean("auto_select", enabled)?.apply()
        if (enabled) {
            manualLineEndpoint = null
            prefs()?.edit()?.remove("manual_line_endpoint_domain")?.remove("manual_line_endpoint_ip")?.apply()
            applyFastestLine()
        } else {
            manualLineEndpoint = currentLineEndpoint(lineHost)
            prefs()?.edit()?.putString("selected_line", hostFromUrl(baseUrl))
                ?.also {
                    persistEndpoint(it, "manual_line_endpoint", manualLineEndpoint)
                    persistEndpoint(it, "selected_line_endpoint", manualLineEndpoint)
                }?.apply()
        }
        JmNetworkModule.rebindApiEndpoint()
        record("line", lineHost, if (enabled) "启用 JM 自动选线" else "切换 JM 手动选线")
    }

    /** 手动固定 JM 图床；空值清除固定并恢复测速最快/首选图床。 */
    @Synchronized
    fun selectImageDomain(domain: String?) {
        val current = _state.value
        val selected = domain?.let { value -> current.imageDomains.firstOrNull { it.equals(value, true) } }
        if (selected == null) {
            manualImageEndpoint = null
        } else if (manualImageEndpoint?.domain?.equals(selected, true) != true) {
            manualImageEndpoint = null
        }
        val resolved = selected ?: fastestImageDomain() ?: current.imageDomains.first()
        _state.value = current.copy(imageDomain = resolved)
        val editor = prefs()?.edit()
        if (selected == null) {
            editor?.remove("selected_image")
            editor?.remove("manual_image_endpoint_domain")?.remove("manual_image_endpoint_ip")
            manualImageEndpoint = null
        } else {
            editor?.putString("selected_image", selected)
        }
        if (selected == null) persistEndpoint(editor, "selected_image_endpoint", currentImageEndpoint(resolved))
        editor?.also { persistEndpoint(it, "selected_image_endpoint", currentImageEndpoint(resolved)) }?.apply()
        JmNetworkModule.rebindImageEndpoint()
        publishEndpointSelection()
        record("image", resolved, if (selected == null) "恢复 JM 图床自动选择" else "手动选择 JM 图床${currentImageEndpoint(resolved)?.let { " · ${it.ip}" }.orEmpty()}")
    }

    @Synchronized
    fun applySnapshot(snapshot: SpeedTestSnapshot, expectedPlatformRevision: Long? = null): Boolean {
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.JM) ||
            (expectedPlatformRevision != null && StartupPlatformPolicy.state.value.revision != expectedPlatformRevision) ||
            !isSpeedTestSnapshotValid(snapshot)
        ) {
            return false
        }
        _speedTestSnapshot.value = snapshot
        failedLineEndpoints.clear()
        snapshot.lineIpDelaysMs.filterValues { it >= Long.MAX_VALUE }.keys.forEach { failedLineEndpoints += it.normalizedForComparison() }
        failedImageEndpoints.clear()
        snapshot.imageIpDelaysMs.forEach { (endpoint, delay) ->
            if (delay >= Long.MAX_VALUE || snapshot.imageIpVerdicts[endpoint] != JmSpeedTester.ImgVerdict.OK) {
                failedImageEndpoints += endpoint.normalizedForComparison()
            }
        }
        lastLineEndpoint?.let { last -> if (snapshot.lineIpDelaysMs.entries.any { it.key.normalizedForComparison() == last.normalizedForComparison() && it.value >= Long.MAX_VALUE }) lastLineEndpoint = null }
        lastImageEndpoint?.let { last -> if (snapshot.imageIpDelaysMs.entries.any { it.key.normalizedForComparison() == last.normalizedForComparison() && (it.value >= Long.MAX_VALUE || snapshot.imageIpVerdicts[it.key] != JmSpeedTester.ImgVerdict.OK) }) lastImageEndpoint = null }
        if (manualLineEndpoint?.let { selected -> snapshot.lineIpDelaysMs.entries.none { it.key.normalizedForComparison() == selected.normalizedForComparison() && it.value < Long.MAX_VALUE } } == true) {
            manualLineEndpoint = null
            prefs()?.edit()?.remove("manual_line_endpoint_domain")?.remove("manual_line_endpoint_ip")?.apply()
        }
        if (manualImageEndpoint?.let { selected -> snapshot.imageIpDelaysMs.entries.none { (endpoint, delay) -> endpoint.normalizedForComparison() == selected.normalizedForComparison() && delay < Long.MAX_VALUE && snapshot.imageIpVerdicts[endpoint] == JmSpeedTester.ImgVerdict.OK } } == true) {
            manualImageEndpoint = null
            prefs()?.edit()?.remove("manual_image_endpoint_domain")?.remove("manual_image_endpoint_ip")?.apply()
        }
        if (isReusableImageSnapshotValid(snapshot)) {
            reusableImageSnapshot = snapshot
            prefs()?.edit()?.putString(KEY_IMAGE_SNAPSHOT, encodeSnapshot(snapshot))?.apply()
        }
        persistSnapshot()
        if (_state.value.autoSelect) applyFastestLine()
        if (pinnedImageDomain == null) {
            fastestImageDomain()?.let { domain -> _state.value = _state.value.copy(imageDomain = domain) }
        }
        persistCurrentEndpoints()
        JmNetworkModule.rebindApiEndpoint()
        JmNetworkModule.rebindImageEndpoint()
        publishEndpointSelection()
        return true
    }

    /** True only when the persisted snapshot exactly matches today's active JM configuration. */
    fun reuseValidResults(expectedPlatformRevision: Long? = null): Boolean {
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.JM) ||
            (expectedPlatformRevision != null && StartupPlatformPolicy.state.value.revision != expectedPlatformRevision)
        ) {
            return false
        }
        val snapshot = _speedTestSnapshot.value
        if (!isSpeedTestSnapshotValid(snapshot)) return false
        return applySnapshot(snapshot, expectedPlatformRevision)
    }

    /** API pools/versions do not invalidate otherwise identical image-host/IP results. */
    fun currentImageConfigFingerprint(): String {
        val text = buildString {
            append("probe=").append(JmSpeedProbeProtocol.IMAGE_PATH).append('\n')
            imageDomains.forEach { host ->
                append(host.lowercase(Locale.ROOT)).append('=')
                resolveImageIp(host).sorted().forEach { append(it).append(',') }
                append('\n')
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun currentImageNetworkPolicy(): String =
        "${DevConfig.isFollowSystemVpnEnabled()}|${NetworkModule.isSystemVpnActive()}"

    fun reusableImageSnapshotOrNull(): SpeedTestSnapshot? =
        reusableImageSnapshot?.takeIf(::isReusableImageSnapshotValid)

    private fun isReusableImageSnapshotValid(snapshot: SpeedTestSnapshot): Boolean =
        snapshot.imageIpDelaysMs.isNotEmpty() &&
            snapshot.imageIpDelaysMs.keys == snapshot.imageIpVerdicts.keys &&
            snapshot.imageIpDelaysMs.keys.all { endpoint -> imageDomains.any { it.equals(endpoint.domain, true) } } &&
            snapshot.imageIpDelaysMs.all { (endpoint, delay) ->
                delay >= 0L && when (snapshot.imageIpVerdicts[endpoint]) {
                    JmSpeedTester.ImgVerdict.OK -> delay < Long.MAX_VALUE
                    JmSpeedTester.ImgVerdict.NO_IMAGE, JmSpeedTester.ImgVerdict.UNREACHABLE -> delay == Long.MAX_VALUE
                    null -> false
                }
            } &&
            JmSpeedTestValidityPolicy.canReuseImages(
                snapshot.imageConfigFingerprint,
                currentImageConfigFingerprint(),
                snapshot.imageNetworkPolicy,
                currentImageNetworkPolicy(),
                snapshot.imageDelaysMs,
                imageDomains.map { it.lowercase(Locale.ROOT) }.toSet(),
                snapshot.startedAtEpochMs,
                snapshot.completedAtEpochMs,
            )

    fun currentSpeedTestConfigRevision(): Long = configRevision

    fun currentSpeedTestConfigFingerprint(): String {
        val state = _state.value
        val text = buildString {
            append(JmSpeedProbeProtocol.LINE_PATH).append('|').append(JmSpeedProbeProtocol.IMAGE_PATH).append('\n')
            append(state.remoteVersion ?: "builtin").append('\n')
            state.lines.forEach { append(hostFromUrl(it).lowercase(Locale.ROOT)).append('\n') }
            append("--images--\n")
            state.imageDomains.forEach { append(it.lowercase(Locale.ROOT)).append('\n') }
            append("--rules--\n")
            append("--api-rules--\n")
            lineRouteRules.entries.sortedBy { it.key.lowercase(Locale.ROOT) }.forEach { (host, ips) ->
                append(host.lowercase(Locale.ROOT)).append('=')
                ips.sorted().forEach { append(it).append(',') }
                append('\n')
            }
            append("--image-rules--\n")
            imageRouteRules.entries.sortedBy { it.key.lowercase(Locale.ROOT) }.forEach { (host, ips) ->
                append(host.lowercase(Locale.ROOT)).append('=')
                ips.sorted().forEach { append(it).append(',') }
                append('\n')
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isSpeedTestSnapshotValid(snapshot: SpeedTestSnapshot): Boolean {
        val current = _state.value
        val currentLines = current.lines.map { hostFromUrl(it).lowercase(Locale.ROOT) }.toSet()
        val currentImages = current.imageDomains.map { it.lowercase(Locale.ROOT) }.toSet()
        return JmSpeedTestValidityPolicy.isValid(
            snapshotVersion = snapshot.remoteVersion,
            currentVersion = current.remoteVersion,
            snapshotFingerprint = snapshot.configFingerprint,
            currentFingerprint = currentSpeedTestConfigFingerprint(),
            snapshotLineCandidates = snapshot.lineDelaysMs.keys,
            currentLineCandidates = currentLines,
            snapshotImageCandidates = snapshot.imageDelaysMs.keys,
            currentImageCandidates = currentImages,
            lineDelays = snapshot.lineDelaysMs,
            imageDelays = snapshot.imageDelaysMs,
            startedAtEpochMs = snapshot.startedAtEpochMs,
            completedAtEpochMs = snapshot.completedAtEpochMs,
        )
    }

    @Synchronized
    private fun applyFastestLine() {
        if (!_state.value.autoSelect) return
        val fastest = _speedTestSnapshot.value.lineDelaysMs.entries
            .filter { it.value < Long.MAX_VALUE && isLineAvailable(it.key) && currentLineEndpoint(it.key) != null }
            .minByOrNull { it.value }?.key ?: return
        val index = _state.value.lines.indexOfFirst { hostFromUrl(it).equals(fastest, true) }
        if (index >= 0) {
            _state.value = _state.value.copy(lineIndex = index)
            val host = hostFromUrl(fastest)
            val endpoint = currentLineEndpoint(host)
            val editor = prefs()?.edit()
            persistEndpoint(editor, "selected_line_endpoint", endpoint)
            editor?.apply()
            publishEndpointSelection()
        }
    }

    private fun fastestImageDomain(): String? = _speedTestSnapshot.value.imageDelaysMs.entries
        .filter { it.value < Long.MAX_VALUE && isImageAvailable(it.key) && currentImageEndpoint(it.key) != null }
        .minByOrNull { it.value }
        ?.key?.let { measured -> imageDomains.firstOrNull { it.equals(measured, true) } }

    fun rememberLineEndpoint(endpoint: DomainIp) {
        if (lineHosts.none { hostFromUrl(it).equals(endpoint.domain, true) }) return
        lastLineEndpoint = endpoint
        prefs()?.edit()?.also { persistEndpoint(it, "selected_line_endpoint", endpoint) }?.apply()
        publishEndpointSelection()
    }

    fun rememberImageEndpoint(endpoint: DomainIp) {
        if (imageDomains.none { it.equals(endpoint.domain, true) }) return
        lastImageEndpoint = endpoint
        prefs()?.edit()?.also { persistEndpoint(it, "selected_image_endpoint", endpoint) }?.apply()
        publishEndpointSelection()
    }

    /** API and image endpoint maps never borrow addresses from each other's config pool. */
    fun resolveLineIp(host: String): List<String> =
        lineRouteRules.entries.firstOrNull { it.key.equals(host, true) }?.value.orEmpty()

    fun resolveImageIp(host: String): List<String> =
        imageRouteRules.entries.firstOrNull { it.key.equals(host, true) }?.value.orEmpty()

    fun hasEndpointMeasurements(host: String, image: Boolean): Boolean {
        val endpoints = if (image) _speedTestSnapshot.value.imageIpDelaysMs.keys else _speedTestSnapshot.value.lineIpDelaysMs.keys
        val failures = if (image) failedImageEndpoints else failedLineEndpoints
        return endpoints.any { it.domain.equals(host, true) } || failures.any { it.domain.equals(host, true) }
    }

    @Deprecated("Use resolveLineIp or resolveImageIp so lines2/sources2 remain isolated")
    fun resolveIp(host: String): List<String> = resolveLineIp(host) + resolveImageIp(host)

    @Synchronized
    fun recordApiSuccess(host: String = lineHost) {
        consecutiveFailures = 0
        lineHealth.remove(host.lowercase(Locale.ROOT))
        currentLineEndpoint(host)?.let { endpoint ->
            failedLineEndpoints.remove(endpoint.normalizedForComparison())
            lastLineEndpoint = endpoint
            prefs()?.edit()?.also { persistEndpoint(it, "selected_line_endpoint", endpoint) }?.apply()
        }
        record("api", host, "JM API 请求成功")
    }

    @Synchronized
    fun recordApiEndpointFailure(endpoint: DomainIp, detail: String) {
        failedLineEndpoints += endpoint.normalizedForComparison()
        persistSnapshot()
        if (manualLineEndpoint?.normalizedForComparison() == endpoint.normalizedForComparison()) {
            manualLineEndpoint = null
            prefs()?.edit()?.remove("manual_line_endpoint_domain")?.remove("manual_line_endpoint_ip")?.apply()
        }
        val next = currentLineEndpoint(endpoint.domain)
        if (next != null) {
            lastLineEndpoint = next
            prefs()?.edit()?.also { persistEndpoint(it, "selected_line_endpoint", next) }?.apply()
        } else {
            lastLineEndpoint = null
            prefs()?.edit()?.remove("selected_line_endpoint_domain")?.remove("selected_line_endpoint_ip")?.apply()
        }
        record("api_ip_failover", endpoint.domain, "$detail；${endpoint.ip} -> ${next?.ip ?: "无已测速成功备选"}")
        publishEndpointSelection()
        JmNetworkModule.evictIdleEndpointPools()
    }

    @Synchronized
    fun recordImageEndpointFailure(endpoint: DomainIp, detail: String) {
        failedImageEndpoints += endpoint.normalizedForComparison()
        persistSnapshot()
        if (manualImageEndpoint?.normalizedForComparison() == endpoint.normalizedForComparison()) {
            manualImageEndpoint = null
            prefs()?.edit()?.remove("manual_image_endpoint_domain")?.remove("manual_image_endpoint_ip")?.apply()
        }
        val next = currentImageEndpoint(endpoint.domain)
        if (next != null) {
            lastImageEndpoint = next
            prefs()?.edit()?.also { persistEndpoint(it, "selected_image_endpoint", next) }?.apply()
        } else {
            lastImageEndpoint = null
            prefs()?.edit()?.remove("selected_image_endpoint_domain")?.remove("selected_image_endpoint_ip")?.apply()
        }
        record("image_ip_failover", endpoint.domain, "$detail；${endpoint.ip} -> ${next?.ip ?: "无已测速成功备选"}")
        // Keep the active image interceptor alive so it can continue its hostname fallback loop.
        JmNetworkModule.evictIdleEndpointPools()
        if (next == null) fastestImageDomain()?.let { fallback -> _state.value = _state.value.copy(imageDomain = fallback) }
        persistCurrentEndpoints()
        publishEndpointSelection()
    }

    @Synchronized
    fun recordApiFailure(host: String = lineHost, detail: String = "JM API 请求失败") {
        val key = host.lowercase(Locale.ROOT)
        val previous = lineHealth[key]?.failures ?: 0
        val failures = previous + 1
        val cooldown = (BASE_COOLDOWN_MS * (1L shl (failures - 1).coerceAtMost(5))).coerceAtMost(MAX_COOLDOWN_MS)
        lineHealth[key] = Health(failures, System.currentTimeMillis() + cooldown)
        consecutiveFailures++
        record("api", host, "$detail；冷却 ${cooldown / 1000}s（$failures 次）")
        if (consecutiveFailures >= API_FAILURE_THRESHOLD && _state.value.autoSelect) {
            consecutiveFailures = 0
            selectNextAvailableLine()
        }
    }

    @Synchronized
    private fun selectNextAvailableLine() {
        val current = _state.value
        if (current.lines.size < 2) return
        val next = (1..current.lines.size).asSequence()
            .map { (current.lineIndex + it) % current.lines.size }
            .firstOrNull {
                val host = hostFromUrl(current.lines[it])
                isLineAvailable(host) && (!hasEndpointMeasurements(host, image = false) || currentLineEndpoint(host) != null)
            }
            ?: return
        _state.value = current.copy(lineIndex = next)
        persistCurrentEndpoints()
        record("failover", hostFromUrl(current.lines[next]), "JM 自动切换至可用线路")
    }

    fun isLineAvailable(host: String): Boolean = isAvailable(lineHealth, host)
    fun isImageAvailable(host: String): Boolean = isAvailable(imageHealth, host)

    fun recordImageSuccess(host: String) {
        imageHealth.remove(host.lowercase(Locale.ROOT))
        record("image", host, "JM 图像获取成功")
    }

    fun recordImageFailure(host: String, detail: String) {
        val key = host.lowercase(Locale.ROOT)
        val failures = (imageHealth[key]?.failures ?: 0) + 1
        val cooldown = (BASE_COOLDOWN_MS * (1L shl (failures - 1).coerceAtMost(5))).coerceAtMost(MAX_COOLDOWN_MS)
        imageHealth[key] = Health(failures, System.currentTimeMillis() + cooldown)
        record("image", host, "$detail；冷却 ${cooldown / 1000}s")
    }

    fun currentImageEndpoint(domain: String = activeImageDomain): DomainIp? {
        val snapshot = _speedTestSnapshot.value
        val selected = JmEndpointSelectionPolicy.select(
            domain = domain,
            configuredDomains = imageDomains,
            delays = snapshot.imageIpDelaysMs,
            successful = { snapshot.imageIpVerdicts[it] == JmSpeedTester.ImgVerdict.OK },
            manualEndpoint = manualImageEndpoint,
            excluded = failedImageEndpoints,
        )
        if (selected != null) return selected
        if (snapshot.imageIpDelaysMs.keys.any { it.domain.equals(domain, true) }) return null
        manualImageEndpoint?.takeIf { it.domain.equals(domain, true) && !isFailed(it, failedImageEndpoints) }?.let { return it }
        lastImageEndpoint?.takeIf { it.domain.equals(domain, true) && !isFailed(it, failedImageEndpoints) }?.let { return it }
        return imageRouteRules.entries.firstOrNull { it.key.equals(domain, true) }?.value
            ?.firstOrNull { isLiteralIp(it) && !isFailed(DomainIp(domain, it), failedImageEndpoints) }
            ?.let { DomainIp(domain, it) }
    }

    fun imageCandidates(): List<String> {
        val configured = imageDomains
        val preferredFirst = configured.firstOrNull { it.equals(activeImageDomain, true) }
            ?.let { selected -> listOf(selected) + configured.filterNot { it.equals(selected, true) } }
            ?: configured
        val allHosts = (configured + DEFAULT_IMAGE_DOMAINS).toSet()
        val available = allHosts.filter(::isImageAvailable).toSet()
        // The absolute URL's rotating host is intentionally not inserted into this static source pool.
        return JmImagePolicy.imageCandidates(preferredFirst, available, DEFAULT_IMAGE_DOMAINS)
    }

    /** Hostname routes match JmImageInterceptor; DNS/IP choice remains owned by JM transport. */
    fun imageRetryEndpoints(excluded: Set<DomainIp>): List<DomainIp> {
        val tried = excluded.mapTo(HashSet()) { it.normalizedForComparison().domain }
        return imageCandidates().map { DomainIp(it, "").normalizedForComparison() }
            .filterNot { it.domain in tried }
    }

    @Synchronized
    fun recordSpeedTest(snapshot: SpeedTestSnapshot, expectedPlatformRevision: Long? = null): Boolean {
        if (!applySnapshot(snapshot, expectedPlatformRevision) ||
            (expectedPlatformRevision != null && StartupPlatformPolicy.state.value.revision != expectedPlatformRevision) ||
            !StartupPlatformPolicy.isEnabled(ContentPlatform.JM)
        ) {
            return false
        }
        record("speed", "JM", "JM 独立线路/图床测速完成")
        return true
    }

    @Synchronized
    fun record(kind: String, host: String, message: String) {
        val next = (_logs.value + LogEntry(System.currentTimeMillis(), kind, host, message)).takeLast(MAX_LOGS)
        _logs.value = next
        prefs()?.edit()?.putString(KEY_LOGS, encodeLogs(next))?.apply()
        publishEndpointSelection()
    }

    private fun persistSnapshot() {
        prefs()?.edit()
            ?.putString(KEY_SNAPSHOT, encodeSnapshot(_speedTestSnapshot.value))
            ?.putString("failed_line_endpoints", encodeFailedEndpoints(failedLineEndpoints))
            ?.putString("failed_image_endpoints", encodeFailedEndpoints(failedImageEndpoints))
            ?.apply()
    }

    private fun encodeFailedEndpoints(endpoints: Set<DomainIp>): String = JSONArray().apply {
        endpoints.forEach { put(JSONObject().put("domain", it.domain).put("ip", it.ip)) }
    }.toString()

    private fun decodeFailedEndpoints(raw: String?, configured: List<String>): Set<DomainIp> = runCatching {
        val array = JSONArray(raw ?: return@runCatching emptySet())
        (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            storedEndpoint(item.optString("domain"), item.optString("ip"), configured)?.normalizedForComparison()
        }.toSet()
    }.getOrDefault(emptySet())

    private fun persistCurrentEndpoints() {
        val editor = prefs()?.edit() ?: return
        persistEndpoint(editor, "selected_line_endpoint", selectedLineEndpoint)
        persistEndpoint(editor, "selected_image_endpoint", currentImageEndpoint())
        editor.apply()
    }

    private fun persistEndpoint(editor: SharedPreferences.Editor?, prefix: String, endpoint: DomainIp?) {
        if (editor == null) return
        if (endpoint == null) {
            editor.remove("${prefix}_domain").remove("${prefix}_ip")
        } else {
            editor.putString("${prefix}_domain", endpoint.domain).putString("${prefix}_ip", endpoint.ip)
        }
    }

    private fun publishEndpointSelection() {
        val previous = _endpointSelection.value
        _endpointSelection.value = EndpointSelectionState(
            revision = previous.revision + 1,
            line = currentLineEndpoint(lineHost),
            image = currentImageEndpoint(activeImageDomain),
        )
    }

    private fun isFailed(endpoint: DomainIp, failed: Set<DomainIp>): Boolean =
        failed.any { it.normalizedForComparison() == endpoint.normalizedForComparison() }

    private fun isLiteralIp(value: String): Boolean =
        value.isNotBlank() && (value.all { it.isDigit() || it == '.' } || ':' in value) &&
            runCatching { java.net.InetAddress.getByName(value) }.isSuccess

    private fun storedEndpoint(domain: String?, ip: String?, configured: List<String>): DomainIp? {
        if (domain.isNullOrBlank() || ip.isNullOrBlank() || !isLiteralIp(ip.trim()) || configured.none { it.equals(domain, true) }) return null
        return DomainIp(domain, ip.trim())
    }

    private fun prefs(): SharedPreferences? = runCatching {
        DevConfig.appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()

    private fun isAvailable(map: ConcurrentHashMap<String, Health>, host: String): Boolean {
        val key = host.lowercase(Locale.ROOT)
        val health = map[key] ?: return true
        if (health.untilEpochMs <= System.currentTimeMillis()) {
            map.remove(key, health)
            return true
        }
        return false
    }

    private fun normalizeLines(values: List<String>): List<String> {
        val unique = LinkedHashMap<String, String>()
        values.forEach { value ->
            val raw = value.trim()
            if (raw.isNotBlank()) {
                val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
                val host = hostFromUrl(url).lowercase(Locale.ROOT)
                if (host.isNotBlank()) unique.putIfAbsent(host, url)
            }
        }
        return unique.values.toList()
    }

    private fun normalizeHosts(values: List<String>): List<String> = JmImagePolicy.deduplicateImageHosts(values)

    private fun decodeStringList(raw: String?): List<String> = runCatching {
        val array = JSONArray(raw ?: return@runCatching emptyList())
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun hostFromUrl(value: String): String {
        val raw = value.trim()
        val normalized = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        return runCatching { URI(normalized).host.orEmpty() }
            .getOrDefault(raw.removePrefix("https://").removePrefix("http://").substringBefore('/'))
    }

    private fun encodeSnapshot(snapshot: SpeedTestSnapshot): String = JSONObject().apply {
        put("started", snapshot.startedAtEpochMs ?: JSONObject.NULL)
        put("completed", snapshot.completedAtEpochMs ?: JSONObject.NULL)
        put("version", snapshot.remoteVersion ?: JSONObject.NULL)
        put("configFingerprint", snapshot.configFingerprint ?: JSONObject.NULL)
        put("imageConfigFingerprint", snapshot.imageConfigFingerprint ?: JSONObject.NULL)
        put("imageNetworkPolicy", snapshot.imageNetworkPolicy ?: JSONObject.NULL)
        put("lines", JSONObject().apply { snapshot.lineDelaysMs.forEach { (host, delay) -> put(host, delay) } })
        put("images", JSONObject().apply { snapshot.imageDelaysMs.forEach { (host, delay) -> put(host, delay) } })
        fun endpoints(delays: Map<DomainIp, Long>) = JSONArray().apply {
            delays.forEach { (endpoint, delay) ->
                put(
                    JSONObject().put("host", endpoint.domain).put("ip", endpoint.ip).put("delay", delay).apply {
                        snapshot.imageIpVerdicts[endpoint]?.let { put("verdict", it.name) }
                    },
                )
            }
        }
        put("lineIps", endpoints(snapshot.lineIpDelaysMs))
        put("imageIps", endpoints(snapshot.imageIpDelaysMs))
    }.toString()

    private fun decodeSnapshot(raw: String?): SpeedTestSnapshot = runCatching {
        val root = JSONObject(raw ?: return@runCatching SpeedTestSnapshot(remoteVersion = _state.value.remoteVersion))
        fun delays(key: String): Map<String, Long> {
            val obj = root.optJSONObject(key) ?: return emptyMap()
            val result = LinkedHashMap<String, Long>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val host = keys.next()
                result[host] = obj.optLong(host, Long.MAX_VALUE)
            }
            return result
        }
        fun endpointDelays(key: String): Map<DomainIp, Long> {
            val array = root.optJSONArray(key) ?: return emptyMap()
            return (0 until array.length()).associate { index ->
                val item = array.getJSONObject(index)
                DomainIp(item.getString("host"), item.getString("ip")) to item.getLong("delay")
            }
        }
        val imageIps = root.optJSONArray("imageIps")
        val imageVerdicts = (0 until (imageIps?.length() ?: 0)).mapNotNull { index ->
            val item = imageIps!!.getJSONObject(index)
            val verdict = runCatching { JmSpeedTester.ImgVerdict.valueOf(item.getString("verdict")) }.getOrNull()
            verdict?.let { DomainIp(item.getString("host"), item.getString("ip")) to it }
        }.toMap()
        SpeedTestSnapshot(
            lineDelaysMs = delays("lines"),
            imageDelaysMs = delays("images"),
            lineIpDelaysMs = endpointDelays("lineIps"),
            imageIpDelaysMs = endpointDelays("imageIps"),
            imageIpVerdicts = imageVerdicts,
            startedAtEpochMs = root.optLong("started").takeIf { it > 0 },
            completedAtEpochMs = root.optLong("completed").takeIf { it > 0 },
            remoteVersion = root.optInt("version", -1).takeIf { it >= 0 },
            configFingerprint = root.optString("configFingerprint").takeIf { it.isNotBlank() && it != "null" },
            imageConfigFingerprint = root.optString("imageConfigFingerprint").takeIf { it.isNotBlank() && it != "null" },
            imageNetworkPolicy = root.optString("imageNetworkPolicy").takeIf { it.isNotBlank() && it != "null" },
        )
    }.getOrDefault(SpeedTestSnapshot(remoteVersion = _state.value.remoteVersion))

    private fun encodeLogs(entries: List<LogEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().put("at", entry.atEpochMs).put("kind", entry.kind).put("host", entry.host).put("message", entry.message))
        }
    }.toString()

    private fun decodeLogs(raw: String?): List<LogEntry> = runCatching {
        val array = JSONArray(raw ?: return@runCatching emptyList())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            LogEntry(item.optLong("at"), item.optString("kind"), item.optString("host"), item.optString("message"))
        }.takeLast(MAX_LOGS)
    }.getOrDefault(emptyList())
}
