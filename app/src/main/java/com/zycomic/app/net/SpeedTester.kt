package com.zycomic.app.net

import android.util.Log
import com.zycomic.app.data.dto.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * 两类探活都打"真实业务数据"，避免假 200 蒙混：
 * - 线路探活：[NetworkModule.newLineProbeClient]（挂 [ManwaInterceptor]）打分类接口
 *   `/api/classes/index`，完整经过 追加通用参数 → 签 devid/X-Token → gzip → AES 解密，
 *   仅当 2xx **且解密后是合法 JSON** 才计成功；
 * - 图域探活：先打固定真实封面（[PROBE_COVER_PATH]，带 Range 只取前 2KB），
 *   仅当 200/206 且确为图片/非文本二进制才计成功：VPN 下返回 403/HTML 挑战页的图域会被自然淘汰。
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
    private val probeSemaphore = Semaphore(24)

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
    private val configRunLock = Mutex()

    @Volatile private var lastCompletedConfigRevision: Long = -1L

    @Volatile private var lastConfigRunResult: Pair<Int, Int> = -1 to -1

    /** 最近一次测速完成时间与当时的直连策略，供网络回调判断是否需要重测。 */
    @Volatile var lastRunAt: Long = 0L
        private set

    @Volatile var lastRunDirectStrategy: Boolean? = null
        private set

    // ==================== 请求构造 ====================

    /** 线路探活请求：只给业务接口基础 query，签名与通用参数交给 [ManwaInterceptor]。 */
    private fun lineRequest(lineUrl: String): Request =
        Request.Builder()
            .url(lineUrl.trimEnd('/') + LINE_PROBE_PATH)
            .get()
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", ManwaInterceptor.ACCEPT_LANGUAGE)
            .build()

    /**
     * 图域探活请求：[probePath] 为封面/样本相对路径时打该 URL（带 Range 只取前 2KB），
     * 传 null 表示根路径兜底（仍按图片/非文本严格判负）。
     *
     * Referer 用**固定 canonical 域** [ManwaInterceptor.REFERER]，不用"当前线路 origin"：
     * 抓包证实官方 App 无论打哪个线路/图域，Origin/Referer 恒为 mseeowpm1.xyz，
     * 防盗链白名单按此校验；用动态线路 origin（.pro/.cc）反而会被 403。
     */
    private fun imgProbeRequest(domain: String, probePath: String?): Request {
        val builder = Request.Builder()
            .get()
            .header("User-Agent", ManwaInterceptor.UA)
            .header("X-Requested-With", "zycomic.com")
            .header("Referer", ManwaInterceptor.REFERER)
            .header("Accept", "image/*,*/*;q=0.8")
            .header("Connection", "keep-alive")
        if (probePath != null) {
            builder.url("https://$domain$probePath")
            builder.header("Range", "bytes=0-${PROBE_BYTES - 1}")
        } else {
            builder.url("https://$domain/")
        }
        return builder.build()
    }

    // ==================== 单次测量 ====================

    /** 一次探活的结果：[delayMs] 为 [MAX] 表示判负；[httpCode] 为 -1 表示根本没拿到响应。 */
    private data class ProbeOutcome(val delayMs: Long, val httpCode: Int)

    /** 图域探活结论，用于把"连不上"和"连上了但拿不到图"分开——UI 上一律显示"超时"会掩盖两种完全不同的问题。 */
    enum class ImgVerdict { OK, UNREACHABLE, NO_IMAGE }

    /** 图域探活结论 + 延迟。 */
    private data class ImgProbe(val delayMs: Long, val verdict: ImgVerdict)

    /** 最近一轮图域探活的逐域结论（index -> 结论），供 UI/排查区分"没测/连不上/无图"。 */
    @Volatile var lastImgVerdicts: Map<Int, ImgVerdict> = emptyMap()
        private set

    /** Per-domain/IP image verdicts from the latest pass. */
    @Volatile var lastImgIpVerdicts: Map<DomainIp, ImgVerdict> = emptyMap()
        private set

    /** 最近一次 runAndApply 是否因为"已有测速在跑"被整轮丢弃（被丢弃时一个域都没探）。 */
    @Volatile var lastRunSkipped: Boolean = false
        private set

    /** 线路探活：2xx **且解密后 body 是 JSON 对象** 才计成功。 */
    private suspend fun measureLine(client: OkHttpClient, req: Request): Long =
        probe(client, req, requireJson = true, strictImage = false).delayMs

    /**
     * 图域探活（含回退）：固定封面 → 失败时换取样路径再试。
     *
     * 回退条件按真机日志修正为**"只要不是连接层失败就回退"**：这批 CDN 对不存在/已废弃的对象
     * 并不回 404，而是回 `3xx`（域名被回收做跳转站）或 `2xx + text/html`（默认虚拟主机错误页）。
     * 上一版只在 HTTP 404 时回退，实测一轮里 404 出现 0 次 → 回退分支一次都没被触发，
     * 会把"其实可用、只是没镜像这个对象"的域全部误杀。
     *
     * 反之，**连接层失败（拿不到任何响应）不回退**：域都不通，再打两个路径只是白等两个超时。
     */
    private suspend fun measureImg(client: OkHttpClient, domain: String, samplePath: String?): ImgProbe {
        val candidates = buildList {
            add(PROBE_COVER_PATH)
            samplePath?.takeIf { it != PROBE_COVER_PATH }?.let { add(it) }
        }
        var sawConnectionFailure = false
        var lastCode = -1
        for ((i, path) in candidates.withIndex()) {
            val outcome = probe(client, imgProbeRequest(domain, path), requireJson = false, strictImage = true)
            if (outcome.delayMs < MAX) {
                if (i > 0) Log.i(TAG, "img probe fallback ok domain=$domain path=$path")
                return ImgProbe(outcome.delayMs, ImgVerdict.OK)
            }
            if (outcome.httpCode == -1) {
                sawConnectionFailure = true
                break
            }
            lastCode = outcome.httpCode
        }
        val verdict = if (sawConnectionFailure) ImgVerdict.UNREACHABLE else ImgVerdict.NO_IMAGE
        if (verdict == ImgVerdict.NO_IMAGE) Log.d(TAG, "img no-image domain=$domain lastCode=$lastCode")
        return ImgProbe(MAX, verdict)
    }

    private suspend fun measureLineOnce(client: OkHttpClient, lineUrl: String): Long =
        measureLine(client, lineRequest(lineUrl))

    /**
     * 通用单次探活。计时口径：从发起请求到**读完响应头 2KB**——线路探活时
     * [ManwaInterceptor] 是应用拦截器，`execute()` 返回前已完成 gzip 解压与 AES 解密，
     * 所以这个耗时天然包含"解密完成"，反映的是真实 API 链路。
     */
    private suspend fun probe(
        client: OkHttpClient,
        req: Request,
        requireJson: Boolean,
        strictImage: Boolean,
    ): ProbeOutcome =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            var code = -1
            try {
                client.newCall(req).execute().use { resp ->
                    code = resp.code
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    val head = readHead(resp, PROBE_BYTES)
                    val ok = code in 200..299 && when {
                        requireJson -> isJsonLike(head)
                        else -> !strictImage || isImageLike(contentType, head)
                    }
                    if (ok) {
                        ProbeOutcome((System.nanoTime() - start) / 1_000_000, code)
                    } else {
                        Log.d(
                            TAG,
                            "probe reject url=${req.url} code=$code ct=$contentType len=${head.size}",
                        )
                        ProbeOutcome(MAX, code)
                    }
                }
            } catch (e: Exception) {
                // 状态码已拿到就如实带上（404 后能否回退取样路径依赖它）
                Log.d(TAG, "probe fail url=${req.url} code=$code err=${e.message}")
                ProbeOutcome(MAX, code)
            }
        }

    /**
     * 读取响应前 [maxBytes] 字节。
     *
     * 用 `Source.read(sink, n)` 而不是 `BufferedSource.readByteArray(n)`：后者要求**凑满** n 字节，
     * 遇到比 n 短的响应（小封面、几十~上百字节的错误 JSON）会抛 EOFException，
     * 把"其实成功/确实是 404"误判成"网络异常"，进而丢掉图域探活的 404 回退逻辑。
     */
    private fun readHead(resp: okhttp3.Response, maxBytes: Long): ByteArray =
        runCatching {
            resp.body?.source()?.use { src ->
                val buf = Buffer()
                src.read(buf, maxBytes)
                buf.readByteArray()
            } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))

    /** 解密后的线路响应必须是 JSON 对象（被重置/未鉴权/假 200 的 HTML 或空 body 一律判负）。 */
    private fun isJsonLike(head: ByteArray): Boolean =
        String(head, Charsets.UTF_8).trimStart().startsWith("{")

    /**
     * 判断探活响应是否像图片：图片 content-type / 图片魔数直接通过；
     * text/html/json/xml/javascript 或 <、{、[ 开头的拦截页判负；其余高熵二进制（AES 密文）通过。
     */
    private fun isImageLike(contentType: String, head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        if (contentType.contains("text") || contentType.contains("html") ||
            contentType.contains("json") || contentType.contains("xml") ||
            contentType.contains("javascript")
        ) {
            return false
        }
        if (hasImageMagic(head)) return true
        val first = head[0].toInt() and 0xFF
        if (first == '<'.code || first == '{'.code || first == '['.code) return false
        // 加密图片为高熵二进制：含 NUL/控制字节即视为非文本二进制
        return head.any {
            val v = it.toInt() and 0xFF
            v == 0 || v in 1..8 || v in 14..31
        }
    }

    private fun hasImageMagic(b: ByteArray): Boolean {
        if (b.size < 4) return false
        if (b[0] == 0x52.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() && b[3] == 0x46.toByte()) return true
        if (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()) return true
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return true
        if (b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() && b[3] == 0x38.toByte()) return true
        return false
    }

    // ==================== 真实探活样本路径（固定封面 404 时的兜底）====================

    /**
     * 获取兜底用封面相对路径：先用当前线路调首页 index 取一张封面（picx/pic），
     * 成功则持久化；失败回退上次缓存路径，再没有返回 null。
     *
     * 这条路径是"固定封面在某域不存在"时唯一的回退目标，拿不到就等于回退链是空的，
     * 因此每个失败原因都要打出来（上一版整条链静默吞异常，真机日志里只看到
     * `img probe sample path=null`，完全查不出是接口没通、还是字段为空）。
     */
    private suspend fun obtainProbePath(): String? {
        // 整轮测速已经没有"整体超时"了，这一步必须自带上限：api.index() 用的是运行时
        // apiClient（readTimeout 30s），网络半死不活时能把启动卡住半分钟。
        // 6s 内拿不到样本就退到历史缓存路径，绝不拖住进入主页。
        val resp = runCatching { withTimeoutOrNull(SAMPLE_PATH_TIMEOUT_MS) { NetworkModule.api.index() } }
        if (resp.isFailure) {
            Log.w(TAG, "probe sample path: index() 请求失败 err=${resp.exceptionOrNull()?.message}")
        }
        val index = resp.getOrNull()
        if (resp.isSuccess && index == null) {
            Log.w(TAG, "probe sample path: index() 在 ${SAMPLE_PATH_TIMEOUT_MS}ms 内没返回，改用历史缓存")
        }
        val data: Manga? = index?.data
        if (index != null && data == null) {
            Log.w(TAG, "probe sample path: index() 成功但 data 为空（可能解密/解析失败）")
        }
        val live = data?.let { d ->
            buildList {
                add(d)
                addAll(d.loveList)
            }.mapNotNull { m -> toImagePath(m.picx.ifBlank { m.pic }) }.firstOrNull()
        }
        if (live == null && data != null) {
            Log.w(TAG, "probe sample path: index() 有数据但所有条目的 picx/pic 都是空/无法解析")
        }
        if (live != null) {
            RouteManager.saveProbeImgPath(live)
            Log.i(TAG, "probe sample path: 取到新的样本路径 $live")
            return live
        }
        val cached = RouteManager.loadProbeImgPath()
        if (cached == null) {
            Log.w(TAG, "probe sample path: 本地也没有历史样本路径，图域探活只能依赖固定封面路径")
        }
        return cached
    }

    /** 封面字段（完整 URL 或相对路径）→ 以 '/' 开头的相对路径。 */
    private fun toImagePath(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val slash = raw.indexOf('/', startIndex = 8)
            return if (slash >= 0) raw.substring(slash) else null
        }
        return if (raw.startsWith("/")) raw else "/$raw"
    }

    // ==================== 对外：全量/单点 ====================

    private data class LineTarget(val endpoint: DomainIp, val url: String)

    private fun lineDomain(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun lineTargets(): List<LineTarget> = RouteManager.lineHosts.flatMap { url ->
        val domain = lineDomain(url) ?: return@flatMap emptyList()
        NetworkModule.resolveProbeIps(domain).map { ip -> LineTarget(DomainIp(domain, ip), url) }
    }.distinctBy { it.endpoint }

    private fun imgTargets(domains: List<String> = RouteManager.imgDomains): List<DomainIp> = domains.flatMap { domain ->
        NetworkModule.resolveProbeIps(domain).map { ip -> DomainIp(domain, ip) }
    }.distinct()

    private suspend fun measureLineAtIp(client: OkHttpClient, target: LineTarget): Long {
        val routed = NetworkModule.newProbeClientForIp(client, target.endpoint.domain, target.endpoint.ip)
        return try {
            measureLineOnce(routed, target.url)
        } finally {
            routed.releaseProbe()
        }
    }

    private suspend fun measureImgAtIp(client: OkHttpClient, endpoint: DomainIp, samplePath: String?): ImgProbe {
        val routed = NetworkModule.newProbeClientForIp(client, endpoint.domain, endpoint.ip)
        return try {
            measureImg(routed, endpoint.domain, samplePath)
        } finally {
            routed.releaseProbe()
        }
    }

    /** Probe each currently configured or DNS-resolved API IP once. */
    suspend fun testAllLineEndpoints(
        onResult: ((DomainIp, Long) -> Unit)? = null,
    ): Map<DomainIp, Long> = withContext(Dispatchers.IO) {
        val client = NetworkModule.newLineProbeClient()
        try {
            val targets = lineTargets()
            coroutineScope {
                targets.map { target ->
                    async {
                        probeSemaphore.withPermit {
                            val delay = measureLineAtIp(client, target)
                            onResult?.invoke(target.endpoint, delay)
                            target.endpoint to delay
                        }
                    }
                }.awaitAll().toMap()
            }
        } finally {
            client.releaseProbe()
        }
    }

    /** Probe each image domain/IP using the same real-image validation as startup. */
    suspend fun testAllImgEndpoints(
        samplePath: String? = RouteManager.loadProbeImgPath(),
        onResult: ((DomainIp, Long, ImgVerdict) -> Unit)? = null,
        domains: List<String> = RouteManager.imgDomains,
    ): Map<DomainIp, Long> = withContext(Dispatchers.IO) {
        val client = NetworkModule.newSpeedTestClient()
        try {
            val verdicts = java.util.concurrent.ConcurrentHashMap<DomainIp, ImgVerdict>()
            val delays = coroutineScope {
                imgTargets(domains).map { endpoint ->
                    async {
                        probeSemaphore.withPermit {
                            val result = measureImgAtIp(client, endpoint, samplePath)
                            verdicts[endpoint] = result.verdict
                            lastImgIpVerdicts = verdicts.toMap()
                            onResult?.invoke(endpoint, result.delayMs, result.verdict)
                            endpoint to result.delayMs
                        }
                    }
                }.awaitAll().toMap()
            }
            lastImgIpVerdicts = verdicts.toMap()
            lastImgVerdicts = aggregateImgVerdicts(verdicts)
            delays
        } finally {
            client.releaseProbe()
        }
    }

    private fun aggregateImgVerdicts(verdicts: Map<DomainIp, ImgVerdict>): Map<Int, ImgVerdict> =
        RouteManager.imgDomains.mapIndexedNotNull { index, domain ->
            val values = verdicts.filterKeys { it.domain.equals(domain, ignoreCase = true) }.values
            if (values.isEmpty()) {
                null
            } else {
                index to when {
                    ImgVerdict.OK in values -> ImgVerdict.OK
                    ImgVerdict.NO_IMAGE in values -> ImgVerdict.NO_IMAGE
                    else -> ImgVerdict.UNREACHABLE
                }
            }
        }.toMap()

    /** Backward-compatible domain summary for older settings surfaces. */
    suspend fun testAllLines(): Map<Int, Long> {
        val results = testAllLineEndpoints()
        return RouteManager.lineHosts.mapIndexedNotNull { index, url ->
            val host = lineDomain(url) ?: return@mapIndexedNotNull null
            val values = results.filterKeys { it.domain.equals(host, ignoreCase = true) }.values
            if (values.isEmpty()) null else index to (values.filter { it < MAX }.minOrNull() ?: MAX)
        }.toMap()
    }

    /** Backward-compatible per-domain image summary. */
    suspend fun testAllImgHosts(): Map<Int, Long> {
        val results = testAllImgEndpoints()
        return RouteManager.imgDomains.mapIndexedNotNull { index, domain ->
            val values = results.filterKeys { it.domain.equals(domain, ignoreCase = true) }.values
            if (values.isEmpty()) null else index to (values.filter { it < MAX }.minOrNull() ?: MAX)
        }.toMap()
    }

    suspend fun testSingleImgHost(domain: String): Long {
        val previousDelays = RouteManager.lastImgIpDelays
        val previousVerdicts = RouteManager.lastImgIpVerdicts
        val results = testAllImgEndpoints(domains = listOf(domain))
        val newVerdicts = lastImgIpVerdicts
        val combinedDelays = previousDelays + results
        val combinedVerdicts = previousVerdicts + newVerdicts
        lastImgIpVerdicts = combinedVerdicts
        lastImgVerdicts = aggregateImgVerdicts(combinedVerdicts)
        RouteManager.setEndpointDelays(RouteManager.lastLineIpDelays, combinedDelays, combinedVerdicts)
        return results.filterKeys { it.domain.equals(domain, ignoreCase = true) }
            .values.filter { it < MAX }.minOrNull() ?: MAX
    }

    suspend fun testLineEndpoints(lineUrl: String): Map<DomainIp, Long> = withContext(Dispatchers.IO) {
        val host = lineDomain(lineUrl) ?: return@withContext emptyMap()
        val client = NetworkModule.newLineProbeClient()
        try {
            val targets = NetworkModule.resolveProbeIps(host).map { LineTarget(DomainIp(host, it), lineUrl) }
            coroutineScope {
                targets.map { target ->
                    async {
                        probeSemaphore.withPermit { target.endpoint to measureLineAtIp(client, target) }
                    }
                }.awaitAll().toMap()
            }
        } finally {
            client.releaseProbe()
        }
    }

    suspend fun testSingleLine(lineUrl: String): Long {
        val results = testLineEndpoints(lineUrl)
        return results.values.filter { it < MAX }.minOrNull() ?: MAX
    }

    fun fastestLineEndpoint(delays: Map<DomainIp, Long>): DomainIp? =
        delays.filterValues { it < MAX }.minByOrNull { it.value }?.key

    fun selectFastestLine(delays: Map<Int, Long>): Int =
        delays.filterValues { it < MAX }.minByOrNull { it.value }?.key ?: 0

    // ==================== 测速 + 应用（启动 / 设置页 / 网络变化三处复用）====================

    /**
     * 三阶段测速并应用结果：线路域名的每个 IP、图源域名的每个 IP 各自探测，
     * 只应用成功端点中的最快线路/IP与最快图源/IP。
     *
     * 全程在 IO：内部的 [NetworkModule.rebuild] 是 suspend 且固定在 IO 串行执行，
     * 取样本与写 prefs 也都在 IO，任何调用方（含主线程 `LaunchedEffect`）都不会触发
     * `NetworkOnMainThreadException`。
     *
     * @param onProgress 每出一条端点结果回调一次，供 UI 逐条刷新。
     * @return (最快线路 index, 最快图源 index)，失败项为 -1。已有测速在跑时直接返回 (-1,-1)。
     */
    suspend fun runAndApply(
        onProgress: ((line: Map<DomainIp, Long>, img: Map<DomainIp, Long>, verdicts: Map<DomainIp, ImgVerdict>) -> Unit)? = null,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        coroutineScope {
            if (!running.compareAndSet(false, true)) {
                lastRunSkipped = true
                Log.w(TAG, "runAndApply skipped: another speed test is running（本轮一个域都没探）")
                return@coroutineScope (-1 to -1)
            }
            lastRunSkipped = false
            val configRevisionAtStart = RouteManager.configRevision
            // 本轮结论先清空：中途被取消/超时时，UI 会看到"没有结论"而不是上一轮的旧结论
            lastImgVerdicts = emptyMap()
            lastImgIpVerdicts = emptyMap()
            val startedAt = System.currentTimeMillis()
            val lineClient = NetworkModule.newLineProbeClient()
            val imgClient = NetworkModule.newSpeedTestClient()
            val previousImgIndex = RouteManager.imgIndex
            val previousImgIp = RouteManager.selectedImgIp
            try {
                // 阶段 1：逐条线路域名/IP探测，签名+解密分类接口验证。
                val lineAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, Long>()
                val lineJobs = lineTargets().map { target ->
                    async {
                        probeSemaphore.withPermit {
                            val delay = measureLineAtIp(lineClient, target)
                            lineAcc[target.endpoint] = delay
                            onProgress?.invoke(lineAcc.toMap(), emptyMap(), emptyMap())
                            target.endpoint to delay
                        }
                    }
                }
                val lines = lineJobs.awaitAll().toMap()
                val bestLine = fastestLineEndpoint(lines)
                val bestLineIndex = bestLine?.let { endpoint ->
                    RouteManager.lineHosts.indexOfFirst { lineDomain(it).equals(endpoint.domain, ignoreCase = true) }
                }?.takeIf { it >= 0 }
                if (bestLine != null && bestLineIndex != null) {
                    RouteManager.setLineEndpoint(bestLine.domain, bestLine.ip)
                    RouteManager.saveLastFastestLine(bestLineIndex)
                    // 让阶段 2 取样本走最快线路：只重建 API 侧，不打断正在下载的图片
                    NetworkModule.rebuildApi("测速选出线路")
                }
                Log.i(TAG, "line test done, best=$bestLine")

                // 阶段 2：兜底样本路径（固定封面 404 时才用到）
                val samplePath = obtainProbePath()
                Log.i(TAG, "img probe sample path=$samplePath")

                // 阶段 3：逐条图源域名/IP探测（包含真实图片内容校验）。
                val imgAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, Long>()
                val verdictAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, ImgVerdict>()
                val imgJobs = imgTargets().map { endpoint ->
                    async {
                        probeSemaphore.withPermit {
                            val result = measureImgAtIp(imgClient, endpoint, samplePath)
                            imgAcc[endpoint] = result.delayMs
                            verdictAcc[endpoint] = result.verdict
                            lastImgIpVerdicts = verdictAcc.toMap()
                            lastImgVerdicts = aggregateImgVerdicts(verdictAcc)
                            onProgress?.invoke(lines, imgAcc.toMap(), verdictAcc.toMap())
                            endpoint to result.delayMs
                        }
                    }
                }
                val imgs = imgJobs.awaitAll().toMap()
                lastImgIpVerdicts = verdictAcc.toMap()
                lastImgVerdicts = aggregateImgVerdicts(verdictAcc)

                RouteManager.setEndpointDelays(lines, imgs, verdictAcc)
                val bestImg = RouteManager.fastestImgEndpoint
                val imgDomainChanged = bestImg != null && RouteManager.pinnedImgDomain.isNullOrBlank() &&
                    (bestImg.domain != RouteManager.imgDomains.getOrNull(previousImgIndex) || bestImg.ip != previousImgIp)
                if (bestImg != null) RouteManager.applyFastestImgEndpoint(bestImg)
                // 按侧清理：线路变了只重建 API 侧；图源变了只驱逐图片侧空闲连接
                if (imgDomainChanged) NetworkModule.rebindImageRoute("测速应用图源")
                RouteManager.markSpeedTestToday()

                lastRunAt = System.currentTimeMillis()
                lastRunDirectStrategy = NetworkModule.shouldUseDirectStrategy()

                val vc = verdictAcc.values.groupingBy { it }.eachCount()
                val fastest = imgs.entries.filter { it.value < MAX && verdictAcc[it.key] == ImgVerdict.OK }
                    .sortedBy { it.value }.take(5)
                    .joinToString(", ") { (endpoint, ms) -> "${endpoint.domain}(${endpoint.ip})=${ms}ms" }
                Log.i(
                    TAG,
                    "img probe summary total=${verdictAcc.size} ok=${vc[ImgVerdict.OK] ?: 0}" +
                        " unreachable=${vc[ImgVerdict.UNREACHABLE] ?: 0} no_image=${vc[ImgVerdict.NO_IMAGE] ?: 0}" +
                        " samplePath=${if (samplePath == null) "null" else "ok"} fastest=[$fastest]",
                )
                Log.i(
                    TAG,
                    "runAndApply done in ${System.currentTimeMillis() - startedAt}ms, " +
                        "bestLine=$bestLine bestImg=$bestImg direct=$lastRunDirectStrategy",
                )
                val result = (bestLineIndex ?: -1) to
                    (bestImg?.let { RouteManager.imgDomains.indexOfFirst { domain -> domain.equals(it.domain, true) } } ?: -1)
                lastCompletedConfigRevision = configRevisionAtStart
                lastConfigRunResult = result
                result
            } finally {
                running.set(false)
                lineClient.releaseProbe()
                imgClient.releaseProbe()
            }
        }
    }

    /**
     * Run a speed test for the currently active configuration. If a configuration replacement races
     * an existing test, wait for that test and repeat against the newer revision. Concurrent callers
     * share a completed result for the same revision instead of starting duplicate probes.
     */
    suspend fun runForCurrentConfig(
        onProgress: ((line: Map<DomainIp, Long>, img: Map<DomainIp, Long>, verdicts: Map<DomainIp, ImgVerdict>) -> Unit)? = null,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        configRunLock.lock()
        try {
            while (true) {
                while (running.get()) delay(50)
                if (lastRunAt > 0L && lastCompletedConfigRevision == RouteManager.configRevision) {
                    onProgress?.invoke(RouteManager.lastLineIpDelays, RouteManager.lastImgIpDelays, lastImgIpVerdicts)
                    return@withContext lastConfigRunResult
                }

                val result = runAndApply(onProgress)
                if (!lastRunSkipped && lastCompletedConfigRevision == RouteManager.configRevision) {
                    return@withContext result
                }
            }
            error("Unreachable")
        } finally {
            configRunLock.unlock()
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
