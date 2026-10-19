package com.zycomic.app.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.zycomic.app.reader.JmRelatedMangaPolicy
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/** JM 专属图床 fallback 与已验证的章节图 gunzip / 条带逆序流程。 */
class JmImageInterceptor internal constructor(
    private val imageCandidates: () -> List<String> = JmRouteManager::imageCandidates,
    private val prepareImageRoute: (String) -> Unit = JmNetworkModule::prepareImageRoute,
    private val recordImageSuccess: (String) -> Unit = JmRouteManager::recordImageSuccess,
    private val recordImageFailure: (String, String) -> Unit = JmRouteManager::recordImageFailure,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.encodedPath.startsWith("/media/")) return chain.proceed(original)

        val threshold = original.url.queryParameter(SCRAMBLE_QUERY)
            ?.toIntOrNull() ?: JmImagePolicy.DEFAULT_SCRAMBLE_THRESHOLD
        val path = original.url.encodedPath
        val scramble = JmImagePolicy.scrambleCount(path, threshold)
        JmImagePolicy.chapterId(path)?.takeIf(JmImagePolicy::isScrambleBoundaryChapter)?.let { chapterId ->
            JmRouteManager.record(
                "scramble_boundary",
                chapterId.toString(),
                "章节 ID 命中 421925–421927 重排边界区间；" +
                    "当前按 epId >= 421926 使用 base=8，待实测确认",
            )
        }
        val networkUrl = original.url.newBuilder().removeAllQueryParameters(SCRAMBLE_QUERY).build()
        val scope = original.tag(PageAttemptScope::class.java)
        fun isCancelled() = chain.call().isCanceled() || scope?.isCurrent == false
        fun requireActive() {
            if (isCancelled()) throw IOException("图片加载已取消")
        }
        requireActive()
        val configuredCandidates = imageCandidates()
        val candidates = if (path.startsWith("/media/albums/")) {
            JmRelatedMangaPolicy.imageHostsForAlbum(original.url.host, configuredCandidates)
        } else {
            configuredCandidates
        }
        var lastError: IOException? = null
        var lastStatus: Int? = null
        for (host in candidates) {
            requireActive()
            if (scope != null) {
                if (scope.page.endpointsAttemptedInRound.get() >= PageAttemptScope.MAX_ENDPOINTS_PER_ROUND) break
                // JM transport owns DNS/IP selection; the retry identity is the hostname route.
                if (!scope.recordEndpointAttempt(DomainIp(host, ""))) continue
            }
            val url = networkUrl.newBuilder().host(host).build()
            val request = original.newBuilder()
                .url(url)
                .header("User-Agent", JmApiInterceptor.USER_AGENT)
                .header("Origin", JmApiInterceptor.ORIGIN)
                .header("Referer", JmApiInterceptor.REFERER)
                .header("X-Requested-With", JmApiInterceptor.PACKAGE_NAME)
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .build()
            val startedAt = System.nanoTime()
            scope?.endpointRequestStarted((scope.page.endpointsAttemptedInRound.get() - 1).coerceAtLeast(0), PageAttemptScope.MAX_ENDPOINTS_PER_ROUND)
            try {
                prepareImageRoute(host)
                requireActive()
                val response = chain.proceed(request)
                if (isCancelled()) {
                    response.close()
                    requireActive()
                }
                val isImage = response.header("Content-Type")?.substringBefore(';')
                    ?.startsWith("image/", ignoreCase = true) == true
                if (response.isSuccessful && isImage) {
                    return try {
                        val restored = restoreScrambledImage(response, scramble, scope)
                        requireActive()
                        recordImageSuccess(host)
                        restored
                    } catch (error: IOException) {
                        response.close()
                        throw error
                    }
                }
                lastStatus = response.code
                response.close()
                requireActive()
                recordImageFailure(host, "JM 图床 HTTP $lastStatus")
            } catch (error: IOException) {
                // Coil cover requests have no PageAttemptScope; Call cancellation is authoritative.
                if (isCancelled()) throw error
                lastError = error
                recordImageFailure(host, error.message ?: error.javaClass.simpleName)
            } finally {
                scope?.endpointRequestFinished()
                scope?.addNetworkTime(System.nanoTime() - startedAt)
            }
        }
        throw lastError ?: IOException("JM image request failed${lastStatus?.let { " (HTTP $it)" } ?: ""}")
    }

    private fun restoreScrambledImage(response: Response, count: Int, scope: PageAttemptScope?): Response {
        val body = response.body ?: throw IOException("JM 正文图响应缺少图片内容")
        val encoded = readEncodedBody(body, scope)
        val hasGzipEncoding = response.header("Content-Encoding").equals("gzip", ignoreCase = true)
        val isGzip = encoded.size >= 2 && encoded[0] == 0x1f.toByte() && encoded[1] == 0x8b.toByte()
        val raw = if (isGzip) {
            try {
                GZIPInputStream(ByteArrayInputStream(encoded)).use { it.readBytes() }
            } catch (error: Exception) {
                throw IOException("JM 正文图 gzip 解压失败", error)
            }
        } else {
            encoded
        }
        if (raw.size >= GIF_SIGNATURE.size && raw.copyOfRange(0, GIF_SIGNATURE.size).contentEquals(GIF_SIGNATURE)) {
            return response.newBuilder()
                .removeHeader("Content-Length")
                .apply { if (isGzip || hasGzipEncoding) removeHeader("Content-Encoding") }
                .body(raw.toResponseBody("image/gif".toMediaTypeOrNull()))
                .build()
        }
        val shouldScramble = count > 1 && response.request.url.encodedPath.contains("/media/photos/")
        if (!shouldScramble) {
            return response.newBuilder()
                .removeHeader("Content-Length")
                .apply { if (isGzip || hasGzipEncoding) removeHeader("Content-Encoding") }
                .body(raw.toResponseBody(body.contentType()))
                .build()
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || count > height || width.toLong() * height.toLong() > MAX_SCRAMBLE_PIXELS) {
            throw IOException("JM 正文图无法按条带数 $count 安全解码（图像 ${width}x$height）")
        }
        val source = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IOException("JM 正文图格式无法解码；需要一张原始图片样本确认编码")
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val baseHeight = height / count
            val remainder = height % count
            for (index in 0 until count) {
                val sourceHeight = baseHeight + if (index == 0) remainder else 0
                val sourceTop = height - baseHeight * (index + 1) - remainder
                val destinationTop = baseHeight * index + if (index == 0) 0 else remainder
                val sourceRect = Rect(0, sourceTop, width, sourceTop + sourceHeight)
                val destinationRect = Rect(0, destinationTop, width, destinationTop + sourceHeight)
                canvas.drawBitmap(source, sourceRect, destinationRect, paint)
            }
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                    throw IOException("JM 正文图重排后 JPEG 编码失败")
                }
                stream.toByteArray()
            }
            return response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .removeHeader("Content-MD5")
                .header("Content-Type", "image/jpeg")
                .body(bytes.toResponseBody("image/jpeg".toMediaTypeOrNull()))
                .build()
        } finally {
            source.recycle()
            output.recycle()
        }
    }

    /** Report transport bytes before gunzip/strip restoration, using the reader's shared state. */
    private fun readEncodedBody(body: ResponseBody, scope: PageAttemptScope?): ByteArray {
        if (scope == null) return body.bytes()
        val source = object : ForwardingSource(body.source()) {
            private var received = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!scope.isCurrent) throw IOException("图片加载已取消")
                val read = super.read(sink, byteCount)
                if (!scope.isCurrent) throw IOException("图片加载已取消")
                if (read > 0) {
                    received += read
                    scope.reportProgress(received, body.contentLength())
                }
                return read
            }
        }.buffer()
        // Keep ResponseBody.bytes() length validation and source-closing semantics intact.
        return object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }.bytes()
    }

    private companion object {
        const val SCRAMBLE_QUERY = "_jm_scramble"
        const val MAX_SCRAMBLE_PIXELS = 16_000_000L
        val GIF_SIGNATURE = byteArrayOf(0x47, 0x49, 0x46)
    }
}
