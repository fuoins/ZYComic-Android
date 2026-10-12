package com.zycomic.app.net

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource

/**
 * 图片拦截器：加请求头 + 加密漫画图片解密 + 加密图片磁盘缓存。
 *
 * 健壮性（R1/R2）：
 * - 非 2xx 响应原样透传（真实状态码/Content-Type/body），不伪装成 200/image/webp、不落缓存；
 * - 只有校验为有效图片（魔数匹配）才写入 ImageCacheManager；
 * - 缓存命中后解密，若仍非有效图片视为坏缓存，删除该缓存并按未命中重新走网络；
 * - 解密后非图片 / 空响应 / 解密异常，直接抛 IOException，让 Coil 走"加载失败+重试"，
 *   杜绝 "Failed to initialize decoder" 技术异常。
 */
class ImageInterceptor : Interceptor {

    companion object {
        private const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        private const val VALUE_X_REQUESTED_WITH = "zycomic.com"
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
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        val response = chain.proceed(imgReq)

        // R1：非 2xx 原样透传（真实状态码/Content-Type/body），不包装成图片、不落缓存
        if (!response.isSuccessful) return response

        val body = response.body ?: return response
        val raw = try {
            body.source().readByteArray()
        } catch (_: Exception) {
            throw IOException("读取图片响应失败")
        }

        if (raw.isEmpty()) throw IOException("图片响应为空")

        val out = decryptIfNeeded(raw, originalUrl)
        if (!isImageMagic(out)) {
            // 解密后仍非图片：原始响应其实是错误页/拦截页，走加载失败，绝不落缓存
            throw IOException("响应不是有效图片(HTTP ${response.code})")
        }

        // R2：只有校验为有效图片才写加密缓存
        try {
            ImageCacheManager.putStream(originalUrl).use { it.write(raw) }
            ImageCacheManager.commit(originalUrl)
        } catch (_: Exception) {}

        return buildResponse(original, out)
    }

    private fun buildResponse(request: okhttp3.Request, bytes: ByteArray): Response {
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
}
