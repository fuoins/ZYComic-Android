package com.zycomic.app.net

import android.util.Log
import com.zycomic.app.data.dto.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 线路 / 图域测速。
 *
 * 关键一致性原则：测速使用的 OkHttpClient 传输层（DNS/SNI/证书/出口/cookie）由
 * [NetworkModule.applyTransportStrategy] 统一构建，与当前网络下的运行时 api/image client
 * 完全一致—— VPN 开时测速即走系统 DNS+SNI 的 VPN 路径，无 VPN 时走 RuleDns+去 SNI 直连。
 *
 * 探测分三类（都打"真实业务数据"，避免假 200 蒙混）：
 * 1. **线路探活**：逐域，打签名+解密全链路的分类接口；
 * 2. **图源按机器测速**（启动/自动路径）：探 IP 并集，域名分数由 min 派生，
 *    见 [IpProbePlanner] —— 103 个域只落在 15 个 IP 上，逐域测是在间接测机器；
 * 3. **逐域测活**（手动入口 [revalidateImgHosts]）：每域只用"运行时会拨的那个 IP"打一次，
 *    回答"能不能取到图"，结果每域一行写进日志供人工更新 ruledns。
 
 * - 线路探活：[NetworkModule.newLineProbeClient]（挂 [ManwaInterceptor]）打分类接口
 *   `/api/classes/index`，完整经过 追加通用参数 → 签 devid/X-Token → gzip → AES 解密，
 *   仅当 2xx **且解密后是合法 JSON** 才计成功；
 * - 图域/图源探活：打固定真实封面（[PROBE_COVER_PATH]，带 Range 只取前 2KB），
 *   仅当 200/206 且确为图片/非文本二进制才计成功：VPN 下返回 403/HTML 挑战页的图域会被自然淘汰。
 *   但**可达性**与**取到图**是分开的两件事：拿到任何 HTTP 响应就证明该机器可达（延迟照记），
 *   只有连接层失败才判不可达 —— 见 [ProbeOutcome.elapsedMs]。
 *
 * 线程约束（重要）：本对象所有对外方法都是 suspend 且**整体在 [Dispatchers.IO] 上执行**。
 * 之前只有单次测量走 IO，而阶段 1 选完线路后的 `NetworkModule.rebuild()` 留在调用方线程，
 * 冷启动时调用方是主线程 `LaunchedEffect`，`evictAll()` 关 socket 直接抛
 * `NetworkOnMainThreadException` → 进程 12 秒必崩，图域探活根本没机会跑，UI 上表现成
 * "所有图源都超时"。所以这里绝不能再把任何网络/重建调用留在调用方 dispatcher 上。
 */
object SpeedTester {

    private const val TAG = "SpeedTester"
    private const val MAX = Long.MAX_VALUE

    /** 探活只取前 2KB，避免逐域拉整张图/整个 JSON。 */
    private const val PROBE_BYTES = 2048L

    /**
     * 线路探活路径：分类接口首页（与抓包、[ApiService.classes] 一致）。
     * `facility/deviceid/timestamp` 与 `devid/X-Token` 头由 [ManwaInterceptor] 追加，
     * **不要在这里手写**，否则会出现重复 query 且时间戳与签名不一致。
     */
    private const val LINE_PROBE_PATH =
        "/api/classes/index?page=1&gender=2&tag=&area=0&end=0&has_full=0&level=0&st=2&orderBy=0"

    /**
     * 图域探活路径：固定真实封面（抓包证实 `newmwtuyuan8.cc` 上返回
     * 200 / image/jpeg / 13842 字节 / Accept-Ranges: bytes / 明文 JPEG）。
     * 选它是因为所有图域都应当镜像同一批 `/static/upload*` 资源，且路径不随业务数据变化。
     */
    private const val PROBE_COVER_PATH = "/static/upload2/book/id/126811/cover_1.jpg"

    /** 取样本封面路径的独立上限：超过就用历史缓存，不能让启动流程等它。 */
    private const val SAMPLE_PATH_TIMEOUT_MS = 6_000L

