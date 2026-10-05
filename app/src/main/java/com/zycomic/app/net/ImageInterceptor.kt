package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource

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
            val bytes = cached.readBytes()
            val out = decryptIfNeeded(bytes, url)
            return buildResponse(original, out)
        }

        val imgReq = original.newBuilder()
            .header("User-Agent", ManwaInterceptor.UA)
            .header(HEADER_X_REQUESTED_WITH, VALUE_X_REQUESTED_WITH)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .build()

        val response = chain.proceed(imgReq)
        val body = response.body ?: return response
        val raw = try { body.source().readByteArray() } catch (_: Exception) { return response }

        try {
            ImageCacheManager.putStream(url).use { it.write(raw) }
            ImageCacheManager.commit(url)
        } catch (_: Exception) {}

        val out = decryptIfNeeded(raw, url)
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

    private fun decryptIfNeeded(raw: ByteArray, url: String): ByteArray {
        if (isImageMagic(raw)) return raw
        val decrypted = try { Crypto.createImageCipher().doFinal(raw) } catch (_: Exception) { return raw }
        return if (isImageMagic(decrypted)) decrypted else raw
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
