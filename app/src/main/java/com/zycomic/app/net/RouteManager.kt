package com.zycomic.app.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    ) {
        val canExport: Boolean
            get() = isComplete &&
                startedAtEpochMs != null &&
                completedAtEpochMs != null &&
                networkPath != null &&
                lineDiagnostics.keys == lineDelays.keys &&
                imageDiagnostics.keys == imgDelays.keys &&
                imgVerdicts.keys == imgDelays.keys &&
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

    private val _speedTestSnapshot = MutableStateFlow(SpeedTestSnapshot())
    val speedTestSnapshot: StateFlow<SpeedTestSnapshot> = _speedTestSnapshot.asStateFlow()
    private val _completedSpeedTestSnapshot = MutableStateFlow(SpeedTestSnapshot())
    val completedSpeedTestSnapshot: StateFlow<SpeedTestSnapshot> = _completedSpeedTestSnapshot.asStateFlow()

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

    /** Replace image-source candidates with the exact remote list; never merge local or learned entries. */
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
        saveImgIndex()
        val savedPin = pinnedImgDomain ?: runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                ?.getString("pinned_img_domain", null)
        }.getOrNull()
        if (!savedPin.isNullOrBlank() && baselineImgDomains.none { it.equals(savedPin, ignoreCase = true) }) {
            pinnedImgDomain = null
            pinnedImgIp = null
            persistPinnedImgDomain(null, null)
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

    @Volatile private var selectedLineDomain: String? = null

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
    ): Boolean {
        if (expectedConfigRevision != null && expectedConfigRevision != configRevision) return false
        if (completeSnapshot && (
                startedAtEpochMs == null || completedAtEpochMs == null || networkPath == null ||
                    lineDiagnostics.keys != line.keys ||
                    imageDiagnostics.keys != img.keys ||
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
        )
        _speedTestSnapshot.value = snapshot
        if (completeSnapshot) _completedSpeedTestSnapshot.value = snapshot
        return true
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
        setCustomRule(emptyMap())
        setSniDomains(emptySet())
        activeRemoteConfigVersion = null
        clearSpeedTestResults()
        clearPersistedCandidateLists()
        configRevision++
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
    fun setLine(index: Int) {
        if (lineHosts.isEmpty()) return
        lineIndex = index.coerceIn(0, lineHosts.lastIndex)
        selectedLineIp = null
        selectedLineDomain = null
        persistLineEndpoint(null, null)
    }

    /** 精确选择线路域名/IP组合，并记住该组合。 */
    fun setLineEndpoint(domain: String, ip: String) {
        val index = lineIndexForDomain(domain)
        if (index < 0) return
        lineIndex = index
        selectedLineIp = ip
        selectedLineDomain = hostFromLine(lineHosts[index])
        persistLineEndpoint(selectedLineDomain, ip)
    }

    /** 切换图源 */
    fun setImgHost(index: Int) {
        if (imgDomains.isEmpty()) return
        imgIndex = index.coerceIn(0, imgDomains.lastIndex)
        saveImgIndex()
    }

    /** 从 SharedPreferences 加载图源索引（App启动时调用）。 */
    fun loadImgIndex() {
        try {
            val ctx = DevConfig.appContext ?: return
            if (imgDomains.isEmpty()) {
                imgIndex = 0
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
    private fun saveImgIndex() {
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("img_index", imgIndex)
                .putString("selected_img_domain", imgDomains.getOrNull(imgIndex))
                .apply()
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
    fun loadAutoSelectPrefs() {
        try {
            val ctx = DevConfig.appContext ?: return
            val prefs = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            autoSelectEnabled = prefs.getBoolean("auto_select", true)
            lastFastestLineIndex = prefs.getInt("last_fastest_line", 0)
            if (lineHosts.isEmpty()) return
            val savedDomain = prefs.getString("selected_line_domain", null)
            val savedIp = prefs.getString("selected_line_ip", null)
            val savedIndex = savedDomain?.let(::lineIndexForDomain)?.takeIf { it >= 0 }
            if (savedIndex != null) {
                lineIndex = savedIndex
                selectedLineIp = savedIp
                selectedLineDomain = hostFromLine(lineHosts[savedIndex])
            } else if (autoSelectEnabled && lastFastestLineIndex in 0..lineHosts.lastIndex) {
                lineIndex = lastFastestLineIndex
                selectedLineIp = null
                selectedLineDomain = null
                if (!savedDomain.isNullOrBlank()) persistLineEndpoint(null, null)
            } else {
                lineIndex = 0
                selectedLineIp = null
                selectedLineDomain = null
                if (!savedDomain.isNullOrBlank()) persistLineEndpoint(null, null)
            }
        } catch (_: Exception) {}
    }

    fun setAutoSelectEnabled(v: Boolean) {
        autoSelectEnabled = v
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("auto_select", v).apply()
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

    fun loadUseFastestImgForAll() {
        try {
            val ctx = DevConfig.appContext ?: return
            useFastestImgForAll = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .getBoolean("use_fastest_img_for_all", true)
        } catch (_: Exception) {}
    }

    fun updateUseFastestImgForAll(v: Boolean) {
        useFastestImgForAll = v
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
        get() = rankedSuccessfulImgEndpoints().firstOrNull()

    /** 从逐 IP 测速结果派生最快图源域名，无数据返回 null。 */
    val fastestImgDomain: String?
        get() = fastestImgEndpoint?.domain

    /** 当前生效图源的精确 IP（手动 pin 优先，否则跟随自动最快组合）。 */
    val selectedImgIp: String?
        get() {
            val target = imgRewriteTarget ?: imgHost
            pinnedImgIp?.takeIf { pinnedImgDomain.equals(target, ignoreCase = true) }?.let { return it }
            if (useFastestImgForAll) {
                return fastestImgEndpoint?.takeIf { it.domain.equals(target, ignoreCase = true) }?.ip
            }
            return null
        }

    // ---- 手动 pin 图源（持久化，直到用户点「恢复自动」）----
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

    /** 手动选定图源：同时更新 imgIndex（UI 显示）与 pin（实际生效）。 */
    fun pinImgHost(index: Int, ip: String? = null) {
        if (imgDomains.isEmpty()) return
        val safe = index.coerceIn(0, imgDomains.lastIndex)
        val domain = imgDomains.getOrNull(safe) ?: return
        setImgHost(safe)
        pinnedImgDomain = domain
        pinnedImgIp = ip
        persistPinnedImgDomain(domain, ip)
    }

    /** Apply the fastest measured combination unless the user has pinned a source. */
    fun applyFastestImgEndpoint(endpoint: DomainIp?): Boolean {
        endpoint ?: return false
        val index = imgDomains.indexOfFirst { it.equals(endpoint.domain, ignoreCase = true) }
        if (index < 0) return false
        val changed = imgIndex != index || selectedImgIp != endpoint.ip
        if (pinnedImgDomain.isNullOrBlank()) setImgHost(index)
        return changed
    }

    /** 恢复自动：清除手动 pin，重新由测速结果决定。 */
    fun clearPinnedImgDomain() {
        pinnedImgDomain = null
        pinnedImgIp = null
        persistPinnedImgDomain(null, null)
        fastestImgEndpoint?.let(::applyFastestImgEndpoint)
    }

    /** 启动时恢复 pin；域名已被移出当前图源列表时由策略自动忽略。 */
    fun loadPinnedImgDomain() {
        try {
            val ctx = DevConfig.appContext ?: return
            val prefs = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            pinnedImgDomain = prefs.getString("pinned_img_domain", null)?.takeIf { it.isNotBlank() }
            pinnedImgIp = prefs.getString("pinned_img_ip", null)?.takeIf { it.isNotBlank() }
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

    // ---- 图域端点失败冷却（图片请求兜底）----
    /** DomainIp -> cooldown expiry timestamp (ms); memory-only for the current process. */
    private val imgEndpointFailUntil = java.util.concurrent.ConcurrentHashMap<DomainIp, Long>()
    /** 图源端点网络失败冷却时长：30 分钟。 */
    private const val IMG_FAIL_COOLDOWN_MS = 30L * 60L * 1000L

    private fun normalizedEndpoint(endpoint: DomainIp): DomainIp =
        DomainIp(endpoint.domain.lowercase(java.util.Locale.ROOT), endpoint.ip.trim().lowercase(java.util.Locale.ROOT))

    /** Mark one exact endpoint after a transport failure; HTTP/content failures stay page-local. */
    fun markImgEndpointFailed(endpoint: DomainIp) {
        imgEndpointFailUntil[normalizedEndpoint(endpoint)] = System.currentTimeMillis() + IMG_FAIL_COOLDOWN_MS
    }

    /** Whether one exact domain/IP endpoint is not in cooldown. */
    fun isImgEndpointAvailable(endpoint: DomainIp): Boolean {
        val key = normalizedEndpoint(endpoint)
        val until = imgEndpointFailUntil[key] ?: return true
        if (until <= System.currentTimeMillis()) {
            imgEndpointFailUntil.remove(key, until)
            return true
        }
        return false
    }

    /**
     * Build the per-request order: selected/manual endpoint first, then successful endpoints by
     * speed. A null entry means the current request host has no measured endpoint, so the caller
     * may resolve one IP for its first attempt before continuing with measured candidates.
     */
    fun imageRetryEndpoints(requestedDomain: String): List<DomainIp?> {
        val ranked = rankedSuccessfulImgEndpoints()
        val selectedIp = preferredIp(requestedDomain)
        val preferred = selectedIp?.let { DomainIp(requestedDomain, it) }
            ?: ranked.firstOrNull { it.domain.equals(requestedDomain, ignoreCase = true) }
        if (preferred == null) return listOf(null) + ranked
        return ImageEndpointSelectionPolicy.retryOrder(preferred, ranked)
    }

    /** 临时切换到下一条线路（不持久化）。 */
    fun selectNextLine() {
        if (lineHosts.isEmpty()) return
        lineIndex = (lineIndex + 1) % lineHosts.size
        selectedLineIp = null
        selectedLineDomain = null
        persistLineEndpoint(null, null)
    }

    // ---- 故障自动切换 ----
    @Volatile
    var consecutiveFailures: Int = 0
        private set

    fun recordApiFailure() {
        if (++consecutiveFailures >= 3 && autoSelectEnabled) {
            consecutiveFailures = 0
            failover()
        }
    }

    fun recordApiSuccess() {
        consecutiveFailures = 0
    }

    private fun failover() {
        CoroutineScope(Dispatchers.IO).launch {
            selectNextLine()
            // 只清 API 侧：自动换线（连续 3 次 API 失败）不能 cancelAll 掉正在下载的图片
            NetworkModule.rebuildApi("自动换线")
            NetworkModule.cookieJar.syncToAllLines()
        }
    }

    /** Set the DNS/IP rules from the active remote configuration. */
    fun setCustomRule(rule: Map<String, List<String>>) {
        customRule = rule
    }

    /**
     * 应用显式的配置线路与图源列表，精确替换当前集合；不得合并内置、本地缓存或运行时学习项。
     * 若当前选中线路/图源仍存在则保留，否则回到首项并清理失效 pin。
     */
    @Synchronized
    fun applyBaseLists(lineUrls: List<String>, sourceDomains: List<String>, remoteVersion: Int? = null) {
        val lists = RouteListPolicy.replace(lineUrls, sourceDomains)
        activeRemoteConfigVersion = remoteVersion
        val oldLine = lineHosts.getOrNull(lineIndex)
        val savedSelection = runCatching {
            DevConfig.appContext?.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
        }.getOrNull()
        val oldDomain = selectedLineDomain ?: oldLine?.let(::hostFromLine)
            ?: savedSelection?.getString("selected_line_domain", null)
        val oldIp = selectedLineIp ?: savedSelection?.getString("selected_line_ip", null)
        val newLines = lists.lines
        lineHosts = newLines
        val keptLine = oldDomain?.let { domain -> newLines.indexOfFirst { hostFromLine(it).equals(domain, true) } }
            ?.takeIf { it >= 0 }
        lineIndex = keptLine ?: 0
        if (keptLine == null) {
            selectedLineIp = null
            selectedLineDomain = null
            persistLineEndpoint(null, null)
        } else if (oldIp != null) {
            selectedLineIp = oldIp
            selectedLineDomain = hostFromLine(newLines[keptLine])
            persistLineEndpoint(selectedLineDomain, selectedLineIp)
        } else {
            selectedLineIp = null
            selectedLineDomain = null
        }
        setBaselineImgDomains(lists.sources)
        clearSpeedTestResults()
        configRevision++
    }

    /** Remove speed rows for endpoints that may have been removed by the latest configuration. */
    @Synchronized
    fun clearSpeedTestResults() {
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
