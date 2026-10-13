package com.zycomic.app.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 线路与图源管理。
 *
 * - 7 条控制面线路（接口域名），可切换。
 * - 6 个数据面图源（图片 CDN 域名），可切换。
 * - 预设 IP 映射表，供 RuleDns 做 IP 直连。
 * - SNI 绕过域名列表，供 SniRemovingSocketFactory 移除 SNI。
 */
object RouteManager {

    // ---- 8 条线路 ----
    val LINE_HOSTS: List<String> = listOf(
        "https://mseeowpm.online",
        "https://mseeowpm.cc",
        "https://mseeowpm2.cc",
        "https://mseeowpm3.cc",
        "https://mseeowpm4.cc",
        "http://mseeowpm.pro",
        "https://mseeowpma.cc",
        "http://mseeowpm1.xyz",
        "https://etmdcw.cn",
    )

    val IMG_DOMAINS: List<String> = listOf(
        "newmwtuyuan1.cc",
        "newmwtuyuan2.cc",
        "newmwtuyuan3.cc",
        "newmwtuyuan4.cc",
        "newmwtuyuan5.cc",
        // 官方当前在用的新图域（抓包证实其上有 /static/upload2/... 真实封面），
        // 列表缺失会少一个可达/更快的域；能否选中由探活实测决定。
        "newmwtuyuan8.cc",
        "newmwimserv1.cc",
        "newmwimserv2.cc",
        "newmwimserv3.cc",
        "newmwimserv4.cc",
        "newmwimserv5.cc",
        "newmwimserv6.cc",
        "newmwimserv7.cc",
        "newmwimserv8.cc",
        "newmwimserv9.cc",
        "newmwimserv10.cc",
        "newmwimserv11.cc",
        "newmwimserv12.cc",
        "newmwimserv13.cc",
        "newmwimserv14.cc",
        "newmwimserv15.cc",
        "newmwimserv16.cc",
        "mwfimsvfast2.cc",
        "mwfimsvfast5.cc",
        "mwfimsvfast9.cc",
        "mwfimsvfast10.cc",
        "mwfimsvfast11.cc",
        "mwfimsvfast12.cc",
        "mwfimsvfast13.cc",
        "mwfimsvfast14.cc",
        "mwfimsvfast15.cc",
        "mwfimsvfast16.cc",
        "mwfimsvfast17.cc",
        "mwfimsvfast18.cc",
        "mwfimsvfast19.cc",
        "mwfimsvfast20.cc",
        "mwfimsvfast21.cc",
        "mwfimsvfast22.cc",
        "mwfimsvfast23.cc",
        "mwfimsvfast24.cc",
        "mwfimsvfast25.cc",
        "mwfimsvfast26.cc",
        "mwfimsvfast27.cc",
        "mwfimsvfast28.cc",
        "mwfimsvfast29.cc",
        "mwfimsvfast30.cc",
        "mwfimsvfast31.cc",
        "mwfimsvfast32.cc",
        "mwfimsvfast33.cc",
        "mwfimsvfast34.cc",
        "mwfimsvfast35.cc",
        "mwfimsvfast36.cc",
        "mwfimsvfast37.cc",
        "mwfimsvfast38.cc",
        "mwfimsvfast39.cc",
        "mwfimsvfast40.cc",
        "mwfimsvfast41.cc",
        "mwfimsvfast42.cc",
        "mwfimsvfast43.cc",
        "mwfimsvfast44.cc",
        "mwfimsvfast45.cc",
        "mwfimsvfast46.cc",
        "mwfimsvfast47.cc",
        "mwfimsvfast48.cc",
        "mwfimsvfast49.cc",
        "mwfimsvfast50.cc",
        "mwfimsvfast51.cc",
        "mwfimsvfast52.cc",
        "mwfimsvfast53.cc",
        "mwfimsvfast54.cc",
        "mwfimsvfast55.cc",
        "mwfimsvfast56.cc",
        "mwfimsvfast57.cc",
        "mwfimsvfast58.cc",
        "mwfimsvfast59.cc",
        "mwfimsvfast60.cc",
        "mwappimgs.cc",
    )

    // ---- 动态域名列表（开发者配置更新后同步）----
    /** 当前生效的线路列表（测速和请求用这个，不用硬编码 LINE_HOSTS） */
    @Volatile var lineHosts: List<String> = LINE_HOSTS
        private set

    // 当前 DevConfig 中声明的权威 sources。
    @Volatile private var baselineImgDomains: List<String> = IMG_DOMAINS

