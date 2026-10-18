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

    @Volatile private var reusableImageSnapshot: SpeedTestSnapshot? = null
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Volatile private var routeRules: Map<String, List<String>> = emptyMap()

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
        val rulesForJm = (normalizedLines.map(::hostFromUrl) + normalizedSources)
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        routeRules = rules.entries
            .filter { (host, _) -> host.lowercase(Locale.ROOT) in rulesForJm }
            .associate { (host, ips) -> host.lowercase(Locale.ROOT) to ips.toList() }
        jmSniDomains = (activeLines.map(::hostFromUrl) + activeSources + routeRules.keys)
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
        persistSnapshot()
        if (!poolsChanged && auto) applyFastestLine()
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
            0
        } else {
            savedLine?.let { target ->
                restoredLines.indexOfFirst { hostFromUrl(it).equals(target, true) }.takeIf { it >= 0 }
            } ?: 0
        }
        val img = savedImage?.let { target -> restoredImages.firstOrNull { it.equals(target, true) } }
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
        _logs.value = decodeLogs(prefs.getString(KEY_LOGS, null))
    }

    /** 选择 JM API 线路并切换到持久化手动模式。 */
    @Synchronized
    fun selectLine(index: Int) {
        val current = _state.value
        if (current.lines.isEmpty()) return
        val safe = index.coerceIn(0, current.lines.lastIndex)
        val host = hostFromUrl(current.lines[safe])
        _state.value = current.copy(lineIndex = safe, autoSelect = false)
        prefs()?.edit()?.putBoolean("auto_select", false)?.putString("selected_line", host)?.apply()
        consecutiveFailures = 0
        record("line", host, "手动选择 JM API 线路")
    }

    @Synchronized
    fun setAutoSelectEnabled(enabled: Boolean) {
        val current = _state.value
        _state.value = current.copy(autoSelect = enabled)
        prefs()?.edit()?.putBoolean("auto_select", enabled)?.apply()
        if (enabled) {
            applyFastestLine()
        } else {
            prefs()?.edit()?.putString("selected_line", hostFromUrl(baseUrl))?.apply()
        }
        record("line", lineHost, if (enabled) "启用 JM 自动选线" else "切换 JM 手动选线")
    }

    /** 手动固定 JM 图床；空值清除固定并恢复测速最快/首选图床。 */
    @Synchronized
    fun selectImageDomain(domain: String?) {
        val current = _state.value
        val selected = domain?.let { value -> current.imageDomains.firstOrNull { it.equals(value, true) } }
        val resolved = selected ?: fastestImageDomain() ?: current.imageDomains.first()
        _state.value = current.copy(imageDomain = resolved)
        val editor = prefs()?.edit()
        if (selected == null) editor?.remove("selected_image") else editor?.putString("selected_image", selected)
        editor?.apply()
        record("image", resolved, if (selected == null) "恢复 JM 图床自动选择" else "手动选择 JM 图床")
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
        if (isReusableImageSnapshotValid(snapshot)) {
            reusableImageSnapshot = snapshot
            prefs()?.edit()?.putString(KEY_IMAGE_SNAPSHOT, encodeSnapshot(snapshot))?.apply()
        }
        persistSnapshot()
        if (_state.value.autoSelect) applyFastestLine()
        if (pinnedImageDomain == null) {
            fastestImageDomain()?.let { domain -> _state.value = _state.value.copy(imageDomain = domain) }
        }
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
            imageDomains.forEach { host ->
                append(host.lowercase(Locale.ROOT)).append('=')
                resolveIp(host).sorted().forEach { append(it).append(',') }
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
            append(state.remoteVersion ?: "builtin").append('\n')
            state.lines.forEach { append(hostFromUrl(it).lowercase(Locale.ROOT)).append('\n') }
            append("--images--\n")
            state.imageDomains.forEach { append(it.lowercase(Locale.ROOT)).append('\n') }
            append("--rules--\n")
            routeRules.entries.sortedBy { it.key.lowercase(Locale.ROOT) }.forEach { (host, ips) ->
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
            .filter { it.value < Long.MAX_VALUE && isLineAvailable(it.key) }
            .minByOrNull { it.value }?.key ?: return
        val index = _state.value.lines.indexOfFirst { hostFromUrl(it).equals(fastest, true) }
        if (index >= 0) _state.value = _state.value.copy(lineIndex = index)
    }

    private fun fastestImageDomain(): String? = _speedTestSnapshot.value.imageDelaysMs.entries
        .filter { it.value < Long.MAX_VALUE && isImageAvailable(it.key) }
        .minByOrNull { it.value }
        ?.key?.let { measured -> imageDomains.firstOrNull { it.equals(measured, true) } }

    fun resolveIp(host: String): List<String> =
        routeRules.entries.firstOrNull { it.key.equals(host, true) }?.value.orEmpty()

    @Synchronized
    fun recordApiSuccess(host: String = lineHost) {
        consecutiveFailures = 0
        lineHealth.remove(host.lowercase(Locale.ROOT))
        record("api", host, "JM API 请求成功")
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
            .firstOrNull { isLineAvailable(hostFromUrl(current.lines[it])) }
            ?: return
        _state.value = current.copy(lineIndex = next)
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
    }

    private fun persistSnapshot() {
        prefs()?.edit()?.putString(KEY_SNAPSHOT, encodeSnapshot(_speedTestSnapshot.value))?.apply()
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

    private fun hostFromUrl(value: String): String =
        runCatching { URI(value).host.orEmpty() }
            .getOrDefault(value.removePrefix("https://").removePrefix("http://").substringBefore('/'))

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
