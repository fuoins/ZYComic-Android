package com.zycomic.app.net

import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** JM API 专用协议；不调用 ManwaInterceptor / Crypto。 */
class JmApiInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        val host = original.url.host
        val isImage = path.startsWith("/media/")
        val isUnsigned = path == "/confirm" || path == "/setting"
        val builder = original.newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .header("X-Requested-With", PACKAGE_NAME)
            .header("Authorization", "Bearer")
            .header("Sec-Fetch-Storage-Access", "active")
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        if (!isImage && !isUnsigned) {
            builder.header("token", md5Hex(timestamp + REQUEST_SALT))
                .header("tokenparam", "$timestamp,$APP_VERSION")
        }
        val request = builder.build()
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            if (!isImage && !isUnsigned) JmRouteManager.recordApiFailure(host, error.javaClass.simpleName)
            throw error
        }
        if (isImage || isUnsigned || response.body == null) return response

        val raw = response.body!!.string()
        if (!response.isSuccessful) {
            JmRouteManager.recordApiFailure(host, "JM HTTP ${response.code}")
            return response.newBuilder().body(raw.toResponseBody(response.body?.contentType())).build()
        }

        val envelope = try {
            JSONObject(raw)
        } catch (error: Exception) {
            JmRouteManager.recordApiFailure(host, "JM 响应解密/解析失败")
            throw IOException("JM API envelope is not JSON", error)
        }
        val apiCode = envelope.optInt("code", -1)
        if (apiCode != 200) {
            val message = envelope.optString("errorMsg").takeIf { it.isNotBlank() }
            JmRouteManager.recordApiFailure(host, "JM API code $apiCode")
            throw IOException("JM API rejected request (code=$apiCode)${message?.let { ": $it" }.orEmpty()}")
        }

        return try {
            val data = envelope.opt("data")
            val encrypted = data as? String
                ?: throw IOException("JM API data 不是报告确认的加密字符串")
            val plaintext = decryptPayload(encrypted, timestamp)
            // Confirm the decrypted payload is JSON before passing it to the source parser.
            JSONTokener(plaintext).nextValue()
            JmRouteManager.recordApiSuccess(host)
            response.newBuilder().body(plaintext.toResponseBody(response.body?.contentType())).build()
        } catch (error: Exception) {
            JmRouteManager.recordApiFailure(host, "JM 响应解密/解析失败")
            if (error is IOException) throw error
            throw IOException("JM API response could not be decoded", error)
        }
    }

    private fun decryptPayload(base64: String, timestamp: String): String {
        val key = md5Hex(timestamp + RESPONSE_SALT).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val decrypted = String(cipher.doFinal(Base64.decode(base64.trim(), Base64.DEFAULT)), Charsets.UTF_8)
        val startObject = decrypted.indexOf('{').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val startArray = decrypted.indexOf('[').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val start = minOf(startObject, startArray)
        val end = maxOf(decrypted.lastIndexOf('}'), decrypted.lastIndexOf(']'))
        if (start == Int.MAX_VALUE || end < start) throw IOException("JM 解密载荷缺少 JSON 对象/数组")
        return decrypted.substring(start, end + 1)
    }

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"
        const val ORIGIN = "https://localhost"
        const val REFERER = "https://localhost/"
        const val PACKAGE_NAME = "com.example.app"
        const val APP_VERSION = "2.0.16"
        private const val REQUEST_SALT = "18comicAPPContent"
        private const val RESPONSE_SALT = "185Hcomic3PAPP7R"
    }
}