    /** 防止启动测速与网络变化重测速并发重叠。 */
    private val running = AtomicBoolean(false)

    /**
     * 探测并发上限。**故意小于 OkHttp Dispatcher 的默认 64**：
     * 一次 async 全部目标时，超上限的请求会在 `dispatcher.executed()` 里阻塞，而 [probe]
     * 的计时起点在 `execute()` 之前 —— 等槽位的时间会被算进延迟，排在后面的目标被系统性测慢。
     * 自己限流之后，等待时间还能单独量出来（见 [ProbeTrace.queueWaitMs]）。
     */
    private const val PROBE_CONCURRENCY = 16

    private val probeGate = kotlinx.coroutines.sync.Semaphore(PROBE_CONCURRENCY)

    /**
     * 逐域**测活**（不做速度排名，不进启动路径）：每个域名只用"运行时会拨的那个 IP"
     * —— 即 `orderedIpsFor(domain, ruleIps).first()` —— 打一次真实封面路径。
     *
     * 为什么必须钉 IP：不钉的话结论来自系统 DNS 随便给的一台机器，和实际取图走的机器不是
     * 同一台，那份"可用/不可用"就没法用来指导配置更新。
     *
     * 日志每域一行（[ProbeLog.line]），字段直接对应 doh.json：域名、有没有 IP 组、
     * 拨到哪个 IP、结论、状态码、延迟。没有 IP 组的域额外做一次 DoH，把解析到的 v4 地址
     * 一起打出来，照着就能补 `rule` 条目。
     *
     * @return 图源 index -> 结论
     */
    suspend fun revalidateImgHosts(): Map<Int, ImgLiveness> = withContext(Dispatchers.IO) {
        coroutineScope {
            val client = NetworkModule.newSpeedTestClient()
            val samplePath = RouteManager.loadProbeImgPath()
            val rule = DevConfig.getRule()
            val out = java.util.concurrent.ConcurrentHashMap<Int, ImgLiveness>()
            val delays = java.util.concurrent.ConcurrentHashMap<Int, Long>()
            RouteManager.clearImgDomainCooldown()   // 新一轮探测：先清上一轮的失败标记
            try {
                lastImgVerdicts = emptyMap()
                RouteManager.imgDomains.mapIndexed { index, domain ->
                    async {
                        val trace = ProbeTrace(domain)
                        val waitStart = System.nanoTime()
                        probeGate.acquire()
                        val waited = (System.nanoTime() - waitStart) / 1_000_000L
                        if (waited > 0) trace.queueWaitMs = waited
                        try {
                            val dialIp = rule[domain]?.let { RouteManager.orderedIpsFor(domain, it).firstOrNull() }
                            trace.dialedAddress = dialIp?.let { "$it:443" }
                            val target = if (dialIp != null) {
                                NetworkModule.newPinnedIpProbeClient(dialIp, base = client)
                            } else {
                                client
                            }
                            val r = measureImg(target, domain, samplePath)
                            out[index] = ImgLiveness(r.delayMs, r.verdict)
                            delays[index] = r.delayMs
                            val doh = if (dialIp == null) dohARecords(domain) else emptyList()
                            Log.i(
                                TAG,
                                ProbeLog.line(
                                    host = domain,
                                    inRule = rule.containsKey(domain),
                                    dialedIp = trace.dialedIp,
                                    verdict = r.verdict,
                                    httpCode = r.httpCode,
                                    delayMs = r.delayMs,
                                ) + " queue=${trace.queueWaitMs}ms doh=${if (doh.isEmpty()) "-" else doh}",
                            )
                        } finally {
                            probeGate.release()
                        }
                    }
                }.awaitAll()
            } finally {
                client.releaseProbe()
            }
            val sorted = out.toSortedMap()
            lastImgVerdicts = sorted.mapValues { it.value.verdict }
            // 逐域实测结果写回延迟表：测活之后列表里的数字/排序才是真实可用性，
            // 而不是按机器派生的那一套
            RouteManager.setLastDelays(RouteManager.lastLineDelays, delays.toSortedMap())
            val vc = sorted.values.map { it.verdict }.groupingBy { it }.eachCount()
            Log.i(
                TAG,
                "revalidate done total=${sorted.size} ok=${vc[ImgVerdict.OK] ?: 0}" +
                    " unreachable=${vc[ImgVerdict.UNREACHABLE] ?: 0} no_image=${vc[ImgVerdict.NO_IMAGE] ?: 0}",
            )
            sorted
        }
    }

