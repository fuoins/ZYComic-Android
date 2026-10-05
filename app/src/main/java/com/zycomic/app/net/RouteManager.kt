package com.zycomic.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        "https://mseeowpm.online",   // 1
        "https://mseeowpm.cc",       // 2
        "http://mseeowpm.pro",       // 3
        "http://mseeowpm2.cc",       // 4
        "https://mseeowpma.cc",      // 5
        "http://mseeowpm1.xyz",      // 6
    )

    // ---- 6 个图源域名 ----
    val IMG_DOMAINS: List<String> = listOf(
        "newmwimserv5.cc",
        "mwappimgs.cc",
        "mwfimsvfast31.cc",
        "mwfimsvfast40.cc",
        "newmwimserv4.cc",
        "newmwimserv6.cc",
    )

    // ---- 动态域名列表（开发者配置更新后同步）----
    /** 当前生效的线路列表（测速和请求用这个，不用硬编码 LINE_HOSTS） */
    @Volatile var lineHosts: List<String> = LINE_HOSTS
        private set
    /** 当前生效的图源域名列表（测速用这个，不用硬编码 IMG_DOMAINS） */
    @Volatile var imgDomains: List<String> = IMG_DOMAINS
        private set

    /**
     * 根据 customRule 的 keys 更新线路和图源域名列表。
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
                    val url = if (domain.endsWith(".pro") || domain.endsWith(".cc") && !domain.contains("mseeowpm")) {
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
        // 线路：在当前 lineHosts 基础上追加 rule 推导的线路，不重置（保留 Trello 追加的线路）
        if (lines.isNotEmpty()) {
            val merged = lineHosts.toMutableList()
            lines.forEach { l -> if (merged.none { sameLine(it, l) }) merged.add(l) }
            lineHosts = merged
        }
        if (imgs.isNotEmpty()) imgDomains = imgs
    }

    /** 更新图源列表：以硬编码 IMG_DOMAINS 为基底合并追加，去重并持久化。 */
    fun updateImgDomains(domains: List<String>) {
        val merged = IMG_DOMAINS.toMutableList()
        domains.forEach { d -> if (merged.none { it.equals(d, ignoreCase = true) }) merged.add(d) }
        imgDomains = merged
        persistImgDomains(merged)
    }

    /** 启动时读持久化的图源列表并合并。 */
    fun loadImgDomainsFromPrefs() {
        try {
            val ctx = DevConfig.appContext ?: return
            val raw = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .getString("img_domains", null) ?: return
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            updateImgDomains(list)
        } catch (_: Exception) {}
    }

    private fun persistImgDomains(domains: List<String>) {
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putString("img_domains", org.json.JSONArray(domains).toString()).apply()
        } catch (_: Exception) {}
    }

    private fun normalizeLine(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun lineKey(url: String): String =
        url.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")

    private fun sameLine(a: String, b: String): Boolean = lineKey(a) == lineKey(b)

    /** 服务端线路追加：只增不减去重，不覆盖硬编码线路。返回本次新增的线路。 */
    fun appendServerLines(serverLines: List<String>): List<String> {
        val normalized = serverLines.map { normalizeLine(it) }.filter { it.isNotBlank() }.distinctBy { lineKey(it) }
        if (normalized.isEmpty()) return emptyList()
        persistServerLines(normalized)
        val current = lineHosts.toMutableList()
        val added = mutableListOf<String>()
        normalized.forEach { url ->
            if (current.none { sameLine(it, url) }) {
                current.add(url)
                added.add(url)
            }
        }
        if (added.isNotEmpty()) lineHosts = current
        return added
    }

    /** 启动时读本地缓存的服务端线路并合并。 */
    fun loadServerLinesFromPrefs() {
        try {
            val ctx = DevConfig.appContext ?: return
            val raw = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .getString("server_lines", null) ?: return
            val arr = org.json.JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            appendServerLines(list)
        } catch (_: Exception) {}
    }

    private fun persistServerLines(lines: List<String>) {
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putString("server_lines", org.json.JSONArray(lines).toString()).apply()
        } catch (_: Exception) {}
    }

    // ---- 当前选择 ----
    @Volatile var lineIndex: Int = 0
        private set
    @Volatile var imgIndex: Int = 0
        private set

    // ---- 测速结果持久化（切换页面后不丢失）----
    /** 最近一次线路测速结果：index -> 毫秒，失败为 Long.MAX_VALUE */
    @Volatile
    var lastLineDelays: Map<Int, Long> = emptyMap()
        private set

    /** 最近一次图源测速结果：index -> 毫秒，失败为 Long.MAX_VALUE */
    @Volatile
    var lastImgDelays: Map<Int, Long> = emptyMap()
        private set

    /** 写入最近一次测速结果（SettingsViewModel 测速完成后调用）。 */
    fun setLastDelays(line: Map<Int, Long>, img: Map<Int, Long>) {
        lastLineDelays = line
        lastImgDelays = img
    }

    fun isSpeedTestToday(): Boolean = try {
        val ctx = DevConfig.appContext ?: return false
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            .getString("last_speed_test_date", "") == today
    } catch (_: Exception) { false }

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
        get() = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')

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

    /** 切换线路 */
    fun setLine(index: Int) {
        lineIndex = index.coerceIn(0, lineHosts.lastIndex)
    }

    /** 切换图源 */
    fun setImgHost(index: Int) {
        imgIndex = index.coerceIn(0, imgDomains.lastIndex)
        saveImgIndex()
    }

    /** 从 SharedPreferences 加载图源索引（App启动时调用）。 */
    fun loadImgIndex() {
        try {
            val ctx = DevConfig.appContext ?: return
            val prefs = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getInt("img_index", 0)
            if (saved in 0..imgDomains.lastIndex) {
                imgIndex = saved
            }
        } catch (_: Exception) {}
    }

    /** 保存图源索引到 SharedPreferences。 */
    private fun saveImgIndex() {
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putInt("img_index", imgIndex).apply()
        } catch (_: Exception) {}
    }

    // ---- 自动选线 ----
    @Volatile
    var autoSelectEnabled: Boolean = true
        private set

    @Volatile
    var lastFastestLineIndex: Int = 0
        private set

    /** 从 SP 恢复自动选线偏好，开启时用上次最快线路作为当前线路。 */
    fun loadAutoSelectPrefs() {
        try {
            val ctx = DevConfig.appContext ?: return
            val prefs = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            autoSelectEnabled = prefs.getBoolean("auto_select", true)
            lastFastestLineIndex = prefs.getInt("last_fastest_line", 0)
            if (autoSelectEnabled && lastFastestLineIndex in 0..lineHosts.lastIndex) {
                lineIndex = lastFastestLineIndex
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

    /** 临时切换到下一条线路（不持久化）。 */
    fun selectNextLine() {
        lineIndex = (lineIndex + 1) % lineHosts.size
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
            NetworkModule.rebuild()
            NetworkModule.cookieJar.syncToAllLines()
        }
    }

    /** 设置自定义 rule（开发者配置） */
    fun setCustomRule(rule: Map<String, List<String>>) {
        customRule = rule
        updateDomainsFromRule(rule)
    }

    /** 设置 SNI 绕过域名列表 */
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
        customRule[host]?.let { if (it.isNotEmpty()) return it }
        return STATIC_IP[host].orEmpty()
    }

    // ==================== 默认配置 ====================

    /** 默认配置 JSON（应用启动时加载）。 */
    const val DEFAULT_CONFIG_JSON = """
{
  "port": 7891,
  "rule": {
    "mseeowpm.online": ["207.57.165.251","207.57.165.154","207.57.165.208","207.57.165.198","207.57.165.187"],
    "mseeowpm.pro": ["207.57.165.154","207.57.165.187","207.57.165.208","207.57.165.251","207.57.165.198"],
    "mseeowpm.cc": ["207.57.165.198","207.57.165.154","207.57.165.208","207.57.165.187","207.57.165.251"],
    "mseeowpm2.cc": ["207.57.165.187","207.57.165.198","207.57.165.154","207.57.165.208","207.57.165.251"],
    "mseeowpma.cc": ["207.57.165.208","207.57.165.154","207.57.165.198","207.57.165.251","207.57.165.187"],
    "mseeowpm1.xyz": ["207.57.165.251","207.57.165.198","207.57.165.208","207.57.165.154","207.57.165.187"],
    "newmwimserv5.cc": ["172.96.161.195","172.96.141.5","104.238.220.203","172.93.103.134"],
    "mwappimgs.cc": ["207.32.217.51","207.32.217.75","204.77.223.249"],
    "mwfimsvfast31.cc": ["172.96.161.195","172.93.103.134"],
    "mwfimsvfast40.cc": ["172.93.103.134","172.96.161.195","104.238.220.203","172.96.141.5","104.238.221.230"],
    "newmwimserv4.cc": ["104.238.220.203","172.93.103.134","172.96.141.5","172.96.161.195"],
    "newmwimserv6.cc": ["104.238.220.203","172.96.161.195","172.96.141.5","172.93.103.134"],
    "dns.google": ["8.8.8.8","8.8.4.4"]
  },
  "sni": ["mseeowpm.online","mseeowpm.pro","mseeowpm.cc","mseeowpm2.cc","mseeowpma.cc","mseeowpm1.xyz","newmwimserv5.cc","mwappimgs.cc","mwfimsvfast31.cc","mwfimsvfast40.cc","newmwimserv4.cc","newmwimserv6.cc"]
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

            // 解析 rule
            val ruleObj = root["rule"]?.jsonObject ?: emptyMap()
            val ruleMap = mutableMapOf<String, List<String>>()
            ruleObj.forEach { (domain, arr) ->
                val ips = arr.jsonArray.map { it.jsonPrimitive.content }
                ruleMap[domain] = ips
            }

            // 解析 sni
            val sniArr = root["sni"]?.jsonArray ?: JsonArray(emptyList())
            val sniSet = sniArr.map { it.jsonPrimitive.content }.toSet()

            setCustomRule(ruleMap)
            setSniDomains(sniSet)
        } catch (e: Exception) {
            android.util.Log.e("RouteManager", "applyDefaultConfig failed", e)
        }
    }
}
