package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.CipherSource
import okio.buffer

/**
 * 图片拦截器：
 * 1. 为图片请求添加 X-Requested-With: zycomic.com 头。
 * 2. 对加密漫画图片（URL 含 "_zb"）使用 Okio [CipherSource] 流式解密 AES/CBC/NOPADDING，
 *    边下载边解密，绝不调用 .string() 以免破坏二进制数据。
 *
 * 注意：解密后的 contentLength 未知（NOPADDING 对齐后长度变化），返回 -1。
 */
class ImageInterceptor : Interceptor {

    companion object {
        private const val HEADER_X_REQUESTED_WITH = "X-Requested-With"
        private const val VALUE_X_REQUESTED_WITH = "zycomic.com"
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

        // 非加密图片（封面等）直接透传
        if (!isEncryptedImageUrl(url)) {
            return response
        }

        val body = response.body ?: return response

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

    /** 加密漫画图片 URL 特征（_zb.webp） */
    private fun isEncryptedImageUrl(url: String): Boolean {
        return url.contains("_zb")
    }
}
