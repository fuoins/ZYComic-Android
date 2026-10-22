package com.zycomic.app.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class JmLoginAttemptInterceptorTest {
    @Test
    fun `late login HTTP 401 cannot clear a newer login attempt`() {
        val oldAttempt = JmAuthStore.beginLoginAttempt()
        val currentAttempt = JmAuthStore.beginLoginAttempt()

        val response = unauthorizedClient().newCall(loginRequest(oldAttempt)).execute()
        response.close()

        assertTrue(JmAuthStore.isLoginCurrent(currentAttempt))
        assertFalse(JmAuthStore.isLoginCurrent(oldAttempt))
    }

    @Test
    fun `raw HTTP 401 does not clear the current login attempt`() {
        val attempt = JmAuthStore.beginLoginAttempt()

        unauthorizedClient().newCall(loginRequest(attempt)).execute().close()

        assertTrue(JmAuthStore.isLoginCurrent(attempt))
    }

    @Test
    fun `explicit JM business expiry code may clear the current login attempt`() {
        val attempt = JmAuthStore.beginLoginAttempt()
        assertThrows(IOException::class.java) {
            businessUnauthorizedClient().newCall(loginRequest(attempt)).execute().close()
        }
        assertFalse(JmAuthStore.isLoginCurrent(attempt))
    }

    @Test
    fun `credential refresh HTTP 401 preserves the active session attempt`() {
        val attempt = JmAuthStore.beginLoginAttempt(preserveSessionOnUnauthorized = true)

        unauthorizedClient().newCall(loginRequest(attempt)).execute().close()

        assertTrue(JmAuthStore.isLoginCurrent(attempt))
    }

    private fun businessUnauthorizedClient(): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .addInterceptor(JmApiInterceptor(recordRouteHealth = false, allowAuthentication = false))
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"code\":401,\"errorMsg\":\"expired\"}".toResponseBody())
                .build()
        }
        .build()

    private fun unauthorizedClient(): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .addInterceptor(JmApiInterceptor(recordRouteHealth = false, allowAuthentication = false))
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("unauthorized".toResponseBody())
                .build()
        }
        .build()

    private fun loginRequest(attempt: JmLoginAttempt): Request = Request.Builder()
        .url("https://jm.offline.invalid/login")
        .tag(JmLoginAttempt::class.java, attempt)
        .build()
}
