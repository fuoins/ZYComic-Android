package com.zycomic.app.net

import android.util.Log
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 线路 / 图域测速。
 *
 * 关键一致性原则：测速使用的 OkHttpClient 传输层（DNS/SNI/证书/出口/cookie）由
 * [NetworkModule.applyTransportStrategy] 统一构建，与当前网络下的运行时 api/image client
 * 完全一致—— VPN 开时测速即走系统 DNS+保留 SNI 的 VPN 路径；直连时走 RuleDns，且仅对显式配置中的非 Cloudflare 主机尝试去 SNI。
 * 连接失败也可能来自 DNS、TLS、HTTP 状态或源站，不应仅凭失败结果归因于 SNI。
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
    private val probeSemaphore = SpeedTestProbeBudget.permits

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
    private val _isRunning = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()
    val isRunning: Boolean get() = running.get()
    private val configRunLock = Mutex()

    /** Compatibility shim: report whether image history can support a fast retest; never reuse its latency directly. */
    fun requestPreviousImageResults(): Boolean {
        val history = RouteManager.speedTestHistoryForVersion(RouteManager.activeRemoteConfigVersion) ?: return false
        return SpeedTestHistoryPolicy.selectTopSuccessful(history.imgDelays) { endpoint, _ ->
            history.imgVerdicts[endpoint] == ImgVerdict.OK
        }.isNotEmpty()
    }

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

    /** 一次探活的结果；不保存 URL、响应正文或原始异常文本。 */
    private data class ProbeOutcome(
        val delayMs: Long,
        val httpCode: Int,
        val contentType: String?,
        val failureCategory: SpeedTestFailureCategory?,
        val elapsedMs: Long,
        val networkEvents: List<SpeedTestNetworkEvent> = emptyList(),
        val sniDecisions: List<SpeedTestSniDecision> = emptyList(),
    ) {
        fun toAttempt(probe: String) = SpeedTestProbeAttempt(
            probe = probe,
            success = delayMs < MAX,
            elapsedMs = elapsedMs,
            httpStatus = httpCode.takeIf { it >= 0 },
            contentType = contentType,
            failureCategory = failureCategory,
            networkEvents = networkEvents,
            sniDecisions = sniDecisions,
        )

        fun toLineDiagnostic(): SpeedTestEndpointDiagnostic {
            val attempt = toAttempt("line_api")
            return SpeedTestEndpointDiagnostic(
                success = attempt.success,
                latencyMs = delayMs.takeIf { it < MAX },
                elapsedMs = elapsedMs,
                failureCategory = failureCategory,
                attempts = listOf(attempt),
            )
        }
    }

    /** 图域探活结论，用于把"连不上"和"连上了但拿不到图"分开——UI 上一律显示"超时"会掩盖两种完全不同的问题。 */
    enum class ImgVerdict { OK, UNREACHABLE, NO_IMAGE }

    /** 图域探活结论、延迟与每次样本探测的安全诊断字段。 */
    private data class ImgProbe(
        val delayMs: Long,
        val verdict: ImgVerdict,
        val diagnostic: SpeedTestEndpointDiagnostic,
    )

    /** 最近一轮图域探活的逐域结论（index -> 结论），供 UI/排查区分"没测/连不上/无图"。 */
    @Volatile var lastImgVerdicts: Map<Int, ImgVerdict> = emptyMap()
        private set

    /** Per-domain/IP image verdicts from the latest pass. */
    @Volatile var lastImgIpVerdicts: Map<DomainIp, ImgVerdict> = emptyMap()
        private set

    enum class Phase {
        PREPARING_LINES,
        TESTING_LINES,
        APPLYING_LINE,
        FETCHING_SAMPLE,
        PREPARING_IMAGES,
        TESTING_IMAGES,
        APPLYING_RESULTS,
        COMPLETE,
        FAILED,
        SKIPPED,
    }

    data class Progress(
        val phase: Phase = Phase.PREPARING_LINES,
        val completed: Int = 0,
        val total: Int = 0,
        val successful: Int = 0,
        val failed: Int = 0,
        val noImage: Int = 0,
        val freshImages: Int = 0,
        val reusedImages: Int = 0,
        val reusedLines: Int = 0,
        val fullRetestRecommended: Boolean = false,
        val lineTopFiveAllFailed: Boolean = false,
        val imageTopFiveAllFailed: Boolean = false,
        val lineQuickTest: Boolean = false,
        val imageQuickTest: Boolean = false,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private fun publishProgress(progress: Progress, callback: ((Progress) -> Unit)?) {
        _progress.value = progress
        callback?.invoke(progress)
    }

    /** 最近一次 runAndApply 是否因为"已有测速在跑"被整轮丢弃（被丢弃时一个域都没探）。 */
    @Volatile var lastRunSkipped: Boolean = false
        private set

    /** 线路探活：2xx **且解密后 body 是 JSON 对象** 才计成功。 */
    private suspend fun measureLine(client: OkHttpClient, req: Request): ProbeOutcome =
        probe(client, req, requireJson = true, strictImage = false)

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
    private suspend fun measureImg(
        client: OkHttpClient,
        domain: String,
        samplePath: suspend () -> String?,
    ): ImgProbe {
        val attempts = ManwaImageFallbackProbePolicy.probeFixedThenFallback(
            fixedPath = PROBE_COVER_PATH,
            samplePath = samplePath,
            probe = { path -> probe(client, imgProbeRequest(domain, path), requireJson = false, strictImage = true) },
            isSuccess = { it.delayMs < MAX },
            hasHttpResponse = { it.httpCode != -1 },
        )
        val outcomes = attempts.map { it.result }
        val last = outcomes.last()
        val success = last.delayMs < MAX
        if (success && attempts.size > 1) Log.i(TAG, "img probe fallback ok domain=$domain path=${attempts.last().path}")
        val verdict = when {
            success -> ImgVerdict.OK
            outcomes.any { it.httpCode == -1 } -> ImgVerdict.UNREACHABLE
            else -> ImgVerdict.NO_IMAGE
        }
        if (verdict == ImgVerdict.NO_IMAGE) Log.d(TAG, "img no-image domain=$domain lastCode=${last.httpCode}")
        return ImgProbe(
            delayMs = if (success) last.delayMs else MAX,
            verdict = verdict,
            diagnostic = SpeedTestEndpointDiagnostic(
                success = success,
                latencyMs = last.delayMs.takeIf { success },
                elapsedMs = outcomes.sumOf { it.elapsedMs },
                failureCategory = last.failureCategory,
                imageVerdict = verdict.name.lowercase(java.util.Locale.ROOT),
                fallbackAttempted = attempts.size > 1,
                attempts = attempts.mapIndexed { index, attempt ->
                    attempt.result.toAttempt(if (index == 0) "fixed_cover" else "fallback_sample")
                },
            ),
        )
    }

    private suspend fun measureLineOnce(client: OkHttpClient, lineUrl: String): ProbeOutcome =
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
            var contentType: String? = null
            val trace = ProbeAttemptTrace()
            val tracedRequest = req.newBuilder()
                .tag(ProbeAttemptTrace::class.java, trace)
                .build()
            try {
                ProbeAttemptTraceContext.withTrace(trace) {
                    client.newCall(tracedRequest).execute().use { resp ->
                        code = resp.code
                        contentType = resp.header("Content-Type")
                            ?.lowercase(java.util.Locale.ROOT)
                            ?.takeIf(String::isNotBlank)
                        val head = readHead(resp, PROBE_BYTES)
                        val failureCategory = when {
                            code !in 200..299 -> SpeedTestFailureCategory.HTTP_STATUS
                            requireJson && !isJsonLike(head) -> SpeedTestFailureCategory.INVALID_JSON
                            strictImage && !isImageLike(contentType.orEmpty(), head) -> SpeedTestFailureCategory.INVALID_IMAGE
                            else -> null
                        }
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000
                        if (failureCategory == null) {
                            ProbeOutcome(
                                delayMs = elapsedMs,
                                httpCode = code,
                                contentType = contentType,
                                failureCategory = null,
                                elapsedMs = elapsedMs,
                                networkEvents = trace.networkEvents(),
                                sniDecisions = trace.sniDecisions(),
                            )
                        } else {
                            Log.d(
                                TAG,
                                "probe reject url=${req.url} code=$code ct=$contentType len=${head.size}",
                            )
                            ProbeOutcome(
                                delayMs = MAX,
                                httpCode = code,
                                contentType = contentType,
                                failureCategory = failureCategory,
                                elapsedMs = elapsedMs,
                                networkEvents = trace.networkEvents(),
                                sniDecisions = trace.sniDecisions(),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 状态码已拿到就如实带上（404 后能否回退取样路径依赖它）
                Log.d(TAG, "probe fail url=${req.url} code=$code err=${e.message}")
                trace.recordProbeFailure(e)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                ProbeOutcome(
                    delayMs = MAX,
                    httpCode = code,
                    contentType = contentType,
                    failureCategory = classifyFailure(e),
                    elapsedMs = elapsedMs,
                    networkEvents = trace.networkEvents(),
                    sniDecisions = trace.sniDecisions(),
                )
            }
        }

    private fun classifyFailure(error: Exception): SpeedTestFailureCategory = when (error) {
        is java.net.SocketTimeoutException,
        is java.io.InterruptedIOException,
        is java.util.concurrent.TimeoutException,
        -> SpeedTestFailureCategory.TIMEOUT
        is java.net.UnknownHostException -> SpeedTestFailureCategory.DNS
        is javax.net.ssl.SSLException -> SpeedTestFailureCategory.TLS
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.SocketException,
        -> SpeedTestFailureCategory.CONNECTION
        is java.io.IOException -> SpeedTestFailureCategory.IO
        else -> SpeedTestFailureCategory.OTHER
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
    private suspend fun obtainProbePath(isCurrent: () -> Boolean = { true }): String? {
        // 整轮测速已经没有"整体超时"了，这一步必须自带上限：api.index() 用的是运行时
        // apiClient（readTimeout 30s），网络半死不活时能把启动卡住半分钟。
        // 6s 内拿不到样本就退到历史缓存路径，绝不拖住进入主页。
        val index = try {
            withTimeoutOrNull(SAMPLE_PATH_TIMEOUT_MS) { NetworkModule.api.index() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "probe sample path: index() 请求失败 err=${error.message}")
            null
        }
        if (index == null) {
            Log.w(TAG, "probe sample path: index() 无结果或在 ${SAMPLE_PATH_TIMEOUT_MS}ms 内没返回，改用历史缓存")
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
            if (!isCurrent()) return null
            RouteManager.saveProbeImgPath(live)
            Log.i(TAG, "probe sample path: 取到新的样本路径 $live")
            return live
        }
        if (!isCurrent()) return null
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

    private data class StandaloneImageResultGuard(val configRevision: Long, val platformRevision: Long, val result: Map<DomainIp, Long>)

    @Volatile private var standaloneImageResultGuard: StandaloneImageResultGuard? = null

    internal fun isStandaloneImageResultCurrent(result: Map<DomainIp, Long>): Boolean {
        val guard = standaloneImageResultGuard ?: return false
        return guard.result == result && guard.configRevision == RouteManager.configRevision &&
            isPlatformCurrent(guard.platformRevision)
    }

    private fun isPlatformCurrent(revision: Long): Boolean =
        StartupPlatformPolicy.state.value.revision == revision &&
            StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)

    internal fun currentLineEndpoints(): Set<DomainIp> = lineTargets().map { it.endpoint }.toSet()

    internal fun currentImageEndpoints(): Set<DomainIp> = imgTargets().toSet()

    suspend fun reuseValidResults(): Boolean {
        val revision = StartupPlatformPolicy.state.value.revision
        if (!isPlatformCurrent(revision) || !RouteManager.reuseValidResults()) return false
        if (!isPlatformCurrent(revision)) return false
        val line = RouteManager.lastLineIpDelays
        val image = RouteManager.lastImgIpDelays
        val fastestLine = fastestLineEndpoint(line)?.let { endpoint ->
            RouteManager.lineHosts.indexOfFirst { lineDomain(it).equals(endpoint.domain, true) }
        }?.takeIf { it >= 0 } ?: -1
        val fastestImage = RouteManager.fastestImgEndpoint?.let { endpoint ->
            RouteManager.imgDomains.indexOfFirst { it.equals(endpoint.domain, true) }
        }?.takeIf { it >= 0 } ?: -1
        lastRunAt = RouteManager.speedTestSnapshot.value.completedAtEpochMs ?: System.currentTimeMillis()
        lastRunDirectStrategy = RouteManager.speedTestSnapshot.value.networkPath?.directStrategy
        lastCompletedConfigRevision = RouteManager.configRevision
        lastConfigRunResult = fastestLine to fastestImage
        return true
    }

    private data class LineTarget(val endpoint: DomainIp, val url: String)

    private fun lineDomain(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun lineTargets(): List<LineTarget> = RouteManager.lineHosts.flatMap { url ->
        val domain = lineDomain(url) ?: return@flatMap emptyList()
        DomainIpProbePolicy.endpoints(domain, NetworkModule.resolveProbeIps(domain))
            .map { endpoint -> LineTarget(endpoint, url) }
    }.distinctBy { it.endpoint }

    private fun imgTargets(domains: List<String> = RouteManager.imgDomains): List<DomainIp> = domains.flatMap { domain ->
        DomainIpProbePolicy.endpoints(domain, NetworkModule.resolveProbeIps(domain))
    }.distinct()

    private suspend fun measureLineAtIp(client: OkHttpClient, target: LineTarget): ProbeOutcome {
        val routed = NetworkModule.newProbeClientForIp(client, target.endpoint.domain, target.endpoint.ip)
        return try {
            measureLineOnce(routed, target.url).also { outcome ->
                Log.i(
                    TAG,
                    "line endpoint domain=${target.endpoint.domain} ip=${target.endpoint.ip} " +
                        "result=${if (outcome.delayMs < MAX) "ok" else outcome.failureCategory?.name ?: "failed"} " +
                        "delayMs=${outcome.delayMs.takeIf { it < MAX } ?: "unavailable"}",
                )
            }
        } finally {
            routed.releaseProbe()
        }
    }

    private suspend fun measureImgAtIp(
        client: OkHttpClient,
        endpoint: DomainIp,
        samplePath: suspend () -> String?,
    ): ImgProbe {
        val routed = NetworkModule.newProbeClientForIp(client, endpoint.domain, endpoint.ip)
        return try {
            measureImg(routed, endpoint.domain, samplePath).also { result ->
                Log.i(
                    TAG,
                    "image endpoint domain=${endpoint.domain} ip=${endpoint.ip} " +
                        "verdict=${result.verdict.name.lowercase(java.util.Locale.ROOT)} " +
                        "delayMs=${result.delayMs.takeIf { it < MAX } ?: "unavailable"}",
                )
            }
        } finally {
            routed.releaseProbe()
        }
    }

    /** Probe each currently configured or DNS-resolved API IP once. */
    suspend fun testAllLineEndpoints(
        onResult: ((DomainIp, Long) -> Unit)? = null,
    ): Map<DomainIp, Long> = withContext(Dispatchers.IO) {
        val platformRevision = StartupPlatformPolicy.state.value.revision
        val configRevision = RouteManager.configRevision
        fun stillCurrent() = isPlatformCurrent(platformRevision) && RouteManager.configRevision == configRevision
        if (!stillCurrent()) return@withContext emptyMap()
        val client = NetworkModule.newLineProbeClient()
        try {
            val targets = lineTargets()
            coroutineScope {
                targets.map { target ->
                    async {
                        probeSemaphore.withPermit {
                            val delay = if (stillCurrent()) measureLineAtIp(client, target).delayMs else MAX
                            if (stillCurrent()) onResult?.invoke(target.endpoint, delay)
                            target.endpoint to delay
                        }
                    }
                }.awaitAll().toMap()
            }.takeIf { stillCurrent() }.orEmpty()
        } finally {
            client.releaseProbe()
        }
    }

    /** Probe each image domain/IP using the same real-image validation as startup. */
    suspend fun testAllImgEndpoints(
        samplePath: String? = RouteManager.loadProbeImgPath(),
        onResult: ((DomainIp, Long, ImgVerdict) -> Unit)? = null,
        domains: List<String> = RouteManager.imgDomains,
        onStageProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): Map<DomainIp, Long> = withContext(Dispatchers.IO) {
        val platformRevision = StartupPlatformPolicy.state.value.revision
        val configRevision = RouteManager.configRevision
        fun stillCurrent() = isPlatformCurrent(platformRevision) && RouteManager.configRevision == configRevision
        if (!stillCurrent()) return@withContext emptyMap()
        val client = NetworkModule.newSpeedTestClient()
        try {
            val verdicts = java.util.concurrent.ConcurrentHashMap<DomainIp, ImgVerdict>()
            val targets = imgTargets(domains)
            val completed = AtomicInteger(0)
            onStageProgress?.invoke(0, targets.size)
            val delays = coroutineScope {
                targets.map { endpoint ->
                    async {
                        probeSemaphore.withPermit {
                            val result = if (stillCurrent()) {
                                measureImgAtIp(client, endpoint) { samplePath }
                            } else {
                                ImgProbe(MAX, ImgVerdict.UNREACHABLE, SpeedTestEndpointDiagnostic(false, null, 0L, null))
                            }
                            if (stillCurrent()) {
                                verdicts[endpoint] = result.verdict
                                lastImgIpVerdicts = verdicts.toMap()
                                onResult?.invoke(endpoint, result.delayMs, result.verdict)
                            }
                            onStageProgress?.invoke(completed.incrementAndGet(), targets.size)
                            endpoint to result.delayMs
                        }
                    }
                }.awaitAll().toMap()
            }
            if (stillCurrent()) {
                lastImgIpVerdicts = verdicts.toMap()
                lastImgVerdicts = aggregateImgVerdicts(verdicts)
                standaloneImageResultGuard = StandaloneImageResultGuard(configRevision, platformRevision, delays)
            } else {
                standaloneImageResultGuard = null
            }
            delays.takeIf { stillCurrent() }.orEmpty()
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
        val platformRevision = StartupPlatformPolicy.state.value.revision
        if (!isPlatformCurrent(platformRevision)) return MAX
        val configRevision = RouteManager.configRevision
        val previousDelays = RouteManager.lastImgIpDelays
        val previousVerdicts = RouteManager.lastImgIpVerdicts
        val results = testAllImgEndpoints(domains = listOf(domain))
        if (!isPlatformCurrent(platformRevision) || RouteManager.configRevision != configRevision) return MAX
        val newVerdicts = lastImgIpVerdicts
        val combinedDelays = previousDelays + results
        val combinedVerdicts = previousVerdicts + newVerdicts
        lastImgIpVerdicts = combinedVerdicts
        lastImgVerdicts = aggregateImgVerdicts(combinedVerdicts)
        if (isPlatformCurrent(platformRevision)) {
            RouteManager.setEndpointDelays(
                RouteManager.lastLineIpDelays,
                combinedDelays,
                combinedVerdicts,
                expectedConfigRevision = configRevision,
                expectedPlatformRevision = platformRevision,
            )
        }
        return results.filterKeys { it.domain.equals(domain, ignoreCase = true) }
            .values.filter { it < MAX }.minOrNull() ?: MAX
    }

    suspend fun testLineEndpoints(lineUrl: String): Map<DomainIp, Long> = withContext(Dispatchers.IO) {
        val platformRevision = StartupPlatformPolicy.state.value.revision
        val configRevision = RouteManager.configRevision
        fun stillCurrent() = isPlatformCurrent(platformRevision) && RouteManager.configRevision == configRevision
        if (!stillCurrent()) return@withContext emptyMap()
        val host = lineDomain(lineUrl) ?: return@withContext emptyMap()
        val client = NetworkModule.newLineProbeClient()
        try {
            val targets = NetworkModule.resolveProbeIps(host).map { LineTarget(DomainIp(host, it), lineUrl) }
            coroutineScope {
                targets.map { target ->
                    async {
                        probeSemaphore.withPermit {
                            target.endpoint to (if (stillCurrent()) measureLineAtIp(client, target).delayMs else MAX)
                        }
                    }
                }.awaitAll().toMap()
            }.takeIf { stillCurrent() }.orEmpty()
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
        onStageProgress: ((Progress) -> Unit)? = null,
        reuseLines: Boolean = false,
        allowImageReuse: Boolean = false,
        isCurrent: () -> Boolean = { true },
        beforeApply: suspend () -> Boolean = { true },
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        coroutineScope {
            val configRevisionAtStart = RouteManager.configRevision
            val configVersionAtStart = RouteManager.activeRemoteConfigVersion
            val platformRevisionAtStart = StartupPlatformPolicy.state.value.revision
            fun runIsCurrent(): Boolean =
                isPlatformCurrent(platformRevisionAtStart) &&
                    configRevisionAtStart == RouteManager.configRevision &&
                    configVersionAtStart == RouteManager.activeRemoteConfigVersion &&
                    isCurrent()
            if (!runIsCurrent()) {
                lastRunSkipped = true
                onStageProgress?.invoke(Progress(Phase.SKIPPED))
                return@coroutineScope (-1 to -1)
            }
            fun reportStage(progress: Progress) {
                if (runIsCurrent()) {
                    publishProgress(progress, onStageProgress)
                }
            }
            if (!running.compareAndSet(false, true)) {
                lastRunSkipped = true
                onStageProgress?.invoke(Progress(Phase.SKIPPED))
                Log.w(TAG, "runAndApply skipped: another speed test is running（本轮一个域都没探）")
                return@coroutineScope (-1 to -1)
            }
            lastRunSkipped = false
            _isRunning.value = true
            reportStage(Progress(Phase.PREPARING_LINES))
            val startedAt = System.currentTimeMillis()
            val imageCacheNetworkIdentityAtStart = PlatformSpeedTestCoordinator.currentNetworkIdentity()
            val followSystemVpnEnabled = DevConfig.isFollowSystemVpnEnabled()
            val systemVpnActive = NetworkModule.isSystemVpnActive()
            val networkPath = SpeedTestNetworkPath(
                followSystemVpnEnabled = followSystemVpnEnabled,
                systemVpnActive = systemVpnActive,
                directStrategy = !followSystemVpnEnabled || !systemVpnActive,
                androidApiLevel = android.os.Build.VERSION.SDK_INT,
                androidRelease = android.os.Build.VERSION.RELEASE,
            )
            val lineClient = NetworkModule.newLineProbeClient()
            val imgClient = NetworkModule.newSpeedTestClient()
            val previousImgIndex = RouteManager.imgIndex
            val previousImgIp = RouteManager.selectedImgIp
            val previousImgDomain = RouteManager.imgDomains.getOrNull(previousImgIndex)
            var samplePath: ManwaImageFallbackProbePolicy.SharedLazy<String?>? = null
            val timings = SpeedTestTimingLog.start(SpeedTestTimingLog.Scope.MANWA)
            try {
                val linePreparationTiming = timings.start(SpeedTestTimings.Phase.LINE_PREPARATION)
                // 阶段 1：逐条线路域名/IP探测，签名+解密分类接口验证。
                val lineAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, Long>()
                val compatibleHistory = RouteManager.speedTestHistoryForVersion(configVersionAtStart)
                val lineHistory = compatibleHistory?.lineDelays.orEmpty()
                val selectedLineEndpoints = if (reuseLines) {
                    SpeedTestHistoryPolicy.selectRankedTop(lineHistory).toSet()
                } else {
                    emptySet()
                }
                val quickLineRetest = selectedLineEndpoints.isNotEmpty()
                val resolvedLines = if (quickLineRetest) emptyList() else lineTargets()
                val lineProbeTargets = if (quickLineRetest) {
                    selectedLineEndpoints.map { endpoint ->
                        val url = RouteManager.lineHosts.firstOrNull {
                            lineDomain(it).equals(endpoint.domain, ignoreCase = true)
                        } ?: "https://${endpoint.domain}"
                        LineTarget(endpoint, url)
                    }
                } else {
                    resolvedLines
                }
                val retainedLineCount = if (quickLineRetest) {
                    lineAcc.putAll(lineHistory.filterKeys { it !in selectedLineEndpoints })
                    lineHistory.size - selectedLineEndpoints.size
                } else {
                    0
                }
                linePreparationTiming.close()
                val lineTiming = timings.start(SpeedTestTimings.Phase.LINES)
                reportStage(Progress(Phase.TESTING_LINES, total = lineProbeTargets.size, reusedLines = retainedLineCount))
                val freshLineAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, Long>()
                val lineJobs = lineProbeTargets.map { target ->
                    async {
                        probeSemaphore.withPermit {
                            val outcome = if (runIsCurrent()) {
                                measureLineAtIp(lineClient, target)
                            } else {
                                ProbeOutcome(MAX, -1, null, SpeedTestFailureCategory.OTHER, 0L)
                            }
                            lineAcc[target.endpoint] = outcome.delayMs
                            freshLineAcc[target.endpoint] = outcome.delayMs
                            if (runIsCurrent()) {
                                onProgress?.invoke(lineAcc.toMap(), emptyMap(), emptyMap())
                            }
                            val successful = freshLineAcc.values.count { it < MAX }
                            reportStage(
                                Progress(
                                    phase = Phase.TESTING_LINES,
                                    completed = freshLineAcc.size.coerceAtMost(lineProbeTargets.size),
                                    total = lineProbeTargets.size,
                                    successful = successful,
                                    failed = freshLineAcc.size - successful,
                                    reusedLines = retainedLineCount,
                                    lineQuickTest = quickLineRetest,
                                ),
                            )
                            target.endpoint to outcome
                        }
                    }
                }
                val freshLineOutcomes = lineJobs.awaitAll().toMap()
                lineTiming.close()
                val confirmedForApply = timings.start(SpeedTestTimings.Phase.CONFIG_WAIT).use { beforeApply() }
                if (!confirmedForApply || !runIsCurrent()) return@coroutineScope (-1 to -1)
                // Do not replace previously published results during an unconfirmed warmup.
                lastImgVerdicts = emptyMap()
                lastImgIpVerdicts = emptyMap()
                val freshLines = freshLineOutcomes.mapValues { it.value.delayMs }
                val lines = if (quickLineRetest) {
                    SpeedTestHistoryPolicy.merge(lineHistory, selectedLineEndpoints, freshLines)
                } else {
                    freshLines
                }
                if (runIsCurrent()) onProgress?.invoke(lines, emptyMap(), emptyMap())
                val lineDiagnostics = freshLineOutcomes.mapValues { it.value.toLineDiagnostic() }
                val lineQuickAllFailed = quickLineRetest && freshLines.isNotEmpty() && freshLines.values.all { it >= MAX }
                if (!runIsCurrent()) {
                    return@coroutineScope (-1 to -1)
                }
                val successfulLines = lines.values.count { it < MAX }
                reportStage(
                    Progress(
                        phase = Phase.APPLYING_LINE,
                        completed = freshLines.size,
                        total = lineProbeTargets.size,
                        successful = freshLines.values.count { it < MAX },
                        failed = freshLines.size - freshLines.values.count { it < MAX },
                        reusedLines = retainedLineCount,
                        lineQuickTest = quickLineRetest,
                    ),
                )
                val bestLine = fastestLineEndpoint(if (quickLineRetest) freshLines else lines)
                val bestLineIndex = bestLine?.let { endpoint ->
                    RouteManager.lineHosts.indexOfFirst { lineDomain(it).equals(endpoint.domain, ignoreCase = true) }
                }?.takeIf { it >= 0 }
                if (bestLine != null && bestLineIndex != null && runIsCurrent()) {
                    RouteManager.saveLastFastestLine(bestLineIndex)
                    if (runIsCurrent() && RouteManager.applyFastestLineEndpoint(
                            bestLine,
                            expectedConfigRevision = configRevisionAtStart,
                            expectedPlatformRevision = platformRevisionAtStart,
                        )
                    ) {
                        // 自动模式下让阶段 2 样本走最快线路；临时手动覆盖不被测速改写。
                        NetworkModule.rebuildApi("测速选出线路")
                    }
                }
                Log.i(TAG, "line test done, best=$bestLine")

                // The fallback sample is fetched only if an image probe gets an HTTP response
                // without a valid image; concurrent failures share this run-scoped lazy lookup.
                var resolvedSamplePath: String? = null
                val sharedSamplePath = ManwaImageFallbackProbePolicy.sharedLazy(this) {
                    reportStage(Progress(Phase.FETCHING_SAMPLE))
                    timings.start(SpeedTestTimings.Phase.IMAGE_SAMPLE_FETCH_FALLBACK).use {
                        if (runIsCurrent()) obtainProbePath(::runIsCurrent) else null
                    }.also { path ->
                        resolvedSamplePath = path
                        if (runIsCurrent()) Log.i(TAG, "img probe sample path=$path")
                    }
                }
                samplePath = sharedSamplePath

                // 阶段 3：逐条图源域名/IP探测（包含真实图片内容校验）。
                val imgAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, Long>()
                val verdictAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, ImgVerdict>()
                val freshVerdictAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, ImgVerdict>()
                val imageDiagnosticAcc = java.util.concurrent.ConcurrentHashMap<DomainIp, SpeedTestEndpointDiagnostic>()
                val freshImageCount = AtomicInteger(0)
                val reusedImageCount = AtomicInteger(0)
                val imagePreparationTiming = timings.start(SpeedTestTimings.Phase.IMAGE_PREPARATION)
                reportStage(Progress(Phase.PREPARING_IMAGES))
                val imageHistory = compatibleHistory?.imgDelays.orEmpty()
                val selectedImageEndpoints = if (allowImageReuse) {
                    SpeedTestHistoryPolicy.selectRankedTop(imageHistory).toSet()
                } else {
                    emptySet()
                }
                val quickImageRetest = selectedImageEndpoints.isNotEmpty()
                val resolvedImages = if (quickImageRetest) emptyList() else imgTargets()
                val imageProbeTargets = if (quickImageRetest) selectedImageEndpoints.toList() else resolvedImages
                if (quickImageRetest) {
                    imageHistory.filterKeys { it !in selectedImageEndpoints }.forEach { (endpoint, delay) ->
                        imgAcc[endpoint] = delay
                        compatibleHistory?.imgVerdicts?.get(endpoint)?.let { verdictAcc[endpoint] = it }
                    }
                    reusedImageCount.set(imageHistory.size - selectedImageEndpoints.size)
                }
                reportStage(
                    Progress(
                        Phase.TESTING_IMAGES,
                        total = imageProbeTargets.size,
                        reusedImages = reusedImageCount.get(),
                        freshImages = 0,
                        reusedLines = retainedLineCount,
                        lineQuickTest = quickLineRetest,
                        imageQuickTest = quickImageRetest,
                    ),
                )
                imagePreparationTiming.close()
                val imageTiming = timings.start(SpeedTestTimings.Phase.IMAGES_INCLUDING_SAMPLE)
                val imgJobs = imageProbeTargets.map { endpoint ->
                    async {
                        probeSemaphore.withPermit {
                            val result = if (runIsCurrent()) {
                                measureImgAtIp(imgClient, endpoint) {
                                    if (runIsCurrent()) sharedSamplePath.await().takeIf { runIsCurrent() } else null
                                }
                            } else {
                                ImgProbe(MAX, ImgVerdict.UNREACHABLE, SpeedTestEndpointDiagnostic(false, null, 0L, null))
                            }
                            val delay = result.delayMs
                            val verdict = result.verdict
                            imageDiagnosticAcc[endpoint] = result.diagnostic
                            freshImageCount.incrementAndGet()
                            imgAcc[endpoint] = delay
                            verdictAcc[endpoint] = verdict
                            freshVerdictAcc[endpoint] = verdict
                            if (runIsCurrent()) {
                                lastImgIpVerdicts = verdictAcc.toMap()
                                lastImgVerdicts = aggregateImgVerdicts(verdictAcc)
                            }
                            if (runIsCurrent()) {
                                onProgress?.invoke(lines, imgAcc.toMap(), verdictAcc.toMap())
                            }
                            reportStage(
                                Progress(
                                    phase = Phase.TESTING_IMAGES,
                                    completed = freshImageCount.get().coerceAtMost(imageProbeTargets.size),
                                    total = imageProbeTargets.size,
                                    successful = freshVerdictAcc.values.count { it == ImgVerdict.OK },
                                    failed = freshVerdictAcc.values.count { it == ImgVerdict.UNREACHABLE },
                                    noImage = freshVerdictAcc.values.count { it == ImgVerdict.NO_IMAGE },
                                    freshImages = freshImageCount.get(),
                                    reusedImages = reusedImageCount.get(),
                                    reusedLines = retainedLineCount,
                                    lineQuickTest = quickLineRetest,
                                    imageQuickTest = quickImageRetest,
                                ),
                            )
                            endpoint to delay
                        }
                    }
                }
                imgJobs.awaitAll()
                imageTiming.close()
                val freshImages = imageDiagnosticAcc.keys.associateWith { imgAcc.getValue(it) }
                val imgs = if (quickImageRetest) {
                    SpeedTestHistoryPolicy.merge(imageHistory, selectedImageEndpoints, freshImages)
                } else {
                    freshImages
                }
                val imageDiagnostics = imageDiagnosticAcc.toMap()
                val mixedImageResult = retainedLineCount > 0 || reusedImageCount.get() > 0
                val imageQuickAllFailed = quickImageRetest && freshImages.isNotEmpty() &&
                    freshImages.keys.all { verdictAcc[it] != ImgVerdict.OK }
                val fullRetestRecommended = (lineQuickAllFailed && quickLineRetest) || imageQuickAllFailed
                if (!runIsCurrent()) return@coroutineScope (-1 to -1)
                lastImgIpVerdicts = verdictAcc.toMap()
                lastImgVerdicts = aggregateImgVerdicts(verdictAcc)

                reportStage(
                    Progress(
                        phase = Phase.APPLYING_RESULTS,
                        completed = freshImageCount.get().coerceAtMost(imageProbeTargets.size),
                        total = imageProbeTargets.size,
                        successful = freshVerdictAcc.values.count { it == ImgVerdict.OK },
                        failed = freshVerdictAcc.values.count { it == ImgVerdict.UNREACHABLE },
                        noImage = freshVerdictAcc.values.count { it == ImgVerdict.NO_IMAGE },
                        freshImages = freshImageCount.get(),
                        reusedImages = reusedImageCount.get(),
                        reusedLines = retainedLineCount,
                        fullRetestRecommended = fullRetestRecommended,
                        lineTopFiveAllFailed = lineQuickAllFailed,
                        imageTopFiveAllFailed = imageQuickAllFailed,
                        lineQuickTest = quickLineRetest,
                        imageQuickTest = quickImageRetest,
                    ),
                )
                if (!RouteManager.setEndpointDelays(
                        lines,
                        imgs,
                        verdictAcc,
                        remoteVersion = configVersionAtStart,
                        expectedConfigRevision = configRevisionAtStart,
                        invalidateCompletedSnapshot = mixedImageResult,
                        expectedPlatformRevision = platformRevisionAtStart,
                        lineUntestedEndpoints = if (quickLineRetest) lineHistory.keys - selectedLineEndpoints else emptySet(),
                        imageUntestedEndpoints = if (quickImageRetest) imageHistory.keys - selectedImageEndpoints else emptySet(),
                    )
                ) {
                    return@coroutineScope (-1 to -1)
                }
                if (!runIsCurrent()) return@coroutineScope (-1 to -1)
                val bestImg = if (quickImageRetest) {
                    freshImages.entries
                        .filter { it.value < MAX && freshVerdictAcc[it.key] == ImgVerdict.OK }
                        .minByOrNull { it.value }
                        ?.key
                } else {
                    RouteManager.fastestImgEndpoint
                }
                val imgApplied = bestImg?.let { endpoint ->
                    RouteManager.applyFastestImgEndpoint(
                        endpoint,
                        expectedConfigRevision = configRevisionAtStart,
                        expectedPlatformRevision = platformRevisionAtStart,
                    )
                } == true
                val imgDomainChanged = imgApplied && bestImg != null &&
                    (!previousImgDomain.equals(bestImg.domain, ignoreCase = true) || previousImgIp != bestImg.ip)
                // 按侧清理：线路变了只重建 API 侧；图源变了只驱逐图片侧空闲连接
                if (imgDomainChanged) NetworkModule.rebindImageRoute("测速应用图源")
                RouteManager.markSpeedTestToday()

                val completedAt = System.currentTimeMillis()
                if (!mixedImageResult) {
                    if (!RouteManager.setEndpointDelays(
                            line = lines,
                            img = imgs,
                            imgVerdicts = verdictAcc,
                            remoteVersion = configVersionAtStart,
                            expectedConfigRevision = configRevisionAtStart,
                            lineDiagnostics = lineDiagnostics,
                            imageDiagnostics = imageDiagnostics,
                            startedAtEpochMs = startedAt,
                            completedAtEpochMs = completedAt,
                            networkPath = networkPath,
                            completeSnapshot = true,
                            expectedPlatformRevision = platformRevisionAtStart,
                            imageCacheNetworkIdentity = imageCacheNetworkIdentityAtStart,
                        )
                    ) {
                        return@coroutineScope (-1 to -1)
                    }
                }
                if (!runIsCurrent()) return@coroutineScope (-1 to -1)
                lastRunAt = completedAt
                lastRunDirectStrategy = networkPath.directStrategy

                val vc = freshVerdictAcc.values.groupingBy { it }.eachCount()
                val fastest = freshImages.entries.filter { it.value < MAX && freshVerdictAcc[it.key] == ImgVerdict.OK }
                    .sortedBy { it.value }.take(5)
                    .joinToString(", ") { (endpoint, ms) -> "${endpoint.domain}(${endpoint.ip})=${ms}ms" }
                Log.i(
                    TAG,
                    "img probe summary total=${freshVerdictAcc.size} ok=${vc[ImgVerdict.OK] ?: 0}" +
                        " unreachable=${vc[ImgVerdict.UNREACHABLE] ?: 0} no_image=${vc[ImgVerdict.NO_IMAGE] ?: 0}" +
                        " samplePath=${if (resolvedSamplePath == null) "null" else "ok"} fastest=[$fastest]",
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
                reportStage(
                    Progress(
                        phase = Phase.COMPLETE,
                        completed = freshImageCount.get().coerceAtMost(imageProbeTargets.size),
                        total = imageProbeTargets.size,
                        successful = freshVerdictAcc.values.count { it == ImgVerdict.OK },
                        failed = freshVerdictAcc.values.count { it == ImgVerdict.UNREACHABLE },
                        noImage = freshVerdictAcc.values.count { it == ImgVerdict.NO_IMAGE },
                        freshImages = freshImageCount.get(),
                        reusedImages = reusedImageCount.get(),
                        reusedLines = retainedLineCount,
                        fullRetestRecommended = fullRetestRecommended,
                        lineTopFiveAllFailed = lineQuickAllFailed,
                        imageTopFiveAllFailed = imageQuickAllFailed,
                        lineQuickTest = quickLineRetest,
                        imageQuickTest = quickImageRetest,
                    ),
                )
                result
            } finally {
                samplePath?.cancel()
                running.set(false)
                _isRunning.value = false
                lineClient.releaseProbe()
                imgClient.releaseProbe()
                timings.finish()
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
        onStageProgress: ((Progress) -> Unit)? = null,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)) return@withContext (-1 to -1)
        configRunLock.lock()
        try {
            while (true) {
                if (!StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)) return@withContext (-1 to -1)
                while (running.get()) delay(50)
                val policy = StartupPlatformPolicy.state.value
                val result = runAndApply(
                    onProgress = onProgress,
                    onStageProgress = onStageProgress,
                    reuseLines = policy.useLastLineResult,
                    allowImageReuse = policy.useLastResult,
                )
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
