package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.CipherSource
import okio.buffer

/**
 * 图片拦截器：
 * 1. 为图片请求添加 X-Requested-With: zycomic.com 头。
 * 2. 对加密漫画图片使用 Okio [CipherSource] 流式解密 AES/CBC/NOPADDING，
 *    边下载边解密，绝不调用 .string() 以免破坏二进制数据。
 *
 * 加密判断（双层）：
 * - 快速路径：URL 含 "_zb" → 直接解密，无需检测文件头。
 * - 检测路径：URL 不含 "_zb" → peek 前 12 字节检测文件头，
 *   不是已知图片格式（WebP/JPEG/PNG/GIF/BMP）则视为加密，进行解密。
 *   这是因为部分漫画图片 URL 不含 "_zb" 但实际是加密的（如 32位哈希文件名）。
 *
 * 注意：解密后的 contentLength 未知（NOPADDING 对齐后长度变化），返回 -1。
 */
class ImageInterceptor : Interceptor {

    companion object {
        private const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        private const val VALUE_X_REQUESTED_WITH = "zycomic.com"

        /** 已知图片格式的文件头魔数（前若干字节） */
        private val IMAGE_MAGIC_NUMBERS = listOf(
            // WebP: "RIFF" + 4字节长度 + "WEBP"（检测前4字节和第8-11字节）
            // JPEG: FF D8 FF
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            // GIF: "GIF8"
            byteArrayOf(0x47, 0x49, 0x46, 0x38),
            // BMP: "BM"
            byteArrayOf(0x42, 0x4D),
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url.toString()

        if (!isImageRequest(url)) {
            return chain.proceed(original)
        }

        // 添加图片请求头
        val imgReq = original.newBuilder()
            .header("User-Agent", ManwaInterceptor.UA)
            .header(HEADER_X_REQUESTED_WITH, VALUE_X_REQUESTED_WITH)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        val response = chain.proceed(imgReq)
        val body = response.body ?: return response

        // 判断是否需要解密
        val needDecrypt = isEncryptedImageUrl(url) || isEncryptedByMagicNumber(body)

        if (!needDecrypt) {
            return response
        }

        // 流式解密：CipherSource 包装原始 body.source()，边读边解密
        val cipher = Crypto.createImageCipher()
        val cipherSource = CipherSource(body.source(), cipher)

        val streamingBody = object : ResponseBody() {
            override fun contentType() = "image/webp".toMediaType()
            override fun contentLength(): Long = -1 // NOPADDING 后长度不确定
            override fun source(): BufferedSource = cipherSource.buffer()
        }

        return response.newBuilder()
            .body(streamingBody)
            .build()
    }

    private fun isImageRequest(url: String): Boolean {
        return url.contains("/static/upload") ||
            url.contains(".webp") || url.contains(".jpg") ||
            url.contains(".png") || url.contains(".gif")
    }

    /** 快速路径：URL 含 "_zb" 视为加密图片 */
    private fun isEncryptedImageUrl(url: String): Boolean {
        return url.contains("_zb")
    }

    /**
     * 检测路径：peek 前 12 字节，判断是否是已知图片格式。
     * 不是已知格式则视为加密（部分漫画图片 URL 不含 "_zb" 但实际加密）。
     *
     * WebP 特殊处理：前4字节为 "RIFF" 且第8-11字节为 "WEBP"。
     */
    private fun isEncryptedByMagicNumber(body: ResponseBody): Boolean {
        return try {
            val peeked: ByteArray = body.source().peek().readByteArray(12)
            // WebP 检测：RIFF....WEBP
            if (peeked.size >= 12 &&
                peeked[0] == 0x52.toByte() && peeked[1] == 0x49.toByte() &&
                peeked[2] == 0x46.toByte() && peeked[3] == 0x46.toByte() &&
                peeked[8] == 0x57.toByte() && peeked[9] == 0x45.toByte() &&
                peeked[10] == 0x42.toByte() && peeked[11] == 0x50.toByte()
            ) {
                return false // 是 WebP，未加密
            }
            // 其他格式检测（JPEG/PNG/GIF/BMP）
            for (magic in IMAGE_MAGIC_NUMBERS) {
                if (peeked.size >= magic.size &&
                    peeked.copyOfRange(0, magic.size).contentEquals(magic)
                ) {
                    return false // 是已知格式，未加密
                }
            }
            // 不是已知图片格式 → 视为加密
            true
        } catch (_: Exception) {
            // peek 失败（如响应体为空）→ 保守起见不解密
            false
        }
    }
}
