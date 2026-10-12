package com.zycomic.app.net

import android.util.Log
import com.zycomic.app.data.dto.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 线路 / 图域测速。
 *
 * 关键一致性原则：测速使用的 OkHttpClient 由 [NetworkModule.newSpeedTestClient] 构建，
 * 其 DNS/SNI/证书/出口（cookieJar/传输策略）与当前网络下的运行时 api/image client 完全一致——
 * VPN 开时测速即走系统 DNS+SNI 的 VPN 路径，无 VPN 时走 RuleDns+去 SNI 直连。
 *
 * 图域探活打"真实封面 URL"（首页 index 取一张封面路径），带与图片运行时一致的头并只取前 2KB，
 * 仅当返回 200/206 且确为图片/非文本二进制才计成功：VPN 下返回 403/HTML 挑战页的图域会被自然淘汰。
 */
object SpeedTester {

    private const val TAG = "SpeedTester"
    private const val MAX = Long.MAX_VALUE
    /** 探活只取前 2KB，避免逐域拉整张图。 */
    private const val PROBE_BYTES = 2048L

    /** 防止启动测速与网络变化重测速并发重叠。 */
    private val running = AtomicBoolean(false)

    /** 最近一次测速完成时间与当时的直连策略，供网络回调判断是否需要重测。 */
    @Volatile var lastRunAt: Long = 0L
        private set
    @Volatile var lastRunDirectStrategy: Boolean? = null
        private set

    // ==================== 请求构造 ====================

    private fun lineRequest(lineUrl: String): Request {
        val ts = System.currentTimeMillis().toString()
        val url = "$lineUrl/api/index/index?facility=android&deviceid=${ManwaInterceptor.DEVICE_ID}&timestamp=$ts"
        return Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", ManwaInterceptor.UA)
            .header("devid", ts)
            .header("X-Token", Crypto.md5Hex(ts + Crypto.XTOKEN_SALT))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", ManwaInterceptor.ACCEPT_LANGUAGE)
            .header("Origin", ManwaInterceptor.ORIGIN)
            .header("Referer", ManwaInterceptor.REFERER)
            .header("Connection", "keep-alive")
            .build()
    }

