package com.zycomic.app.net

/**
 * IP 层测速的纯规划逻辑。
 *
 * 为什么要按 IP 测：线上 ruledns 配置里 103 个图源域名其实只落在 15 个 IP 上
 * （12 条线路落在 11 个 IP），而 [NetworkModule.RuleDns] 按 rule 列表顺序返回、OkHttp 只拨
 * 第一个能连上的 IP。所以"逐域测速"测到的其实是"每个域第 0 号 IP 的分数"——用 103 次请求
 * 间接地、带噪声地测 15 台机器。改成按 IP 测一次，再把分数派生给所有含该 IP 的域名，
 * 既便宜又修了口径。
 *
 * 本对象不含网络与 Android 依赖，可脱离 SDK 单测。
 */
object IpProbePlanner {

    /** 无结论 / 本轮判定不可用。 */
    const val UNAVAILABLE: Long = Long.MAX_VALUE

    /** 一次 IP 探测目标：打哪个 IP，以及用哪些真实域名作代表（Host/SNI 保持真实）。 */
    data class Target(val ip: String, val representatives: List<String>)

    /** 默认代表域上限：只在上一发失败时才换下一个，避免一个死 vhost 污染整台机器的分数。 */
    const val DEFAULT_MAX_REPRESENTATIVES = 3

    /** 参与本轮的域名 -> IP 并集（保序去重）。 */
    fun uniqueIps(hosts: List<String>, rule: Map<String, List<String>>): List<String> =
        hosts.flatMap { rule[it].orEmpty() }.filter { it.isNotBlank() }.distinct()

    /**
     * 该 IP 的代表域名：按 [hosts] 的给定顺序取前 [maxRepresentatives] 个包含该 IP 的域。
     *
     * 顺序即优先级（当前图源列表把官方在用源排在前面），失败时按序回退。
     */
    fun representativesFor(
        ip: String,
        hosts: List<String>,
        rule: Map<String, List<String>>,
        maxRepresentatives: Int = DEFAULT_MAX_REPRESENTATIVES,
    ): List<String> = hosts
        .filter { host -> rule[host]?.contains(ip) == true }
        .take(maxRepresentatives.coerceAtLeast(1))

    /**
     * 规划本轮要探的 IP。
     *
     * - [forceFullProbe] = true（用户点"重新测速"）：全部重测。
     * - 否则只探**还没有分数**的 IP —— 新配置刚下发一批新 IP 时天然只补测这些新 IP，
     *   不需要额外的"IP 集合签名"记账。
     */
    fun plan(
        hosts: List<String>,
        rule: Map<String, List<String>>,
        knownScores: Map<String, Long>,
        forceFullProbe: Boolean,
        maxRepresentatives: Int = DEFAULT_MAX_REPRESENTATIVES,
    ): List<Target> =
        uniqueIps(hosts, rule)
            .filter { forceFullProbe || !knownScores.containsKey(it) }
            .map { Target(it, representativesFor(it, hosts, rule, maxRepresentatives)) }
            .filter { it.representatives.isNotEmpty() }

    /** 域名分数 = 其 IP 组里**已测出**的最快值；一个都没测到则 [UNAVAILABLE]（不是 0）。 */
    fun scoreForDomain(ips: List<String>, scores: Map<String, Long>): Long {
        val measured = ips.mapNotNull { scores[it] }.filter { it < UNAVAILABLE }
        return measured.minOrNull() ?: UNAVAILABLE
    }

    /** 把 IP 分数派生成「域名 -> 延迟」，用于替代逐域探测的结果表。 */
    fun domainDelays(
        hosts: List<String>,
        rule: Map<String, List<String>>,
        scores: Map<String, Long>,
    ): Map<String, Long> = hosts.associateWith { scoreForDomain(rule[it].orEmpty(), scores) }

    /** 丢掉已从配置里消失的 IP 分数，避免陈旧条目一直占位或被排进拨号顺序。 */
    fun prune(scores: Map<String, Long>, validIps: List<String>): Map<String, Long> {
        val valid = validIps.toSet()
        return scores.filterKeys { it in valid }
    }

    /**
     * 没有 IP 组的域名（章节接口/Trello 运行时学到、但 ruledns 还没下发的域）。
     * 这些域走 `Dns.SYSTEM` 兜底，拿不到 IP 分数，只能单独记录，等人工补进配置。
     */
    fun hostsWithoutRule(hosts: List<String>, rule: Map<String, List<String>>): List<String> =
        hosts.filter { rule[it].isNullOrEmpty() }

    /**
     * 是否落在 Cloudflare 公布的 IPv4 段（anycast）。
     *
     * 用途有两个：1) 这类地址必须保留 SNI（见 [NetworkModule] 的 `isInCloudflare` 豁免）；
     * 2) 多个 CF IP 之间测出的差异是"本机到 CF 边缘节点"的抖动，不代表不同机器，
     * 所以排序/展示时要单独对待。段表与 [NetworkModule] 保持一致。
     */
    fun isCloudflareAnycast(ip: String): Boolean {
        val value = ipv4ToLong(ip) ?: return false
        return CLOUDFLARE_V4.any { (net, bits) ->
            val mask = if (bits == 0) 0L else (-1L shl (32 - bits)) and 0xFFFFFFFFL
            (value and mask) == (net and mask)
        }
    }

