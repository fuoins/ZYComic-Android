package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.CipherSource
import okio.ForwardingSource
import okio.buffer
import okio.Okio

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
                override fun source(): BufferedSource = Okio.source(cached).buffer()
            }
            val cachedResp = original.newBuilder()
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
                    tmp.copyTo(sink, 0, n)
                    try { cacheOut.write(tmp, n) } catch (_: Exception) {}
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
        val headEncrypted = try {
            body.source().peek().readByteArray(16)
        } catch (_: Exception) {
            return response
        }
        if (headEncrypted.size < 16) return response

        val headDecrypted = try {
            Crypto.createImageCipher().doFinal(headEncrypted)
        } catch (_: Exception) {
            return response
        }
        if (!isImageMagic(headDecrypted)) return response

        val cipher = Crypto.createImageCipher()
        val cipherSource = CipherSource(body.source(), cipher)
        val streamingBody = object : ResponseBody() {
            override fun contentType() = "image/webp".toMediaType()
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = cipherSource.buffer()
        }
        return response.newBuilder().body(streamingBody).build()
    }

    private fun isImageRequest(url: String): Boolean {
        return url.contains("/static/upload") ||
            url.contains(".webp") || url.contains(".jpg") ||
            url.contains(".png") || url.contains(".gif")
    }

    private fun isImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46 &&
            bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50
        ) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return true
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38) return true
        return false
    }
}
