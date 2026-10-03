package com.zycomic.app.net

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
 * - 预设 IP 映射表，供 [LocalProxyServer] 做 IP 直连。
 * - SNI 绕过域名列表，供 [LocalProxyServer] 做 MITM（不发 SNI）。
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
        "https://manwa.me",          // 7（旧版本线路）
        "http://wz65.cc",            // 8（旧版本线路）
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

    /** 最近一次每 IP 的 TCP 延迟：域名 -> (IP -> 毫秒)，失败为 Long.MAX_VALUE */
    @Volatile
    var lastIpDelays: Map<String, Map<String, Long>> = emptyMap()
        private set

    /** 每个域名测速选出的最快 IP（host -> ip），供代理优先使用 */
    val fastestIp: MutableMap<String, String> = mutableMapOf()

    /** 写入最近一次测速结果（SettingsViewModel 测速完成后调用）。 */
    fun setLastDelays(line: Map<Int, Long>, img: Map<Int, Long>) {
        lastLineDelays = line
        lastImgDelays = img
    }

    /** 写入最近一次每 IP 的 TCP 延迟（SettingsViewModel 测速完成后调用）。 */
    fun setLastIpDelays(ipDelays: Map<String, Map<String, Long>>) {
        lastIpDelays = ipDelays
    }

    /** 记录某域名的最快 IP（测速完成后调用）。 */
    fun setFastestIp(host: String, ip: String) {
        fastestIp[host] = ip
    }

    /** 当前接口 baseUrl */
    val baseUrl: String get() = LINE_HOSTS[lineIndex.coerceIn(0, LINE_HOSTS.lastIndex)]

    /** 当前图源 host（不含 scheme） */
    val imgHost: String get() = IMG_DOMAINS[imgIndex.coerceIn(0, IMG_DOMAINS.lastIndex)]

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
        lineIndex = index.coerceIn(0, LINE_HOSTS.lastIndex)
    }

    /** 切换图源 */
    fun setImgHost(index: Int) {
        imgIndex = index.coerceIn(0, IMG_DOMAINS.lastIndex)
    }

    /** 设置自定义 rule（开发者配置） */
    fun setCustomRule(rule: Map<String, List<String>>) {
        customRule = rule
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
  "sni": ["mseeowpm.online","mseeowpm.pro","mseeowpm.cc","mseeowpm2.cc","mseeowpma.cc","mseeowpm1.xyz","newmwimserv5.cc","mwappimgs.cc","mwfimsvfast31.cc","mwfimsvfast40.cc","newmwimserv4.cc","newmwimserv6.cc","dns.google"]
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