    /**
     * 图域探活请求：[probePath] 为真实封面相对路径时打该 URL（带 Range 与运行时同款图片头）；
     * 无可用样本路径时退回根路径（仍按图片/非文本严格判负）。
     */
    private fun imgProbeRequest(domain: String, probePath: String?): Request {
        val builder = Request.Builder()
            .get()
            .header("User-Agent", ManwaInterceptor.UA)
            .header("X-Requested-With", "zycomic.com")
            .header("Referer", RouteManager.baseUrl.trimEnd('/'))
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

    /** 线路探活：仅 2xx 计成功（API 经 ManwaInterceptor 解密与否不影响状态码判定）。 */
    private suspend fun measureLine(client: OkHttpClient, req: Request): Long =
        measure(client, req, strictImage = false)

    /** 图域探活：200/206 且为图片/非文本二进制才计成功。 */
    private suspend fun measureImg(client: OkHttpClient, req: Request): Long =
        measure(client, req, strictImage = true)

    private suspend fun measure(client: OkHttpClient, req: Request, strictImage: Boolean): Long =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            try {
                client.newCall(req).execute().use { resp ->
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    val head = resp.body?.source()?.use { it.readByteArray(PROBE_BYTES) } ?: ByteArray(0)
                    val ok = resp.code in 200..299 &&
                        (!strictImage || isImageLike(contentType, head))
                    if (ok) {
                        (System.nanoTime() - start) / 1_000_000
                    } else {
                        Log.d(TAG, "probe reject url=${req.url} code=${resp.code} ct=$contentType")
                        MAX
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "probe fail url=${req.url} err=${e.message}")
                MAX
            }
        }

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

    // ==================== 真实探活样本路径 ====================

    /**
     * 获取探活图片相对路径：先用当前线路调首页 index 取一张封面（picx/pic），
     * 成功则持久化；失败回退上次缓存路径，再没有返回 null（探活退化为根路径严格判负）。
     */
    private suspend fun obtainProbePath(): String? {
        val live = runCatching {
            val data: Manga? = NetworkModule.api.index().data
            buildList {
                if (data != null) {
                    add(data)
                    addAll(data.loveList)
                }
            }.mapNotNull { m -> toImagePath(m.picx.ifBlank { m.pic }) }.firstOrNull()
        }.getOrNull()
        if (live != null) {
            RouteManager.saveProbeImgPath(live)
            return live
        }
        return RouteManager.loadProbeImgPath()
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

    suspend fun testAllLines(): Map<Int, Long> = coroutineScope {
        val client = NetworkModule.newSpeedTestClient()
        RouteManager.lineHosts.mapIndexed { index, lineUrl ->
            async { index to measureLine(client, lineRequest(lineUrl)) }
        }.awaitAll().toMap()
    }

    /** 图域全量探活（ReaderActivity 手动入口用）；样本路径取缓存，无则根路径严格判负。 */
    suspend fun testAllImgHosts(): Map<Int, Long> = coroutineScope {
        val client = NetworkModule.newSpeedTestClient()
        val probePath = RouteManager.loadProbeImgPath()
        RouteManager.imgDomains.mapIndexed { index, domain ->
            async { index to measureImg(client, imgProbeRequest(domain, probePath)) }
        }.awaitAll().toMap()
    }

    suspend fun testSingleImgHost(domain: String): Long {
        val client = NetworkModule.newSpeedTestClient()
        return measureImg(client, imgProbeRequest(domain, RouteManager.loadProbeImgPath()))
    }

    suspend fun testSingleLine(lineUrl: String): Long {
        val client = NetworkModule.newSpeedTestClient()
        return measureLine(client, lineRequest(lineUrl))
    }

    fun selectFastestLine(delays: Map<Int, Long>): Int =
        delays.filterValues { it < MAX }.minByOrNull { it.value }?.key ?: 0

    // ==================== 测速 + 应用（启动 / 设置页 / 网络变化三处复用）====================

    /**
     * 两阶段测速并应用结果：
     * 1) 线路探活（与运行时同传输策略）→ 选最快线路并重建 Retrofit；
     * 2) 取首页真实封面路径 → 图域探活 → 选最快图域；
     * 3) 成功才替换当前线路/图域；全部失败保留现状。
     *
     * @param onProgress 每出一条结果回调一次（线路 Map、图域 Map 的不可变快照），供 UI 逐条刷新。
     * @return (最快线路 index, 最快图域 index)，失败项为 -1。已有测速在跑时直接返回 (-1,-1)。
     */
    suspend fun runAndApply(
        onProgress: ((line: Map<Int, Long>, img: Map<Int, Long>) -> Unit)? = null,
    ): Pair<Int, Int> = coroutineScope {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "runAndApply skipped: another speed test is running")
            return@coroutineScope (-1 to -1)
        }
        try {
            val client = NetworkModule.newSpeedTestClient()

            // 阶段 1：线路
            val lineAcc = java.util.concurrent.ConcurrentHashMap<Int, Long>()
            val lineJobs = RouteManager.lineHosts.mapIndexed { index, lineUrl ->
                async {
                    val v = measureLine(client, lineRequest(lineUrl))
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
                NetworkModule.rebuild() // 让首页 index 走最快线路
            }
            Log.i(TAG, "line test done, best=$bestLine")

            // 阶段 2：真实封面样本路径
            val probePath = obtainProbePath()
            Log.i(TAG, "img probe sample path=$probePath")

            // 阶段 3：图域
            val imgAcc = java.util.concurrent.ConcurrentHashMap<Int, Long>()
            val imgJobs = RouteManager.imgDomains.mapIndexed { index, domain ->
                async {
                    val v = measureImg(client, imgProbeRequest(domain, probePath))
                    imgAcc[index] = v
                    onProgress?.invoke(lines, imgAcc.toSortedMap())
                    index to v
                }
            }
            val imgs = imgJobs.awaitAll().toMap()

            // 结果一次性写入，fastestImgDomain 据此派生
            RouteManager.setLastDelays(lines, imgs)
            val bestImg = imgs.filterValues { it < MAX }.minByOrNull { it.value }?.key
            if (bestImg != null && bestImg != RouteManager.imgIndex) {
                RouteManager.setImgHost(bestImg)
            }
            if (bestLine != null || bestImg != null) NetworkModule.rebuild()
            RouteManager.markSpeedTestToday()

            lastRunAt = System.currentTimeMillis()
            lastRunDirectStrategy = NetworkModule.shouldUseDirectStrategy()
            Log.i(TAG, "runAndApply done, bestLine=$bestLine bestImg=$bestImg direct=$lastRunDirectStrategy")
            (bestLine ?: -1) to (bestImg ?: -1)
        } finally {
            running.set(false)
        }
    }
}
