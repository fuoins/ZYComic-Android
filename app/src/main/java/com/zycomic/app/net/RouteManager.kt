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
    // 基础图源（内置 IMG_DOMAINS ∪ 远程/基线 sources）
    @Volatile private var baselineImgDomains: List<String> = IMG_DOMAINS
    // 章节接口 / DoH 运行时学到的图源（远程刷新不会清除）
    @Volatile private var chapterImgDomains: List<String> = emptyList()

    /** 当前生效的图源域名列表：基础图源 + 章节运行时图源（去重拼接，基础在前） */
    var imgDomains: List<String> by mutableStateOf(IMG_DOMAINS)
        private set

    /** 按基础集 + 章节集重算 imgDomains，并尽量保持当前选中图源。 */
    private fun rebuildImgDomains() {
        val out = LinkedHashSet<String>()
        baselineImgDomains.forEach { out.add(it.trim()) }
        chapterImgDomains.forEach { out.add(it.trim()) }
        val merged = out.toList()
        val cur = imgDomains.getOrNull(imgIndex)
        imgDomains = merged
        val kept = cur?.let { c -> merged.indexOfFirst { it.equals(c, ignoreCase = true) } }?.takeIf { it >= 0 }
        imgIndex = kept ?: imgIndex.coerceIn(0, merged.lastIndex)
    }

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
        if (imgs.isNotEmpty()) {
            val merged = imgDomains.toMutableList()
            imgs.forEach { d -> if (merged.none { it.equals(d, ignoreCase = true) }) merged.add(d) }
            imgDomains = merged
        }
    }

    /** 远程/基线基础图源：sources 在前，内置 IMG_DOMAINS 缺失补尾（内置永不删），持久化并重算。 */
    fun setBaselineImgDomains(sources: List<String>) {
        val out = LinkedHashSet<String>()
        sources.forEach { d -> d.trim().takeIf { it.isNotBlank() }?.let { out.add(it) } }
        IMG_DOMAINS.forEach { out.add(it) }
        baselineImgDomains = out.toList()
        persistStringList("baseline_img_domains", baselineImgDomains)
        rebuildImgDomains()
    }

    /** 章节接口/DoH 运行时学到的图源：加入章节集并持久化，远程刷新不会清除。 */
    fun addChapterImgDomain(domain: String) {
        val d = domain.trim()
        if (d.isNotBlank() && chapterImgDomains.none { it.equals(d, ignoreCase = true) }) {
            chapterImgDomains = chapterImgDomains + d
            persistStringList("chapter_img_domains", chapterImgDomains)
            rebuildImgDomains()
        } else {
            rebuildImgDomains()
        }
    }

    /**
     * 启动时恢复图源：基础集 baseline_img_domains（缺省回退内置），章节集 chapter_img_domains；
     * 并对旧版本做一次性拆分迁移 img_split_v1：旧 img_domains 中不属于内置的条目归入章节集。
     */
    fun loadImgDomainsFromPrefs() {
        try {
            val ctx = DevConfig.appContext ?: return
            val prefs = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            baselineImgDomains = prefs.getString("baseline_img_domains", null)?.let(::toStringList)
                ?.takeIf { it.isNotEmpty() } ?: IMG_DOMAINS
            chapterImgDomains = prefs.getString("chapter_img_domains", null)?.let(::toStringList) ?: emptyList()
            if (!prefs.getBoolean("img_split_v1", false)) {
                prefs.getString("img_domains", null)?.let(::toStringList)?.forEach { d ->
                    if (IMG_DOMAINS.none { it.equals(d, ignoreCase = true) } &&
                        chapterImgDomains.none { it.equals(d, ignoreCase = true) }
                    ) {
                        chapterImgDomains = chapterImgDomains + d
                    }
                }
                persistStringList("chapter_img_domains", chapterImgDomains)
                prefs.edit().putBoolean("img_split_v1", true).apply()
            }
            rebuildImgDomains()
        } catch (_: Exception) {}
    }

    private fun persistStringList(key: String, list: List<String>) {
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putString(key, org.json.JSONArray(list).toString()).apply()
        } catch (_: Exception) {}
    }

    private fun toStringList(raw: String): List<String> = try {
        val arr = org.json.JSONArray(raw)
        List(arr.length()) { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun normalizeLine(url: String): String {
        val t = url.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun lineKey(url: String): String =
        url.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")

    private fun sameLine(a: String, b: String): Boolean = lineKey(a) == lineKey(b)

    /**
     * Trello 专用入口（在 ruledns 远程更新之后执行的兜底来源）。
     * 严格"只增量补充线路"：只按 host 去重新增，已存在（含 ruledns 已托管）的线路一律跳过；
     * 不得覆盖/删除已有线路，不得改 rule/SNI，不得改图源。返回本次新增的线路。
     */
    fun appendTrelloLinesOnly(serverLines: List<String>): List<String> = appendServerLines(serverLines)

    /** 服务端线路追加：只增不减去重，不覆盖硬编码线路。返回本次新增的线路。 */
    fun appendServerLines(serverLines: List<String>): List<String> {
        val normalized = serverLines.map { normalizeLine(it) }.filter { it.isNotBlank() }.distinctBy { lineKey(it) }
        if (normalized.isEmpty()) return emptyList()
        val current = lineHosts.toMutableList()
        val added = mutableListOf<String>()
        normalized.forEach { url ->
            if (current.none { sameLine(it, url) }) {
                current.add(url)
                added.add(url)
            }
        }
        if (added.isNotEmpty()) {
            lineHosts = current
            persistServerLines(current)
        }
        return added
    }

    /** 读取本地缓存的 Trello 补充线路（不修改当前列表）。 */
    private fun readPersistedServerLines(): List<String> = try {
        val ctx = DevConfig.appContext ?: return emptyList()
        val raw = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            .getString("server_lines", null) ?: return emptyList()
        toStringList(raw)
    } catch (_: Exception) {
        emptyList()
    }

    /** 启动时读本地缓存的服务端线路并合并。 */
    fun loadServerLinesFromPrefs() {
        appendServerLines(readPersistedServerLines())
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

    // ---- IP 层测速分数表（不写进 config_json：远程 ruledns 会整体覆盖 rule）----
    private val ipScoreStore = IpScoreStore()

    /** 分数新鲜期：超过就重新按机器探测。 */
    private const val IP_SCORE_TTL_MS = 6 * 60 * 60 * 1000L

    /** 本轮 IP 探测结果（ip -> 毫秒），供测速日志与展示。 */
    @Volatile
    var lastIpDelays: Map<String, Long> = emptyMap()
        private set

    /** 直连策略查询的结果做 1s 记忆：`shouldUseDirectStrategy()` 会走 ConnectivityManager。 */
    private var lastDirectCheckAt = 0L
    private var lastDirectValue = true

    /** [currentNetworkKey] 的记忆值（5s 内复用，避免每条新连接都问一次系统） */
    private var lastNetworkKeyAt = 0L
    private var lastNetworkKeyValue = ""

    private fun directStrategyNowCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDirectCheckAt > 1_000L) {
            lastDirectCheckAt = now
            lastDirectValue = NetworkModule.shouldUseDirectStrategy()
        }
        return lastDirectValue
    }

    /**
     * 网络身份：换 Wi-Fi/蜂窝/重连都会变。VPN 模式下 IP 组不参与解析，
     * 所以把策略本身也编进 key，策略一翻旧分数即失效。
     */
    fun currentNetworkKey(): String {
        val now = System.currentTimeMillis()
        // 5s 记忆：本方法会被 DNS 查询（每建立一条新连接）触发，不能每次都去问系统
        if (lastNetworkKeyAt != 0L && now - lastNetworkKeyAt < 5_000L) return lastNetworkKeyValue
        val base = "direct=" + directStrategyNowCached()
        val key = try {
            val ctx = DevConfig.appContext
            val cm = ctx?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            // 用 Network.toString()（内含 netId/handle，值语义：同一网络得到同一字符串）。
            // **不能**用 System.identityHashCode —— activeNetwork 每次可能返回不同的 Network
            // 实例，那样 key 每查一次都在变，分数会被永久判成"网络已变化"：既不参与拨号排序，
            // 每轮还要全量重测。口径与 App.scheduleNetworkRetest 保持一致。
            val net = cm?.activeNetwork
            if (ctx == null) "$base/net=unknown" else "$base/net=${net ?: "none"}"
        } catch (_: Exception) {
            "$base/net=unknown"
        }
        lastNetworkKeyAt = now
        lastNetworkKeyValue = key
        return key
    }

    /** 从 SharedPreferences 恢复 IP 分数（网络身份/策略/新鲜期任一不符即视为不可用）。 */
    fun loadIpScores() {
        try {
            val ctx = DevConfig.appContext ?: return
            val raw = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .getString("ip_scores", null)
            val loaded = IpScoreStore.fromPersistentString(raw)
            ipScoreStore.replaceAll(loaded.scores)
            ipScoreStore.networkKey = loaded.networkKey
            ipScoreStore.directStrategy = loaded.directStrategy
            ipScoreStore.savedAt = loaded.savedAt
            android.util.Log.i(
                "RouteManager",
                "IP 分数恢复 count=${ipScoreStore.scores.size} usable=${ipScoresSnapshot().isNotEmpty()}",
            )
        } catch (e: Exception) {
            android.util.Log.w("RouteManager", "loadIpScores failed", e)
        }
    }

    /** 保存本轮 IP 分数并落盘；同时刷新可用性判定。 */
    fun saveIpScores(scores: Map<String, Long>, networkKey: String, directStrategy: Boolean) {
        ipScoreStore.replaceAll(scores)
        ipScoreStore.networkKey = networkKey
        ipScoreStore.directStrategy = directStrategy
        ipScoreStore.savedAt = System.currentTimeMillis()
        try {
            val ctx = DevConfig.appContext ?: return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putString("ip_scores", ipScoreStore.toPersistentString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * 当前可用的 IP 分数快照；网络身份变化、直连策略翻转（VPN 走 Dns.SYSTEM，IP 顺序不参与）、
     * 或超过新鲜期，一律返回空表，RuleDns 随即退回配置原序。
     *
     * 读时判定而不是存标志位：避免"策略在会话中途翻了、脏分数还在被用来排序"。
     */
    fun ipScoresSnapshot(): Map<String, Long> =
        if (ipScoreStore.isValidFor(
                networkKey = currentNetworkKey(),
                directStrategy = directStrategyNowCached(),
                now = System.currentTimeMillis(),
                ttlMs = IP_SCORE_TTL_MS,
            )
        ) {
            ipScoreStore.scores
        } else {
            emptyMap()
        }

    /**
     * 供 [NetworkModule.RuleDns] 在返回前调用：把该域名的 IP 组按"最快在前"重排。
     * 没有分数时原序返回，行为与改动前一致。
     */
    fun orderedIpsFor(host: String, ips: List<String>): List<String> {
        val scores = ipScoresSnapshot()
        if (scores.isEmpty()) return ips
        return IpProbePlanner.orderIps(ips, scores)
    }

    /** 记录本轮 IP 探测结果。 */
    fun setLastIpDelays(delays: Map<String, Long>) {
        lastIpDelays = delays
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

    /**
     * 应当用于改写图片/封面 URL 的图源域名。优先级：手动 pin > 本轮测速里排除冷却后的最快。
     *
     * 返回 null 表示**不接管**：既没有手动 pin，本轮也没有任何可用测速结论——此时保持
     * 服务端给的原始域名。选择器里的 `CHAPTER_NAMED` / `FIRST_IN_LIST` 两档需要章节上下文
     * 才有意义，留给 [effectiveImgDomain] 使用。
     */
    val fastestImgDomain: String?
        get() {
            val choice = ImgDomainSelector.select(
                candidates = imgDomains,
                delays = imgDelaysByHost(),
                cooled = imgCooldown.snapshot(),
                pinned = pinnedImgDomain,
                chapterNamed = emptyList(),
            )
            return if (choice.reason == ImgDomainSelector.Reason.FIRST_IN_LIST) null else choice.domain
        }

    /**
     * 统一决策入口：这次图片/封面请求应当改写到哪个图源；返回 null 表示**不接管**（用原始域名）。
     *
     * 优先级：手动 pin >（`useFastestImgForAll` 开启时）本轮排除冷却后的最快。
     * 开关关闭且没有 pin 时保持服务端给的域名，这是既有行为。
     * 所有改写点（两个拦截器、封面拼 URL、章节 URL 拼接、Coil 封面 fetcher）都只读这一个属性，
     * 避免"手动切图源被派生最快域静默覆盖"的问题再次出现在某个新增调用点上。
     */
    val imgRewriteTarget: String?
        get() = pinnedImgDomainInList ?: if (useFastestImgForAll) fastestImgDomain else null

    /** pin 指向的域仍在当前图源列表里才算有效（远程配置可能已把它删掉）。 */
    private val pinnedImgDomainInList: String?
        get() = pinnedImgDomain?.takeIf { p -> imgDomains.any { it.equals(p, ignoreCase = true) } }

    /**
     * 带章节上下文的完整优先级：手动 pin > 最快（排除冷却）> 章节接口点名 > 列表首个。
     * [chapterNamed] 传章节内容给的域名（`currentImgDomain` 在前，其后 `imgDomains`）。
     */
    fun effectiveImgDomain(chapterNamed: List<String>): String? =
        ImgDomainSelector.select(
            candidates = imgDomains,
            delays = imgDelaysByHost(),
            cooled = imgCooldown.snapshot(),
            pinned = pinnedImgDomain,
            chapterNamed = chapterNamed,
        ).domain

    /** [lastImgDelays] 以 index 为键，这里转成选择器需要的「域名 -> 延迟」。 */
    private fun imgDelaysByHost(): Map<String, Long> {
        val out = HashMap<String, Long>(lastImgDelays.size)
        lastImgDelays.forEach { (index, ms) -> imgDomains.getOrNull(index)?.let { out[it] = ms } }
        return out
    }

    // ---- 手动 pin 图源（持久化，直到用户点「恢复自动」）----
    /**
     * 用户手动钉住的图源域名。
     *
     * 修复点：此前 `useFastestImgForAll` 为 true 时，拦截器只读派生的最快域，
     * 阅读器/设置页里的手动切换图源对实际请求**完全无效**（空操作）。现在手动选择
     * 一律写入本字段，并由选择器置于最高优先级。
     */
    @Volatile var pinnedImgDomain: String? = null
        private set

    /** 手动选定图源：同时更新 imgIndex（UI 显示）与 pin（实际生效）。 */
    fun pinImgHost(index: Int) {
        val domain = imgDomains.getOrNull(index.coerceIn(0, imgDomains.lastIndex)) ?: return
        setImgHost(index)
        pinnedImgDomain = domain
        persistPinnedImgDomain(domain)
    }

    /** 恢复自动：清除手动 pin，重新由测速结果决定。 */
    fun clearPinnedImgDomain() {
        pinnedImgDomain = null
        persistPinnedImgDomain(null)
    }

    /** 启动时恢复 pin；域名已被移出当前图源列表时由选择器自动忽略，这里不额外校验。 */
    fun loadPinnedImgDomain() {
        try {
            val ctx = DevConfig.appContext ?: return
            pinnedImgDomain = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .getString("pinned_img_domain", null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {}
    }

    private fun persistPinnedImgDomain(domain: String?) {
        try {
            val ctx = DevConfig.appContext ?: return
            val editor = ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE).edit()
            if (domain == null) editor.remove("pinned_img_domain") else editor.putString("pinned_img_domain", domain)
            editor.apply()
        } catch (_: Exception) {}
    }

    // ---- 图域探活样本路径（真实封面相对路径，测速用）----
    /** 读取上次成功获取的探活图片相对路径（如 /static/upload/xxx.jpg）。 */
    fun loadProbeImgPath(): String? = try {
        val ctx = DevConfig.appContext ?: return null
        ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
            .getString("probe_img_path", null)?.takeIf { it.startsWith("/") && it.length > 1 }
    } catch (_: Exception) { null }

    /** 持久化探活图片相对路径。 */
    fun saveProbeImgPath(path: String) {
        try {
            val ctx = DevConfig.appContext ?: return
            if (!path.startsWith("/") || path.length <= 1) return
            ctx.getSharedPreferences("zycomic_route", android.content.Context.MODE_PRIVATE)
                .edit().putString("probe_img_path", path).apply()
        } catch (_: Exception) {}
    }

    // ---- 图域失败标记与失败轮换（图片请求兜底）----
    /**
     * 失败标记表：**本轮测速内永久**，只有 [clearImgDomainCooldown]（每轮测速/测活开始）才恢复。
     *
     * 原先的「30 分钟到期自然恢复」已按设计取消：跨轮的数据本就该被新一轮探测覆盖，
     * 留着只会污染新一轮选域；代价是偶发一次 reset 会把该域踢出本轮，已知悉并接受。
     */
    private val imgCooldown = ImgCooldown()

    /** 标记某图域本次失败（403/reset/超时/非图片），本轮内不再被选中。 */
    fun markImgDomainFailed(host: String?) {
        host ?: return
        imgCooldown.mark(host)
    }

    /** 图域当前是否可用（本轮未被标记失败）。 */
    fun isImgDomainAvailable(host: String?): Boolean {
        host ?: return true
        return !imgCooldown.isCooling(host)
    }

    /** 本轮被标记失败的图源（小写快照），供测速日志与选域使用。 */
    fun imgCooldownSnapshot(): Set<String> = imgCooldown.snapshot()

    /**
     * 每轮测速 / 测活开始前调用，整表清空。
     * 注意：**被防重入丢弃的那一轮不能清**，否则会白白丢掉已有标记。
     */
    fun clearImgDomainCooldown() {
        imgCooldown.clear()
    }

    /**
     * 从 [currentHost] 起按图域列表顺序取下一个未被标记失败的图域。
     *
     * 降级：若全部域都已被标记（整轮候选被掏空），**忽略标记**继续轮换——否则一次网络抖动
     * 就会把 `ImageInterceptor` 的换域兜底整体废掉。选域侧的同类降级见
     * [ImgDomainSelector.Reason.FASTEST_COOLDOWN_IGNORED]。仅遍历一轮，不重复返回 currentHost。
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
        for (step in 1..n) {
            val cand = all[(start + step) % n]
            if (!cand.equals(currentHost, ignoreCase = true)) return cand
        }
        return null
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
            // 只清 API 侧：自动换线时不能 cancelAll 掉正在下载的图片分页
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
     * 应用显式（远程/基线）线路与图源分类（绕过后缀猜测）：
     * 线路 = 远程 lines（保留各自 http/https）在前 + 内置 LINE_HOSTS 缺失补尾 + Trello 持久化补充线路；
     * 图源基础集 = sources + 内置 IMG_DOMAINS（章节运行时图源由 chapterImgDomains 另存，不在此处理）。
     * 尽量保持当前选中线路/图源。
     */
    fun applyBaseLists(lineUrls: List<String>, sourceDomains: List<String>) {
        val ordered = LinkedHashSet<String>()
        lineUrls.forEach { u -> normalizeLine(u).takeIf { it.isNotBlank() }?.let { ordered.add(it) } }
        LINE_HOSTS.forEach { ordered.add(it) }
        readPersistedServerLines().forEach { u -> if (ordered.none { sameLine(it, u) }) ordered.add(u) }
        val newLines = ordered.toList()
        val curLine = lineHosts.getOrNull(lineIndex)
        lineHosts = newLines
        val keptLine = curLine?.let { c -> newLines.indexOfFirst { sameLine(it, c) } }?.takeIf { it >= 0 }
        lineIndex = keptLine ?: lineIndex.coerceIn(0, newLines.lastIndex)
        setBaselineImgDomains(sourceDomains)
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
        customRule[host]?.let { if (it.isNotEmpty()) return it }
        return STATIC_IP[host].orEmpty()
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
            // SNI 由受管主机派生（含运行时学到的额外 rule 主机），不再读取独立 sni
            setSniDomains(ruleMap.keys - "dns.google")
        } catch (e: Exception) {
            android.util.Log.e("RouteManager", "applyDefaultConfig failed", e)
        }
    }
}
