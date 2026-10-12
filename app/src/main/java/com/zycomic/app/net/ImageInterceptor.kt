package com.zycomic.app.net

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource

/**
 * 图片拦截器：加请求头（含 Referer 防盗链）+ 加密漫画图片解密 + 加密图片磁盘缓存。
 *
 * 健壮性：
 * - 非 2xx 响应不伪装成图片、不落缓存；最终全部尝试失败时返回真实状态码（如 403），
 *   让阅读器显示准确错误；
 * - 只有校验为有效图片（魔数匹配）才写入 ImageCacheManager；
 * - 缓存命中后解密，若仍非有效图片视为坏缓存，删除该缓存并按未命中重新走网络；
 * - 失败兜底（对标官方 switchImageHost）：网络类错误（reset/超时/连接失败）先同域重试 1 次，
 *   之后按图域列表最多换 2 个未冷却图域重试；HTTP 4xx/5xx 或非图片内容直接换域；
 *   失败图域进入短期冷却（[RouteManager.markImgDomainFailed]）。封面经统一图片栈同样受益。
 */
class ImageInterceptor : Interceptor {

    companion object {
        private const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        private const val VALUE_X_REQUESTED_WITH = "zycomic.com"
        /** 同域重试之后，最多换几个图域。 */
        private const val MAX_ROTATE_DOMAINS = 2
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val originalUrl = original.url.toString()
        if (!isImageRequest(originalUrl)) return chain.proceed(original)

        val fetchUrl = if (RouteManager.useFastestImgForAll && !RouteManager.fastestImgDomain.isNullOrBlank()) {
            originalUrl.replace(Regex("https?://[^/]+"), "https://${RouteManager.fastestImgDomain}")
        } else originalUrl

        // 缓存命中分支：读出后解密校验；坏缓存删除后按未命中重取
        ImageCacheManager.get(originalUrl)?.let { cached ->
            val bytes = try { cached.readBytes() } catch (_: Exception) { null }
            if (bytes != null) {
                val out = decryptIfNeeded(bytes, originalUrl)
                if (isImageMagic(out)) {
                    return buildResponse(original, out)
                }
            }
            // 坏缓存：删除并落到网络重新获取
            try { cached.delete() } catch (_: Exception) {}
        }

        val imgReq = original.newBuilder()
            .url(fetchUrl)
            .header("User-Agent", ManwaInterceptor.UA)
            .header(HEADER_X_REQUESTED_WITH, VALUE_X_REQUESTED_WITH)
            // 防盗链 Referer：用**固定 canonical 域**（抓包证实官方 App 无论打哪个线路/图域，
            // Origin/Referer 恒为 mseeowpm1.xyz）。用"动态当前线路 origin"在线路为 .pro/.cc
            // 时不在白名单，会被 403。
            .header("Referer", ManwaInterceptor.REFERER)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        var request = imgReq
        var sameHostRetried = false
        var rotated = 0
        var lastErrorCode = -1
        var lastError: IOException? = null

        while (true) {
            val host = request.url.host
            val outcome = proceedOnce(chain, request, originalUrl)
            if (outcome is Outcome.Success) {
                return buildResponse(original, outcome.bytes)
            }

            val fail = outcome as Outcome.Fail
            // 记录失败图域冷却（HTTP 失败 / 非图片 / 网络错误都记）
            RouteManager.markImgDomainFailed(host)
            when (fail) {
                is Outcome.Fail.Http -> lastErrorCode = fail.code
                is Outcome.Fail.IO -> lastError = fail.error
            }

            // 决定下一次尝试的主机：
            // 网络类错误先同域重试 1 次；HTTP/内容错误或同域已重试 → 换未冷却图域（最多 2 个）
            val nextHost: String? = when {
                fail is Outcome.Fail.IO && !sameHostRetried -> {
                    sameHostRetried = true
                    host // 同域重试
                }
                rotated < MAX_ROTATE_DOMAINS -> {
                    val cand = RouteManager.nextAvailableImgDomain(host)
                    if (cand != null) {
                        rotated++
                        sameHostRetried = true
                    }
                    cand
                }
                else -> null
            }

            if (nextHost == null) break
            if (!nextHost.equals(host, ignoreCase = true)) {
                request = replaceHost(request, nextHost)
            }
        }

        // 全部尝试失败：有真实 HTTP 状态码则透传（阅读器据此显示准确的 403 等），否则抛网络异常
        if (lastErrorCode > 0) return errorResponse(original, lastErrorCode)
        throw lastError ?: IOException("图片加载失败：$originalUrl")
    }

    /** 单次尝试：成功返回解密后的图片字节；任何失败归为 Http/IO。 */
    private fun proceedOnce(
        chain: Interceptor.Chain,
        request: Request,
        originalUrl: String,
    ): Outcome {
        val response = try {
            chain.proceed(request)
        } catch (io: IOException) {
            return Outcome.Fail.IO(io)
        }

        // 非 2xx：不消费/伪装，记录状态码后交上层换域
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            return Outcome.Fail.Http(code)
        }

        val body = response.body ?: run {
            response.close()
            return Outcome.Fail.IO(IOException("图片响应为空"))
        }
        val raw = try {
            body.source().readByteArray()
        } catch (_: Exception) {
            response.close()
            return Outcome.Fail.IO(IOException("读取图片响应失败"))
        }

        if (raw.isEmpty()) {
            response.close()
            return Outcome.Fail.IO(IOException("图片响应为空"))
        }

        val out = decryptIfNeeded(raw, originalUrl)
        if (!isImageMagic(out)) {
            response.close()
            // 原始响应其实是错误页/拦截页
            return Outcome.Fail.Http(response.code)
        }

        response.close()

        // 只有校验为有效图片才写加密缓存
        try {
            ImageCacheManager.putStream(originalUrl).use { it.write(raw) }
            ImageCacheManager.commit(originalUrl)
        } catch (_: Exception) {}

        return Outcome.Success(out)
    }

    private fun replaceHost(request: Request, host: String): Request {
        val newUrl = request.url.toString().replace(Regex("https?://[^/]+"), "https://$host")
        return request.newBuilder().url(newUrl).build()
    }

    private fun buildResponse(request: Request, bytes: ByteArray): Response {
        val body = object : ResponseBody() {
            override fun contentType() = "image/webp".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun source(): BufferedSource = Buffer().apply { write(bytes) }
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body(body).build()
    }

    private fun errorResponse(request: Request, code: Int): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code).message("img fail")
            .body(ByteArray(0).toResponseBody(null))
            .build()
    }

    /** 已加密则解密；原始即图片则原样返回。调用方负责用 [isImageMagic] 校验结果。 */
    private fun decryptIfNeeded(raw: ByteArray, url: String): ByteArray {
        if (isImageMagic(raw)) return raw
        return try { Crypto.createImageCipher().doFinal(raw) } catch (_: Exception) { raw }
    }

    private fun isImageRequest(url: String): Boolean {
        return url.contains("/static/upload") ||
            url.contains(".webp") || url.contains(".jpg") ||
            url.contains(".png") || url.contains(".gif")
    }

    private fun isImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes.size >= 12 && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) return true
        return false
    }

    private sealed class Outcome {
        class Success(val bytes: ByteArray) : Outcome()
        sealed class Fail : Outcome() {
            class Http(val code: Int) : Fail()
            class IO(val error: IOException) : Fail()
        }
    }
}
