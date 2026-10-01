package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody

/**
 * 漫画接口拦截器：
 * 1. 自动追加通用 query 参数：facility=android, deviceid=<固定>, timestamp=<毫秒级>。
 * 2. 自动追加鉴权请求头：X-Token=MD5(ts+XTOKEN_SALT), devid=<纯毫秒 ts>, User-Agent。
 * 3. 响应体若不是明文 JSON（不以 { 或 [ 开头），则用 AES-256-ECB 解密。
 *
 * 关键：timestamp 在此处生成后同时写入 URL query 和 X-Token/decrypt，三者必须完全一致。
 */
class ManwaInterceptor : Interceptor {

    companion object {
        const val DEVICE_ID = "asawsdqwefwsnjfiowehnfjuoweisfnuj"

        const val UA =
            "Mozilla/5.0 (Linux; Android 16; PLC110 Build/BP2A.250605.015; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/150.0.7871.181 Mobile Safari/537.36 " +
            "uni-app Html5Plus/1.0 (Immersed/40.285713)"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url.toString()

        // 图片请求不在此拦截器处理（由 ImageInterceptor 负责）
        if (isImageRequest(url)) {
            return chain.proceed(original)
        }

        // 毫秒级时间戳：同时用于 URL query、X-Token、响应解密
        val ts = System.currentTimeMillis().toString()

        val newUrl = original.url.newBuilder()
            .addQueryParameter("facility", "android")
            .addQueryParameter("deviceid", DEVICE_ID)
            .addQueryParameter("timestamp", ts)
            .build()

        val authed = original.newBuilder()
            .url(newUrl)
            .header("User-Agent", UA)
            .header("devid", ts)
            .header("X-Token", Crypto.md5Hex(ts + Crypto.XTOKEN_SALT))
            .header("Accept", "application/json, text/plain, */*")
            .header("Connection", "keep-alive")
            .build()

        val response = chain.proceed(authed)

        // 读取响应体（接口响应体积小，可安全 string()）
        val rawBody = response.body?.string().orEmpty()
        val trimmed = rawBody.trim()

        val plain: String = if (trimmed.isNotEmpty() &&
            !trimmed.startsWith("{") && !trimmed.startsWith("[")
        ) {
            try {
                Crypto.decryptResponse(trimmed, ts)
            } catch (_: Exception) {
                rawBody
            }
        } else {
            rawBody
        }

        val mediaType = response.body?.contentType()
        val newBody = plain.toResponseBody(mediaType)
        return response.newBuilder().body(newBody).build()
    }

    private fun isImageRequest(url: String): Boolean {
        return url.contains("/static/upload") ||
            url.contains(".webp") || url.contains(".jpg") ||
            url.contains(".png") || url.contains(".gif")
    }
}