    /** 一次逐域测活的结果：延迟（不可用为 [MAX]）+ 三态结论。 */
    data class ImgLiveness(val delayMs: Long, val verdict: ImgVerdict)

    /**
     * 没有 IP 组的域名做一次阿里云 DoH 查 A 记录，只为把这行日志写成可执行的配置补丁。
     * 失败返回空表（测活本身不受影响）。
     */
    private suspend fun dohARecords(domain: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://dns.alidns.com/resolve?name=$domain&type=A")
                .get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                DohResolver.parseARecords(resp.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    suspend fun testSingleImgHost(domain: String): Long = withContext(Dispatchers.IO) {
        val client = NetworkModule.newSpeedTestClient()
        try {
            measureImg(client, domain, RouteManager.loadProbeImgPath()).delayMs
        } finally {
            client.releaseProbe()
        }
    }

    suspend fun testSingleLine(lineUrl: String): Long = withContext(Dispatchers.IO) {
        val client = NetworkModule.newLineProbeClient()
        try {
            measureLineOnce(client, lineUrl)
        } finally {
            client.releaseProbe()
        }
    }

    fun selectFastestLine(delays: Map<Int, Long>): Int =
        delays.filterValues { it < MAX }.minByOrNull { it.value }?.key ?: 0

    // ==================== 测速 + 应用（启动 / 设置页 / 网络变化三处复用）====================

    /**
     * 两阶段测速并应用结果：
     * 1) 线路探活（走签名解密的分类接口）→ 选最快线路并重建 Retrofit；
     * 2) 取兜底封面路径 → 图域探活（固定封面）→ 选最快图域；
     * 3) 成功才替换当前线路/图域；全部失败保留现状。
     *
     * 全程在 IO：内部的 [NetworkModule.rebuild] 是 suspend 且固定在 IO 串行执行，
     * 取样本与写 prefs 也都在 IO，任何调用方（含主线程 `LaunchedEffect`）都不会触发
     * `NetworkOnMainThreadException`。
     *
     * @param onProgress 每出一条结果回调一次（线路 Map、图域 Map 的不可变快照），供 UI 逐条刷新。
     * @return (最快线路 index, 最快图域 index)，失败项为 -1。已有测速在跑时直接返回 (-1,-1)。
     */
    /**
     * [forceFullProbe] 放在倒数第二位、[onProgress] 保持在最后：
     * 这样三处调用点的尾随 lambda 仍然绑到 onProgress（Kotlin 尾随 lambda 只认最后一个参数）。
     */
    suspend fun runAndApply(
        forceFullProbe: Boolean = false,
        onProgress: ((line: Map<Int, Long>, img: Map<Int, Long>) -> Unit)? = null,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        coroutineScope {
            if (!running.compareAndSet(false, true)) {
                lastRunSkipped = true
                Log.w(TAG, "runAndApply skipped: another speed test is running（本轮一个域都没探）")
                return@coroutineScope (-1 to -1)
            }
            lastRunSkipped = false
            // 本轮开始：清空上一轮的图源失败标记（标记改为"本轮内永久"，靠这里整表重置）。
            // 被防重入丢弃的那轮走不到这里，所以不会白白丢掉已有标记。
            RouteManager.clearImgDomainCooldown()
            // 本轮结论先清空：中途被取消/超时时，UI 会看到"没有结论"而不是上一轮的旧结论
            lastImgVerdicts = emptyMap()
            val startedAt = System.currentTimeMillis()
            val lineClient = NetworkModule.newLineProbeClient()
            try {
                // 阶段 1：线路（签名+解密全链路的分类接口）
                val lineAcc = java.util.concurrent.ConcurrentHashMap<Int, Long>()
                val lineJobs = RouteManager.lineHosts.mapIndexed { index, lineUrl ->
                    async {
                        val v = measureLineOnce(lineClient, lineUrl)
                        lineAcc[index] = v
                        onProgress?.invoke(lineAcc.toSortedMap(), emptyMap())
                        index to v
                    }
                }
                val lines = lineJobs.awaitAll().toMap()
                val bestLine = lines.filterValues { it < MAX }.minByOrNull { it.value }?.key
                if (bestLine != null) {
                    RouteManager.setLine(bestLine)
                    RouteManager.saveLastFastestLine(bestLine)
                    // 让阶段 2 取样本走最快线路：只重建 API 侧，不打断正在下载的图片
                    NetworkModule.rebuildApi("测速选出线路")
                }
                Log.i(TAG, "line test done, best=$bestLine")

                // 阶段 2：兜底样本路径（固定封面 404 时才用到）
                val samplePath = obtainProbePath()
                Log.i(TAG, "img probe sample path=$samplePath")

                // 阶段 3：按机器（IP）测速，再把分数派生给全部图源域名。
                //
                // 原实现是逐域各发一次探活：线上配置 103 个图源域名其实只落在 15 个 IP 上，
                // 而 RuleDns 按列表顺序返回、OkHttp 只拨第一个连得上的 IP —— 于是 103 次请求
                // 测到的是"每个域第 0 号 IP 的分数"，用最贵的方式间接测 15 台机器。
                // 现在：探 IP 并集（增量：只补测还没有分数的 IP）→ 域名分数 = 其 IP 组最快值，
                // 零额外请求覆盖全部图源；逐域"能不能取到图"改由设置页/阅读器的测活入口提供。
                val rule = DevConfig.getRule()
                val noRule = IpProbePlanner.hostsWithoutRule(RouteManager.imgDomains, rule)
                if (noRule.isNotEmpty()) {
                    // 章节接口 / Trello 运行时学到、但 ruledns 还没下发 IP 组的域：走 Dns.SYSTEM，
                    // 拿不到 IP 分数。记进日志，供人工补进配置。
                    Log.i(TAG, "hosts without rule entry (不参与 IP 测速): $noRule")
                }
                val imgAcc = java.util.concurrent.ConcurrentHashMap<Int, Long>()
                val verdictAcc = java.util.concurrent.ConcurrentHashMap<Int, ImgVerdict>()
                val knownBefore = RouteManager.ipScoresSnapshot()
                // 先用上一轮分数派生一次：本轮即使中途被取消，UI 也不会显示成"全部超时"
                deriveDomainDelays(knownBefore).forEach { (host, ms) ->
                    RouteManager.imgDomains.indexOf(host).takeIf { it >= 0 }?.let { imgAcc[it] = ms }
                }
                onProgress?.invoke(lines, imgAcc.toSortedMap())

                val (ipDelays, ipVerdicts) = probeImgIps(forceFullProbe = forceFullProbe)
                lastIpVerdicts = ipVerdicts
                RouteManager.setLastIpDelays(ipDelays)
                val finalDerived = deriveDomainDelays(ipDelays)
                RouteManager.imgDomains.forEachIndexed { index, host ->
                    val ms = finalDerived[host] ?: MAX
                    imgAcc[index] = ms
                    // 域名级 verdict 的口径变化：本轮测的是机器，所以"有分数=这台机器能取到图"记 OK，
                    // "IP 组全不可达"记 UNREACHABLE；"机器活着但这个 vhost 没图"要靠测活入口逐域确认
                    // （见 lastIpVerdicts 里的 NO_IMAGE 与"重新测活"）。
                    verdictAcc[index] = if (ms < MAX) ImgVerdict.OK else ImgVerdict.UNREACHABLE
                }
                val imgs: Map<Int, Long> = imgAcc.toSortedMap()
                lastImgVerdicts = verdictAcc.toSortedMap()
                onProgress?.invoke(lines, imgs)
                RouteManager.saveIpScores(
                    scores = ipDelays,
                    networkKey = RouteManager.currentNetworkKey(),
                    directStrategy = NetworkModule.shouldUseDirectStrategy(),
                )
                Log.i(
                    TAG,
                    "ip-layer test done ips=${ipDelays.size} reused=${knownBefore.size} " +
                        "ok=${ipVerdicts.values.count { it == ImgVerdict.OK }} " +
                        "unreachable=${ipVerdicts.values.count { it == ImgVerdict.UNREACHABLE }} " +
                        "noImage=${ipVerdicts.values.count { it == ImgVerdict.NO_IMAGE }}",
                )

                // 结果一次性写入，fastestImgDomain 据此派生
                RouteManager.setLastDelays(lines, imgs)
                val bestImg = imgs.filterValues { it < MAX }.minByOrNull { it.value }?.key
                // 自动选出的图源只更新 imgIndex，**不 pin**：pin 专属于用户手动选择。
                val imgDomainChanged = bestImg != null && bestImg != RouteManager.imgIndex
                if (imgDomainChanged && bestImg != null) {
                    RouteManager.setImgHost(bestImg)
                }
                // 按侧清理：线路变了只重建 API 侧；图源变了只驱逐图片侧空闲连接（不 cancelAll）。
                if (bestLine != null) NetworkModule.rebuildApi("测速应用线路")
                if (imgDomainChanged) NetworkModule.rebindImageRoute("测速应用图源")
                RouteManager.markSpeedTestToday()

                lastRunAt = System.currentTimeMillis()
                lastRunDirectStrategy = NetworkModule.shouldUseDirectStrategy()

                // 汇总：把"连不上"和"连上了但没有图"分开计数，并列出最快的几个域。
                // 只有失败才打逐条日志、成功不打，是此前"看不出哪些域没测过"的直接原因。
                val vc = lastImgVerdicts.values.groupingBy { it }.eachCount()
                val domains = RouteManager.imgDomains
                val fastest = lastImgVerdicts.entries.filter { it.value == ImgVerdict.OK }
                    .mapNotNull { e -> imgs[e.key]?.let { e.key to it } }.sortedBy { it.second }.take(5)
                    .joinToString(", ") { (idx, ms) -> "${domains.getOrElse(idx) { "??" }}=${ms}ms" }
                Log.i(
                    TAG,
                    "img probe summary total=${lastImgVerdicts.size} ok=${vc[ImgVerdict.OK] ?: 0}" +
                        " unreachable=${vc[ImgVerdict.UNREACHABLE] ?: 0} no_image=${vc[ImgVerdict.NO_IMAGE] ?: 0}" +
                        " samplePath=${if (samplePath == null) "null" else "ok"} fastest=[$fastest]",
                )
                Log.i(TAG, "runAndApply done in ${System.currentTimeMillis() - startedAt}ms, " +
                    "bestLine=$bestLine bestImg=$bestImg direct=$lastRunDirectStrategy")
                (bestLine ?: -1) to (bestImg ?: -1)
            } finally {
                running.set(false)
                lineClient.releaseProbe()
            }
        }
    }

    /**
     * 探活 client 是"一次性"的（每次测速新建，保证传输策略与当时网络一致）。
     * 用完只驱逐**自己那份**连接池（每个探活 client 都在 NetworkModule 里 new 了独立
     * ConnectionPool），尽早释放占着旧网络路径的 socket。
     *
     * 故意不动 `dispatcher.executorService`：OkHttp 的 Dispatcher 在无参构造时共享同一个
     * 懒加载默认线程池，运行时 apiClient/imageClient 也在用，关掉它会让后续 Retrofit
     * 异步调用直接 RejectedExecutionException。该线程池核心线程数为 0、60s 空闲自动回收，
     * 不关也不会常驻泄漏。
     */
    private fun OkHttpClient.releaseProbe() {
        runCatching { connectionPool.evictAll() }
    }
}
