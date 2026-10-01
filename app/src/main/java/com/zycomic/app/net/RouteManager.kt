package com.zycomic.app.net

/**
 * 线路与图源管理。
 *
 * - 7 条控制面线路（接口域名），可切换。
 * - 6 个数据面图源（图片 CDN 域名），可切换。
 * - 预设 IP 映射表（2026-09-30 通过 dns.google 查询），供 [DnsOverrideInterceptor] 做 IP 直连。
 */
object RouteManager {

    // ---- 7 条线路 ----
    val LINE_HOSTS: List<String> = listOf(
        "https://mseeowpm.online",   // 1
        "https://mseeowpm.pro",      // 2
        "https://mseeowpm.cc",       // 3
        "http://mseeowpm.pro",       // 4
        "http://mseeowpm2.cc",       // 5
        "https://mseeowpma.cc",      // 6
        "http://mseeowpm1.xyz",      // 7
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

    /**
     * 查找某 host 对应的 IP 列表。
     * 优先级：customRule > STATIC_IP。
     * @return IP 列表，空表示无预设（走系统 DNS）。
     */
    fun resolveIp(host: String): List<String> {
        customRule[host]?.let { if (it.isNotEmpty()) return it }
        return STATIC_IP[host].orEmpty()
    }
}
