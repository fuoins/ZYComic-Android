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

    /**
     * 一次探活的结果。
     *
     * - [delayMs]：仅在"判定成功"时有值，否则 [MAX]（沿用原有选域口径）；
     * - [httpCode]：-1 表示连接层就没拿到响应；
     * - [elapsedMs]：**无论内容是否合格**都记录的实测耗时。
     *   这是 IP 层探测的关键区分：只要拿到任何 HTTP 响应（200/302/404/text/html 都算），
     *   就证明这台机器可达，延迟可用于 IP 排序；只有连接层失败才说明机器不可达。
     *   没有这个字段，一个"活着但没镜像该对象"的 IP 会被误判成死机器。
     */
    private data class ProbeOutcome(
        val delayMs: Long,
        val httpCode: Int,
        val elapsedMs: Long = MAX,
    )

    /** 图域探活结论，用于把"连不上"和"连上了但拿不到图"分开——UI 上一律显示"超时"会掩盖两种完全不同的问题。 */
    enum class ImgVerdict { OK, UNREACHABLE, NO_IMAGE }

    /** 图域探活结论 + 延迟。 */
    private data class ImgProbe(val delayMs: Long, val verdict: ImgVerdict)

    /** 最近一轮图域探活的逐域结论（index -> 结论），供 UI/排查区分"没测/连不上/无图"。 */
    @Volatile var lastImgVerdicts: Map<Int, ImgVerdict> = emptyMap()
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
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    val ok = code in 200..299 && when {
                        requireJson -> isJsonLike(head)
                        else -> !strictImage || isImageLike(contentType, head)
                    }
                    if (ok) {
                        ProbeOutcome(elapsed, code, elapsed)
                    } else {
                        Log.d(
                            TAG,
                            "probe reject url=${req.url} code=$code ct=$contentType len=${head.size}",
                        )
                        // 拿到了响应但内容不合格：延迟仍然有效（证明该机器可达）
                        ProbeOutcome(MAX, code, elapsed)
                    }
                }
            } catch (e: Exception) {
                // 状态码已拿到就如实带上（404 后能否回退取样路径依赖它）
                Log.d(TAG, "probe fail url=${req.url} code=$code err=${e.message}")
                // elapsedMs 保持 MAX：连接层失败不给这台 IP 记延迟
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
        return head.any { val v = it.toInt() and 0xFF; v == 0 || v in 1..8 || v in 14..31 }
    }

    private fun hasImageMagic(b: ByteArray): Boolean {
        if (b.size < 4) return false
        if (b[0] == 0x52.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() && b[3] == 0x46.toByte()) return true
        if (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()) return true
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return true
        if (b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() && b[3] == 0x38.toByte()) return true
        return false
    }

    // ==================== IP 层探测（按机器测速）====================

    /** 一个 IP 的探测结论：延迟 + 这台机器上代表域的取图结论。 */
    private data class IpProbeResult(val delayMs: Long, val verdict: ImgVerdict)

    /** 最近一轮 IP 层探测的逐 IP 结论（ip -> 结论），供测速日志与人工更新 ruledns 用。 */
    @Volatile var lastIpVerdicts: Map<String, ImgVerdict> = emptyMap()
        private set

    /**
     * 探一个 IP：用代表域名发真实图片请求，但把这一发的 DNS 解析钉到目标 IP。
     *
     * 为什么仍用域名而不是直接打 IP：Host/SNI 必须保持真实，否则会落到机器的默认 vhost
     * （404/停车场页），而且 `SniRemovingSocketFactory` 是按 host 是否在 sni 名单里决定去不去
     * SNI 的——URL 写 IP 会让 Cloudflare 段的豁免逻辑失效。
     *
     * [reps] 是同一 IP 名下的若干代表域（按列表顺序，最多 3 个）：
     * - 任一代表域拿到图片 → 立即返回，结论最干净（延迟 + OK）；
     * - 只代表域全都拿到过 HTTP 响应（含 302/404/text-html）→ 机器**可达**，
     *   记最小延迟、verdict=NO_IMAGE（见 [ProbeOutcome.elapsedMs] 的说明）；
     * - 一次响应都没拿到 → UNREACHABLE，不记延迟。
     * 代表域是"死 vhost"还是"活 IP"必须分开，否则一个搬走的域会把整台机器误判成死机器。
     */
    private suspend fun measureIp(client: OkHttpClient, ip: String, reps: List<String>): IpProbeResult {
        if (reps.isEmpty()) return IpProbeResult(MAX, ImgVerdict.UNREACHABLE)
        // 复用同一套传输层，只把这一发的 DNS 钉到目标 IP
        val pinned = NetworkModule.newPinnedIpProbeClient(ip, base = client)
        var bestElapsed = MAX
        var sawResponse = false
        // 不在这个 pinned client 上 releaseProbe：newBuilder 与 base 共用同一个连接池，
        // 逐个 IP 都去 evictAll 会把别的探测刚建好的空闲连接关掉，导致重复握手、
        // 把后面的延迟测高。整轮的连接池统一由 probeImgIps 末尾释放。
        for (rep in reps) {
            val outcome = probe(
                pinned,
                imgProbeRequest(rep, PROBE_COVER_PATH),
                requireJson = false,
                strictImage = true,
            )
            if (outcome.delayMs < MAX) return IpProbeResult(outcome.delayMs, ImgVerdict.OK)
            if (outcome.elapsedMs < MAX) {
                sawResponse = true
                if (outcome.elapsedMs < bestElapsed) bestElapsed = outcome.elapsedMs
            }
        }
        return if (sawResponse) {
            Log.d(TAG, "ip $ip reachable but no image on representatives $reps -> delay=$bestElapsed")
            IpProbeResult(bestElapsed, ImgVerdict.NO_IMAGE)
        } else {
            IpProbeResult(MAX, ImgVerdict.UNREACHABLE)
        }
    }

    /**
     * 按机器测速并派生域名分数。
     *
     * @param forceFullProbe true（用户点"重新测速"）时全部重测；false 时只补测**还没有分数**的 IP，
     *   于是 ruledns 下发新 IP 的那一轮天然只探新 IP，不需要额外记账"IP 集合签名"。
     * @return (ip -> 延迟, ip -> 结论)
     */
    suspend fun probeImgIps(forceFullProbe: Boolean = false): Pair<Map<String, Long>, Map<String, ImgVerdict>> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val rule = DevConfig.getRule()
                val hosts = RouteManager.imgDomains
                val known = RouteManager.ipScoresSnapshot()
                val plan = IpProbePlanner.plan(hosts, rule, known, forceFullProbe)
                // 多个协程并发写：一律用并发容器，不用 HashMap / synchronized
                // 基线只取"本轮有效"的分数：跨网络/跨策略的旧分数不能当已知复用，
                // 否则换网络后会拿别的链路的延迟去派生选域。
                val stored = java.util.concurrent.ConcurrentHashMap<String, Long>(known)
                val verdicts = java.util.concurrent.ConcurrentHashMap<String, ImgVerdict>()
                if (plan.isEmpty()) {
                    Log.i(TAG, "ip probe skipped: 全部 IP 已有分数 (known=${known.size})")
                    // 跳过探测也要清理：配置里已消失的 IP 分数不能一直留着
                    return@coroutineScope IpProbePlanner.prune(
                        stored.toMap(),
                        IpProbePlanner.uniqueIps(hosts, rule),
                    ) to verdicts.toMap()
                }
                val client = NetworkModule.newSpeedTestClient()
                try {
                    plan.map { target ->
                        async {
                            val r = measureIp(client, target.ip, target.representatives)
                            Log.i(
                                TAG,
                                "ip probe ${target.ip} -> ${r.delayMs}ms ${r.verdict}" +
                                    " cf=${IpProbePlanner.isCloudflareAnycast(target.ip)}" +
                                    " reps=${target.representatives}",
                            )
                            stored[target.ip] = r.delayMs
                            verdicts[target.ip] = r.verdict
                            r
                        }
                    }.awaitAll()
                } finally {
                    client.releaseProbe()
                }
                // 丢掉配置里已经不存在的 IP，避免陈旧分数继续参与派生
                IpProbePlanner.prune(stored.toMap(), IpProbePlanner.uniqueIps(hosts, rule)) to verdicts.toMap()
            }
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

    /**
     * 「IP 分数 -> 域名延迟」派生：域名分数 = 它 IP 组里已测出的最快值。
     *
     * 这一步**不发任何请求**，所以 103 个域共享同一批探测结果——这就是"一次测机器、
     * 收益覆盖全部图源"。没测过的 IP 不参与 min；整组都没分数时返回 [MAX]（判负，不是 0）。
     */
    private fun deriveDomainDelays(scores: Map<String, Long>): Map<String, Long> =
        IpProbePlanner.domainDelays(RouteManager.imgDomains, DevConfig.getRule(), scores)

    /** 线路全量探活（只返回延迟，不应用结果）。 */
    suspend fun testAllLines(): Map<Int, Long> = withContext(Dispatchers.IO) {
        coroutineScope {
            val client = NetworkModule.newLineProbeClient()
            try {
                RouteManager.lineHosts.mapIndexed { index, lineUrl ->
                    async { index to measureLineOnce(client, lineUrl) }
                }.awaitAll().toMap()
            } finally {
                client.releaseProbe()
            }
        }
    }

    /** 图域全量探活（ReaderActivity 手动入口用）；样本路径取缓存，作固定封面 404 时的兜底。 */
    suspend fun testAllImgHosts(): Map<Int, Long> = withContext(Dispatchers.IO) {
        coroutineScope {
            val client = NetworkModule.newSpeedTestClient()
            try {
                val samplePath = RouteManager.loadProbeImgPath()
                RouteManager.imgDomains.mapIndexed { index, domain ->
                    async { index to measureImg(client, domain, samplePath).delayMs }
                }.awaitAll().toMap()
            } finally {
                client.releaseProbe()
            }
        }
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
    suspend fun runAndApply(
        onProgress: ((line: Map<Int, Long>, img: Map<Int, Long>) -> Unit)? = null,
        forceFullProbe: Boolean = false,
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
