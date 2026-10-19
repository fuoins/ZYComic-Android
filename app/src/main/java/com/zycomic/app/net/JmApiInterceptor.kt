package com.zycomic.app.net

import android.util.Base64
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** JM API 专用协议；不调用 ManwaInterceptor / Crypto。 */
class JmApiInterceptor internal constructor(
    private val recordRouteHealth: Boolean,
    private val allowAuthentication: Boolean,
    private val base64Decoder: (String) -> ByteArray,
) : Interceptor {

    constructor(recordRouteHealth: Boolean = true, allowAuthentication: Boolean = true) : this(
        recordRouteHealth,
        allowAuthentication,
        { Base64.decode(it, Base64.DEFAULT) },
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val session = original.tag(JmRequestSession::class.java)
        session?.let(JmRequestSession::requireCurrent)
        val path = original.url.encodedPath
        val host = original.url.host
        val isImage = JmAuthPolicy.isImage(path)
        val isUnsigned = path == "/confirm"
        val builder = original.newBuilder()
            .removeHeader("Authorization")
            .apply { if (isImage) removeHeader("Cookie") }
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .header("X-Requested-With", PACKAGE_NAME)
            .header("Sec-Fetch-Storage-Access", "active")
        if (allowAuthentication && (session != null || recordRouteHealth) && JmAuthPolicy.mayAttachJwt(
                path,
                original.method,
                host,
                JmRouteManager.lineHosts.mapNotNull { runCatching { java.net.URI(it).host }.getOrNull() },
            )
        ) {
            (session?.capturedToken ?: JmAuthStore.token())?.let { builder.header("Authorization", "Bearer $it") }
        }
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        if (!isImage && !isUnsigned) {
            builder.header("token", md5Hex(timestamp + REQUEST_SALT))
                .header("tokenparam", "$timestamp,$APP_VERSION")
        }
        val request = builder.build()
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            if (!chain.call().isCanceled() && recordRouteHealth && !isImage && !isUnsigned) {
                JmRouteManager.recordApiFailure(host, error.javaClass.simpleName)
            }
            throw error
        }
        if (isImage || isUnsigned || response.body == null) return response

        val raw = response.body!!.string()
        if (!response.isSuccessful) {
            if (response.code == 401) {
                if (session == null) JmAuthStore.clear() else JmRequestSession.clearIfCurrent(session)
            }
            if (recordRouteHealth && (response.code == 408 || response.code == 429 || response.code >= 500)) {
                JmRouteManager.recordApiFailure(host, "JM HTTP ${response.code}")
            }
            return response.newBuilder().body(raw.toResponseBody(response.body?.contentType())).build()
        }

        val envelope = try {
            JSONObject(raw)
        } catch (error: Exception) {
            if (recordRouteHealth) JmRouteManager.recordApiFailure(host, "JM 响应解密/解析失败")
            throw IOException("JM API envelope is not JSON", error)
        }
        val apiCode = envelope.optInt("code", -1)
        val apiMessage = (if (envelope.isNull("errorMsg")) "" else envelope.optString("errorMsg"))
            .filter { it >= ' ' && it != '\u007f' }
            .take(256)
            .takeIf { it.isNotBlank() }
        if (apiCode != 200) {
            if (apiCode == 401) {
                if (session == null) JmAuthStore.clear() else JmRequestSession.clearIfCurrent(session)
            }
            if (recordRouteHealth && apiCode >= 500) JmRouteManager.recordApiFailure(host, "JM API code $apiCode")
            throw IOException("JM API rejected request (code=$apiCode)${apiMessage?.let { ": $it" }.orEmpty()}")
        }

        return try {
            val data = envelope.opt("data")
            val encrypted = data as? String
                ?: throw IOException("JM API data 不是报告确认的加密字符串")
            val plaintext = decryptPayload(encrypted, timestamp)
            if (!JmSpeedProbeProtocol.isValidDecryptedJson(plaintext)) {
                throw IOException("JM 解密载荷必须是有效的 JSON 对象或数组")
            }
            if (recordRouteHealth) JmRouteManager.recordApiSuccess(host)
            response.newBuilder()
                .headers(
                    response.headers.newBuilder().removeAll("X-JM-Error-Msg").apply {
                        // JM messages can contain Chinese; keep control characters stripped above.
                        apiMessage?.let { addUnsafeNonAscii("X-JM-Error-Msg", it) }
                    }.build(),
                )
                .body(plaintext.toResponseBody(response.body?.contentType()))
                .build()
        } catch (error: Exception) {
            if (recordRouteHealth) JmRouteManager.recordApiFailure(host, "JM 响应解密/解析失败")
            if (error is IOException) throw error
            throw IOException("JM API response could not be decoded", error)
        }
    }

    private fun decryptPayload(base64: String, timestamp: String): String {
        val key = md5Hex(timestamp + RESPONSE_SALT).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val decrypted = String(cipher.doFinal(base64Decoder(base64.trim())), Charsets.UTF_8)
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