    /**
     * 按分数升序排一个域名的 IP 列表（供 [NetworkModule.RuleDns] 返回前调用）。
     *
     * 未测的 IP 排在**所有已测 IP 之后**（而不是丢弃）：`retryOnConnectionFailure` 只会在
     * 连不上时才跳下一个 IP，所以保留它们仍然能当回退用。同分数/同为未测时保持原顺序，
     * 结果稳定可复现。
     */
    fun orderIps(ips: List<String>, scores: Map<String, Long>): List<String> =
        ips.sortedBy { scores[it] ?: UNAVAILABLE }

    /** 是否为合法点分 IPv4（供 DoH 结果过滤、CF 段判定共用同一口径）。 */
    fun isIpv4(ip: String): Boolean = ipv4ToLong(ip) != null

    private fun ipv4ToLong(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || p.any { !it.isDigit() }) return null
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            value = (value shl 8) or n.toLong()
        }
        return value
    }

    /** Cloudflare 官方公布的 IPv4 段（与 [NetworkModule] 的列表同源，改动需同步）。 */
    private val CLOUDFLARE_V4: List<Pair<Long, Int>> = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.15.192.0/18", "197.234.240.0/22",
        "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14",
        "172.64.0.0/13",
    ).map { spec ->
        val (addr, bits) = spec.split('/')
        val net = addr.split('.').fold(0L) { acc, o -> (acc shl 8) or (o.toLong() and 0xFF) }
        net to bits.toInt()
    }
}

/**
 * IP 分数表：纯状态 + 序列化，落盘与读取由调用方（`RouteManager`）负责。
 *
 * 有意**不写进 `config_json`**：远程 ruledns 更新会整体覆盖 rule，
 * `mergeBaselineConfigIfNeeded` 也以基线为准，学到的分数塞在那里一定会被冲掉。
 */
class IpScoreStore {

    private val _scores = LinkedHashMap<String, Long>()

    /** 复制出来的分数快照（ip -> 延迟，[UNAVAILABLE] 表示本轮判定不可用）。 */
    val scores: Map<String, Long> get() = _scores.toMap()

    /** 写入时的网络身份（网络签名 + 直连策略等），换网络即失效。 */
    var networkKey: String? = null

    /** 写入时是否处于直连策略：VPN 模式走 `Dns.SYSTEM`，IP 顺序完全不参与，分数无意义。 */
    var directStrategy: Boolean? = null

    /** 写入时刻（elapsedRealtime 或 currentTimeMillis，由调用方统一口径）。 */
    var savedAt: Long = 0L

    fun put(ip: String, delayMs: Long) {
        if (ip.isBlank()) return
        _scores[ip] = delayMs
    }

    fun replaceAll(next: Map<String, Long>) {
        _scores.clear()
        _scores.putAll(next)
    }

    /** 分数是否还能用：必须有数据、网络身份一致、直连策略一致、且没过期。 */
    fun isValidFor(
        networkKey: String?,
        directStrategy: Boolean,
        now: Long,
        ttlMs: Long,
    ): Boolean {
        if (_scores.isEmpty()) return false
        if (networkKey == null || this.networkKey != networkKey) return false
        if (this.directStrategy != directStrategy) return false
        return (now - savedAt) in 0..ttlMs
    }

    fun toPersistentString(): String = buildString {
        append(LINE_KEY).append('=').append(networkKey?.sanitize().orEmpty()).append('\n')
        append(LINE_DIR).append('=').append(directStrategy?.let { if (it) "1" else "0" }.orEmpty()).append('\n')
        append(LINE_AT).append('=').append(savedAt).append('\n')
        _scores.forEach { (ip, ms) -> append(ip.sanitize()).append('=').append(ms).append('\n') }
    }

    companion object {

        const val UNAVAILABLE: Long = IpProbePlanner.UNAVAILABLE

        private const val LINE_KEY = "nk"
        private const val LINE_DIR = "dir"
        private const val LINE_AT = "at"

        /** 换行会破坏行式格式，落盘前统一替换掉。 */
        private fun String.sanitize(): String = replace('\n', ' ').replace('\r', ' ')

        /** 解析失败一律退化成空表（宁可重测，也不要用脏数据）。 */
        fun fromPersistentString(raw: String?): IpScoreStore {
            val store = IpScoreStore()
            if (raw.isNullOrBlank()) return store
            raw.split('\n').forEach { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val k = line.substring(0, idx)
                val v = line.substring(idx + 1)
                when (k) {
                    LINE_KEY -> store.networkKey = v.takeIf { it.isNotEmpty() }
                    LINE_DIR -> store.directStrategy = when (v) {
                        "1" -> true
                        "0" -> false
                        else -> null
                    }
                    LINE_AT -> store.savedAt = v.toLongOrNull() ?: 0L
                    else -> {
                        val ms = v.toLongOrNull()
                        if (ms != null && ms >= 0) store.put(k, ms)
                    }
                }
            }
            return store
        }
    }
}