    // 旧版字段兼容位，始终保持为空；服务器推荐项不得扩充全局候选。
    @Volatile private var chapterImgDomains: List<String> = emptyList()

    /** 当前生效的图源域名列表，与 DevConfig 当前 sources 一致。 */
    var imgDomains: List<String> by mutableStateOf(IMG_DOMAINS)
        private set

    /** Increased whenever an authoritative route/source configuration replaces the active lists. */
    @Volatile var configRevision: Long = 0L
        private set

    /** 从配置定义的图源集合重算当前列表；运行时推荐不再扩充全局候选集。 */
    private fun rebuildImgDomains() {
        val merged = baselineImgDomains.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }
        if (merged.isEmpty()) return
        val cur = imgDomains.getOrNull(imgIndex)
        imgDomains = merged
        val kept = cur?.let { c -> merged.indexOfFirst { it.equals(c, ignoreCase = true) } }?.takeIf { it >= 0 }
        imgIndex = kept ?: 0
        saveImgIndex()
    }

    /**
     * 仅用于旧格式配置（未提供显式 lines/sources）时，根据 customRule 的 keys 推导候选列表。
     * 线路域名：包含 "mseeowpm"；图源域名：包含 "mw" 且不是线路；排除 dns.google。
     */
    fun updateDomainsFromRule(rule: Map<String, List<String>>) {
        val lines = mutableListOf<String>()
        val imgs = mutableListOf<String>()
        rule.keys.forEach { domain ->
            when {
                domain == "dns.google" -> return@forEach
                domain.contains("mseeowpm") -> {
                    // 线路域名：补全 http/https 前缀（和 LINE_HOSTS 格式一致）
                    val url = if (domain.endsWith(".pro") || (domain.endsWith(".cc") && !domain.contains("mseeowpm"))) {
                        "http://$domain"
                    } else {
                        "https://$domain"
                    }
                    lines.add(url)
                }
                domain.contains("mw") || domain.contains("img") || domain.contains("imserv") || domain.contains("fimsv") -> {
                    imgs.add(domain)
                }
            }
        }
        // 旧格式兼容路径；校验通过的远程配置总是提供显式 lines/sources，不会进入这里。
        if (lines.isNotEmpty()) {
            val merged = lineHosts.toMutableList()
            lines.forEach { l -> if (merged.none { sameLine(it, l) }) merged.add(l) }
            lineHosts = merged
        }
        if (imgs.isNotEmpty()) {
            val merged = imgDomains.toMutableList()
            imgs.forEach { d -> if (merged.none { it.equals(d, ignoreCase = true) }) merged.add(d) }
            imgDomains = merged
        }
    }

    /** 配置是图源集合的唯一来源：不附加内置、章节运行时或历史缓存图源。 */
    fun setBaselineImgDomains(sources: List<String>) {
        val previous = persistedSelectedImgDomain() ?: imgDomains.getOrNull(imgIndex)
        val out = LinkedHashMap<String, String>()
        sources.map(String::trim).filter(String::isNotBlank).forEach { out.putIfAbsent(it.lowercase(), it) }
        if (out.isEmpty()) return

        baselineImgDomains = out.values.toList()
        chapterImgDomains = emptyList()
        imgDomains = baselineImgDomains
        imgIndex = baselineImgDomains.indexOfFirst { it.equals(previous, ignoreCase = true) }
            .takeIf { it >= 0 } ?: 0
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

    /** 兼容旧调用点；配置外图源不得进入或持久化为全局候选。 */
    fun addChapterImgDomain(domain: String) {
        if (baselineImgDomains.none { it.equals(domain.trim(), ignoreCase = true) }) return
        rebuildImgDomains()
    }

    /**
     * 清理旧版持久化候选列表。有效图源只从 DevConfig 当前的 lines/sources 配置重建。
     */
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
    fun setEndpointDelays(
        line: Map<DomainIp, Long>,
        img: Map<DomainIp, Long>,
        imgVerdicts: Map<DomainIp, SpeedTester.ImgVerdict>,
    ) {
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

    /** 当前接口 baseUrl */
    val baseUrl: String get() = lineHosts[lineIndex.coerceIn(0, lineHosts.lastIndex)]

    /** 当前图源 host（不含 scheme） */
    val imgHost: String get() = imgDomains[imgIndex.coerceIn(0, imgDomains.lastIndex)]

    /** 当前线路 host（不含 scheme） */
    val lineHost: String
        get() = hostFromLine(baseUrl)

    private fun hostFromLine(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')

    private fun lineIndexForDomain(domain: String): Int =
        lineHosts.indexOfFirst { hostFromLine(it).equals(domain, ignoreCase = true) }

    // ---- 预设 IP 映射 ----
    /** 6 个线路域名统一 IP 组 */
    private val LINE_IPS = listOf(
        "207.57.165.208",
        "207.57.165.154",
        "207.57.165.187",
        "207.57.165.251",
        "207.57.165.198",
    )

    /** 全量域名 -> IP 列表预设表（含线路 + 图源） */
    val STATIC_IP: Map<String, List<String>> = buildMap {
        // 线路域名
        listOf(
            "mseeowpm.online",
            "mseeowpm.pro",
            "mseeowpm.cc",
            "mseeowpm2.cc",
            "mseeowpma.cc",
            "mseeowpm1.xyz",
        ).forEach { put(it, LINE_IPS) }

        // 图源
        put("newmwimserv5.cc", listOf("172.93.103.134", "172.96.141.5", "104.238.220.203", "172.96.161.195"))
        put("newmwimserv4.cc", listOf("172.93.103.134", "172.96.141.5", "104.238.220.203", "172.96.161.195"))
        put("newmwimserv6.cc", listOf("172.93.103.134", "172.96.141.5", "104.238.220.203", "172.96.161.195"))
        put("mwappimgs.cc", listOf("204.77.223.249", "207.32.217.75", "207.32.217.51"))
        put("mwfimsvfast31.cc", listOf("172.93.103.134", "172.96.161.195"))
        put("mwfimsvfast40.cc", listOf("172.96.161.195", "104.238.220.203", "172.93.103.134", "104.238.221.230", "172.96.141.5"))
    }

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

    /** Fastest reachable image domain/IP combination from the most recent run. */
    val fastestImgEndpoint: DomainIp?
        get() = lastImgIpDelays.entries
            .filter { it.value < Long.MAX_VALUE && lastImgIpVerdicts[it.key] == SpeedTester.ImgVerdict.OK }
            .minByOrNull { it.value }?.key

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

    // ---- 图域失败冷却与失败轮换（图片请求兜底）----
    /** host -> 冷却到期时间戳（ms）。 */
    private val imgFailUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 图域失败冷却时长：30 分钟。 */
    private const val IMG_FAIL_COOLDOWN_MS = 30L * 60L * 1000L

    /** 标记某图域本次失败（403/reset/超时/非图片），进入短期冷却。 */
    fun markImgDomainFailed(host: String?) {
        host ?: return
        imgFailUntil[host] = System.currentTimeMillis() + IMG_FAIL_COOLDOWN_MS
    }

    /** 图域当前是否可用（未在冷却期）。 */
    fun isImgDomainAvailable(host: String?): Boolean {
        host ?: return true
        val until = imgFailUntil[host] ?: return true
        if (until <= System.currentTimeMillis()) {
            imgFailUntil.remove(host)
            return true
        }
        return false
    }

    /**
     * 从 [currentHost] 起按图域列表顺序取下一个"未冷却"图域；没有可用域返回 null。
     * 仅遍历一轮，不重复返回 currentHost。
     */
    fun nextAvailableImgDomain(currentHost: String?): String? {
        val all = imgDomains
        if (all.isEmpty()) return null
        val curIdx = all.indexOfFirst { it.equals(currentHost, ignoreCase = true) }
        val start = if (curIdx >= 0) curIdx else imgIndex
        val n = all.size
        for (step in 1..n) {
            val cand = all[(start + step) % n]
            if (!cand.equals(currentHost, ignoreCase = true) && isImgDomainAvailable(cand)) return cand
        }
        return null
    }

    /** 临时切换到下一条线路（不持久化）。 */
    fun selectNextLine() {
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

    /** 直接设置自定义 rule（DNS 直连映射），不在此做分类；分类由 applyBaseLists / 旧格式兜底负责。 */
    fun setCustomRule(rule: Map<String, List<String>>) {
        customRule = rule
    }

    /** 旧格式（无 lines/sources）兜底：按域名后缀猜测并增量追加线路/图源。 */
    fun applyLegacyClassification(rule: Map<String, List<String>>) {
        updateDomainsFromRule(rule)
    }

    /**
     * 应用显式的配置线路与图源列表，精确替换当前集合；不得合并内置、本地缓存或运行时学习项。
     * 若当前选中线路/图源仍存在则保留，否则回到首项并清理失效 pin。
     */
    fun applyBaseLists(lineUrls: List<String>, sourceDomains: List<String>) {
        val lists = RouteListPolicy.replace(lineUrls, sourceDomains)
        val oldLine = lineHosts.getOrNull(lineIndex)
        val oldDomain = selectedLineDomain ?: oldLine?.let(::hostFromLine)
        val oldIp = selectedLineIp
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
    fun clearSpeedTestResults() {
        lastLineDelays = emptyMap()
        lastImgDelays = emptyMap()
        lastLineIpDelays = emptyMap()
        lastImgIpDelays = emptyMap()
        lastImgIpVerdicts = emptyMap()
    }

    /** 设置 SNI 绕过域名列表（由 lines+sources 派生，调用方负责计算） */
    fun setSniDomains(domains: Set<String>) {
        sniDomains = domains
    }

    /** 判断某 host 是否需要 SNI 绕过 */
    fun isSniBypass(host: String): Boolean = sniDomains.contains(host)

    /**
     * 查找某 host 对应的 IP 列表。
     * 优先级：customRule > STATIC_IP。
     * @return IP 列表，空表示无预设（走系统 DNS）。
     */
    fun resolveIp(host: String): List<String> {
        customRule.entries.firstOrNull { it.key.equals(host, ignoreCase = true) }
            ?.value?.takeIf { it.isNotEmpty() }?.let { return it }
        return STATIC_IP.entries.firstOrNull { it.key.equals(host, ignoreCase = true) }
            ?.value.orEmpty()
    }

    // ==================== 默认配置 ====================

    /** 默认配置 JSON（应用启动时加载）。 */
    const val DEFAULT_CONFIG_JSON = """
{
  "version": 1,
  "updated_at": "2026-10-09T13:54:37+08:00",
  "port": 7891,
  "lines": [
    "https://mseeowpm.online",
    "https://mseeowpm.cc",
    "https://mseeowpm2.cc",
    "https://mseeowpm3.cc",
    "https://mseeowpm4.cc",
    "http://mseeowpm.pro",
    "https://mseeowpma.cc",
    "http://mseeowpm1.xyz",
    "https://etmdcw.cn"
  ],
  "sources": [
    "newmwtuyuan1.cc",
    "newmwtuyuan2.cc",
    "newmwtuyuan3.cc",
    "newmwtuyuan4.cc",
    "newmwtuyuan5.cc",
    "newmwimserv1.cc",
    "newmwimserv2.cc",
    "newmwimserv3.cc",
    "newmwimserv4.cc",
    "newmwimserv5.cc",
    "newmwimserv6.cc",
    "newmwimserv7.cc",
    "newmwimserv8.cc",
    "newmwimserv9.cc",
    "newmwimserv10.cc",
    "newmwimserv11.cc",
    "newmwimserv12.cc",
    "newmwimserv13.cc",
    "newmwimserv14.cc",
    "newmwimserv15.cc",
    "newmwimserv16.cc",
    "mwfimsvfast2.cc",
    "mwfimsvfast5.cc",
    "mwfimsvfast9.cc",
    "mwfimsvfast10.cc",
    "mwfimsvfast11.cc",
    "mwfimsvfast12.cc",
    "mwfimsvfast13.cc",
    "mwfimsvfast14.cc",
    "mwfimsvfast15.cc",
    "mwfimsvfast16.cc",
    "mwfimsvfast17.cc",
    "mwfimsvfast18.cc",
    "mwfimsvfast19.cc",
    "mwfimsvfast20.cc",
    "mwfimsvfast21.cc",
    "mwfimsvfast22.cc",
    "mwfimsvfast23.cc",
    "mwfimsvfast24.cc",
    "mwfimsvfast25.cc",
    "mwfimsvfast26.cc",
    "mwfimsvfast27.cc",
    "mwfimsvfast28.cc",
    "mwfimsvfast29.cc",
    "mwfimsvfast30.cc",
    "mwfimsvfast31.cc",
    "mwfimsvfast32.cc",
    "mwfimsvfast33.cc",
    "mwfimsvfast34.cc",
    "mwfimsvfast35.cc",
    "mwfimsvfast36.cc",
    "mwfimsvfast37.cc",
    "mwfimsvfast38.cc",
    "mwfimsvfast39.cc",
    "mwfimsvfast40.cc",
    "mwfimsvfast41.cc",
    "mwfimsvfast42.cc",
    "mwfimsvfast43.cc",
    "mwfimsvfast44.cc",
    "mwfimsvfast45.cc",
    "mwfimsvfast46.cc",
    "mwfimsvfast47.cc",
    "mwfimsvfast48.cc",
    "mwfimsvfast49.cc",
    "mwfimsvfast50.cc",
    "mwfimsvfast51.cc",
    "mwfimsvfast52.cc",
    "mwfimsvfast53.cc",
    "mwfimsvfast54.cc",
    "mwfimsvfast55.cc",
    "mwfimsvfast56.cc",
    "mwfimsvfast57.cc",
    "mwfimsvfast58.cc",
    "mwfimsvfast59.cc",
    "mwfimsvfast60.cc",
    "mwappimgs.cc"
  ],
  "rule": {
    "mseeowpm.online": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "mseeowpm.cc": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "mseeowpm2.cc": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "mseeowpm3.cc": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "mseeowpm4.cc": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "mseeowpm.pro": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "mseeowpma.cc": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "mseeowpm1.xyz": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "etmdcw.cn": [
      "207.57.165.154",
      "207.57.165.187",
      "207.57.165.198",
      "207.57.165.208",
      "207.57.165.251",
      "207.57.166.161"
    ],
    "newmwtuyuan1.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwtuyuan2.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwtuyuan3.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwtuyuan4.cc": [
      "162.255.119.184"
    ],
    "newmwtuyuan5.cc": [
      "162.255.119.231"
    ],
    "newmwimserv1.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv2.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv3.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv4.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv5.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv6.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv7.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv8.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv9.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv10.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv11.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv12.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv13.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv14.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv15.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "newmwimserv16.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast2.cc": [
      "104.21.72.208",
      "172.67.154.251"
    ],
    "mwfimsvfast5.cc": [
      "104.21.19.40",
      "172.67.184.246"
    ],
    "mwfimsvfast9.cc": [
      "172.65.211.209"
    ],
    "mwfimsvfast10.cc": [
      "172.65.211.209"
    ],
    "mwfimsvfast11.cc": [
      "172.65.211.209"
    ],
    "mwfimsvfast12.cc": [
      "172.65.211.209"
    ],
    "mwfimsvfast13.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5"
    ],
    "mwfimsvfast14.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5"
    ],
    "mwfimsvfast15.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5"
    ],
    "mwfimsvfast16.cc": [
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5"
    ],
    "mwfimsvfast17.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5"
    ],
    "mwfimsvfast18.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5"
    ],
    "mwfimsvfast19.cc": [
      "104.238.220.203",
      "172.93.103.134"
    ],
    "mwfimsvfast20.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast21.cc": [
      "104.238.220.203",
      "172.93.103.134"
    ],
    "mwfimsvfast22.cc": [
      "104.238.220.203",
      "172.93.103.134"
    ],
    "mwfimsvfast23.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast24.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast25.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast26.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast27.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5"
    ],
    "mwfimsvfast28.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast29.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast30.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast31.cc": [
      "172.93.103.134",
      "172.96.161.195"
    ],
    "mwfimsvfast32.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast33.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast34.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast35.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast36.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast37.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast38.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast39.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast40.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast41.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast42.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast43.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast44.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast45.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast46.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast47.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast48.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast49.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast50.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast51.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast52.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast53.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast54.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast55.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast56.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast57.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast58.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast59.cc": [
      "104.238.220.203",
      "104.238.221.230",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwfimsvfast60.cc": [
      "104.238.220.203",
      "172.93.103.134",
      "172.96.141.5",
      "172.96.161.195"
    ],
    "mwappimgs.cc": [
      "204.77.223.249",
      "207.32.217.51",
      "207.32.217.75"
    ]
  }
}
    """

    /**
     * 应用默认配置：解析 DEFAULT_CONFIG_JSON，提取 rule 和 sni。
     * 必须在 NetworkModule 构建 client 之前调用。
     */
    fun applyDefaultConfig() {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(DEFAULT_CONFIG_JSON).jsonObject
            fun strList(key: String): List<String> =
                root[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            val ruleMap = mutableMapOf<String, List<String>>()
            root["rule"]?.jsonObject?.forEach { (domain, arr) ->
                ruleMap[domain] = arr.jsonArray.map { it.jsonPrimitive.content }
            }
            val lines = strList("lines")
            val sources = strList("sources")

            setCustomRule(ruleMap)
            if (lines.isNotEmpty() && sources.isNotEmpty()) {
                // 新规范：显式 lines/sources 分类
                applyBaseLists(lines, sources)
            } else {
                // 旧格式兜底：后缀猜测
                applyLegacyClassification(ruleMap)
            }
            // SNI 由默认配置声明的线路/图源主机派生，不再读取独立 sni。
            setSniDomains(ruleMap.keys - "dns.google")
        } catch (e: Exception) {
            android.util.Log.e("RouteManager", "applyDefaultConfig failed", e)
        }
    }
}
