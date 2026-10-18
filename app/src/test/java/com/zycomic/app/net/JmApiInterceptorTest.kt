package com.zycomic.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class JmApiInterceptorTest {
    @Test
    fun `successful response preserves Chinese errorMsg in safe response header`() {
        val response = offlineClient(errorMsg = "服务器提示：稍后重试").newCall(request()).execute()
        response.use {
            assertEquals(200, it.code)
            assertEquals("服务器提示：稍后重试", it.header("X-JM-Error-Msg"))
            assertEquals("{\"items\":[]}", it.body!!.string())
        }
    }

    @Test
    fun `successful response with null errorMsg removes stale response header`() {
        val response = offlineClient(errorMsg = "null", staleHeader = true).newCall(request()).execute()
        response.use {
            assertEquals(200, it.code)
            assertNull(it.header("X-JM-Error-Msg"))
            assertEquals("{\"items\":[]}", it.body!!.string())
        }
    }

    private fun offlineClient(errorMsg: String, staleHeader: Boolean = false): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .addInterceptor(
            JmApiInterceptor(
                recordRouteHealth = false,
                allowAuthentication = false,
                base64Decoder = { Base64.getDecoder().decode(it) },
            ),
        )
        .addInterceptor { chain ->
            val timestamp = chain.request().header("tokenparam")!!.substringBefore(',')
            val encryptedPayload = encrypt("{\"items\":[]}", timestamp)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .apply { if (staleHeader) header("X-JM-Error-Msg", "stale") }
                .body(
                    "{\"code\":200,\"errorMsg\":$errorMsg,\"data\":\"$encryptedPayload\"}"
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }
        .build()

    private fun request() = Request.Builder().url("https://jm.offline.invalid/favorite").build()

    private fun encrypt(payload: String, timestamp: String): String {
        val key = md5Hex(timestamp + RESPONSE_SALT).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val RESPONSE_SALT = "185Hcomic3PAPP7R"
    }
}
