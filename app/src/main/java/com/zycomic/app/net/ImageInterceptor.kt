package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.CipherSource
import okio.ForwardingSource
import okio.buffer
import okio.source

/**
 * 图片拦截器：密文磁盘缓存 + 统一解密管线。
 * 缓存密文；命中缓存直接读文件；未命中边下边写缓存。
 * 对缓存命中/网络命中统一尝试解密，解密结果是图片魔数则流式解密，否则回退明文。
 */
class ImageInterceptor : Interceptor {

    companion object {
        private const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        private const val VALUE_X_REQUESTED_WITH = "zycomic.com"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url.toString()
        if (!isImageRequest(url)) return chain.proceed(original)

        val cached = ImageCacheManager.get(url)
        if (cached != null) {
            val cachedBody = object : ResponseBody() {
                override fun contentType() = "image/webp".toMediaType()
                override fun contentLength() = cached.length()
                override fun source(): BufferedSource = cached.source().buffer()
            }
            val cachedResp = Response.Builder()
                .request(original)
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(cachedBody).build()
            return decryptResponseIfNeeded(cachedResp)
        }

        val imgReq = original.newBuilder()
            .header("User-Agent", ManwaInterceptor.UA)
            .header(HEADER_X_REQUESTED_WITH, VALUE_X_REQUESTED_WITH)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        val response = chain.proceed(imgReq)
        val body = response.body ?: return response

        val cacheOut = ImageCacheManager.putStream(url)
        val originalSource = body.source()
        val cachingSource = object : ForwardingSource(originalSource) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val tmp = Buffer()
                val n = super.read(tmp, byteCount)
                if (n > 0) {
                    val bytes = tmp.readByteArray(n)
                    sink.write(bytes)
                    try { cacheOut.write(bytes) } catch (_: Exception) {}
                }
                if (n == -1L) {
                    try {
                        cacheOut.close()
                        ImageCacheManager.commit(url)
                    } catch (_: Exception) {}
                }
                return n
            }
        }
        val cachingBody = object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source(): BufferedSource = cachingSource.buffer()
        }
        return decryptResponseIfNeeded(response.newBuilder().body(cachingBody).build())
    }

    private fun decryptResponseIfNeeded(response: Response): Response {
        val body = response.body ?: return response
        val source = body.source()
        val url = response.request.url.toString()

        val rawHead = try { source.peek().readByteArray(16) } catch (_: Exception) { return response }
        if (rawHead.size >= 4 && isImageMagic(rawHead)) return response

        val len = body.contentLength()
        if (len > 0 && len % 16 != 0L) return response

        val forceDecrypt = url.contains("_zb")
        if (!forceDecrypt) {
            if (rawHead.size < 16) return response
            val decryptedHead = try {
                Crypto.createImageCipher().doFinal(rawHead)
            } catch (_: Exception) {
                return response
            }
            if (!isImageMagic(decryptedHead)) return response
        }

        val cipher = Crypto.createImageCipher()
        val safeSource = object : ForwardingSource(CipherSource(source, cipher)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                return try {
                    super.read(sink, byteCount)
                } catch (_: Exception) {
                    -1
                }
            }
        }
        val streamingBody = object : ResponseBody() {
            override fun contentType() = "image/webp".toMediaType()
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = safeSource.buffer()
        }
        return response.newBuilder().body(streamingBody).build()
    }

    private fun isImageRequest(url: String): Boolean {
        return url.contains("/static/upload") ||
            url.contains(".webp") || url.contains(".jpg") ||
            url.contains(".png") || url.contains(".gif")
    }

    private fun isImageMagic(bytes: ByteArray): Boolean {
        fun match(prefix: ByteArray): Boolean =
            bytes.size >= prefix.size && bytes.copyOf(prefix.size).contentEquals(prefix)
        if (match(byteArrayOf(0x52, 0x49, 0x46, 0x46)) && bytes.size >= 12 &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))
        ) return true
        if (match(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))) return true
        if (match(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return true
        if (match(byteArrayOf(0x47, 0x49, 0x46, 0x38))) return true
        return false
    }
}
