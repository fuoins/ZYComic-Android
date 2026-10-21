package com.zycomic.app.picacg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A Picacg-only API client. It has its own connection pool and never borrows platform cookies. */
class PicacgClient(
    private val baseUrl: () -> String,
    private val token: () -> String? = PicacgAuthStore::token,
    private val onUnauthorized: () -> Unit = {},
    http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val nonce: () -> String = ::newNonce,
    private val appUuid: String = UUID.randomUUID().toString().replace("-", ""),
    private val sessionProvider: () -> PicacgAuthStore.Session = PicacgAuthStore::session,
    private val clearSessionIfRevision: (Long) -> Boolean = PicacgAuthStore::clearIfRevision,
    private val diagnosticLog: (String) -> Unit = { message -> logcat { message } },
) {
    private val noRetryHttp =
        http.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    data class Envelope(val code: Int, val message: String, val error: String, val data: JSONObject)

    suspend fun request(
        pathAndQuery: String,
        method: String = "GET",
        body: JSONObject? = null,
        authenticated: Boolean = true,
        expectedSessionRevision: Long? = null,
    ): Envelope =
        withContext(Dispatchers.IO) {
            val capturedSession = if (authenticated) sessionProvider() else null
            if (expectedSessionRevision != null && capturedSession?.revision != expectedSessionRevision) {
                throw IOException("Picacg session changed before the request")
            }
            val capturedToken = if (authenticated) capturedSession?.token ?: token() else null
            if (authenticated && capturedToken.isNullOrBlank()) throw IOException("Picacg login required")
            require(pathAndQuery.startsWith('/')) { "Picacg path must start with /" }
            val base = baseUrl().trim().trimEnd('/')
            require(base.startsWith("https://") || base.startsWith("http://")) { "Picacg API route is not configured" }
            if (pathAndQuery.substringBefore('?') == "/auth/sign-in") {
                diagnosticLog(PicacgDiagnosticPolicy.signInBase(base))
            }
            val time = epochSeconds()
            val requestNonce = nonce()
            val requestUrl = (base + pathAndQuery).toHttpUrl()
            val finalPathAndQuery = requestUrl.encodedPath + requestUrl.encodedQuery?.let { "?$it" }.orEmpty()
            val signature = PicacgSigner.sign(finalPathAndQuery, method, time, requestNonce)
            val builder = Request.Builder()
                .url(requestUrl)
                .header("accept", "application/vnd.picacomic.com.v1+json")
                .header("time", time.toString())
                .header("nonce", requestNonce)
                .header("signature", signature)
                .header("api-key", PicacgSigner.API_KEY)
                .header("app-channel", "1")
                .header("app-version", "2.3.1.20241111")
                .header("app-uuid", appUuid)
                .header("app-platform", "android")
                .header("app-build-version", "20250144")
                .header("image-quality", "medium")
                .header("User-Agent", "okhttp/3.8.1")
            if (authenticated) builder.header("authorization", capturedToken.orEmpty())
            if (method.equals("POST", true)) {
                builder.post((body?.toString() ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
                builder.header("Content-Type", "application/json; charset=UTF-8")
            } else {
                require(body == null) { "Only POST Picacg requests may have a body" }
                builder.get()
            }
            val response = executeWithSafeRedirect(builder.build())
            response.use { response ->
                if (expectedSessionRevision != null && sessionProvider().revision != expectedSessionRevision) {
                    throw IOException("Picacg session changed while the request was in progress")
                }
                if (capturedSession != null && capturedSession.revision != sessionProvider().revision) {
                    throw IOException("Picacg session changed while the request was in progress")
                }
                parseResponse(response, capturedSession?.revision, allowMissingData = method.equals("POST", ignoreCase = true))
            }
        }

    private fun parseResponse(response: Response, expectedRevision: Long?, allowMissingData: Boolean): Envelope {
        val text = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        val code = json?.optInt("code", response.code) ?: response.code
        val message = json?.optString("message").orEmpty()
        val error = json?.optString("error").orEmpty()
        if (response.code == 401 || error == "1005") {
            if (expectedRevision != null) clearSessionIfRevision(expectedRevision) else onUnauthorized()
            throw PicacgResponseException(response.code, code, error, message.ifBlank { "Picacg authorization expired" })
        }
        if (!response.isSuccessful || (code != 200 && code != 0)) {
            throw PicacgResponseException(response.code, code, error, message.ifBlank { "HTTP error" })
        }
        val data = json?.optJSONObject("data") ?: if (allowMissingData) JSONObject() else throw IOException("Picacg response has no data; sign in is required")
        return Envelope(code, message, error, data)
    }

    private fun executeWithSafeRedirect(request: Request): Response {
        val firstResponse = noRetryHttp.newCall(request).execute()
        if (firstResponse.code != 301) {
            if (firstResponse.code in 300..399 && firstResponse.code != 304) {
                logRedirect(firstResponse)
                firstResponse.close()
                throw IOException("Picacg redirect rejected (${firstResponse.code})")
            }
            return firstResponse
        }

        val redirectUrl = firstResponse.use { response ->
            logRedirect(response)
            PicacgRedirectPolicy.targetFor301(response.request.url, response.header("Location"))
        }
        val redirectedRequest = request.newBuilder().url(redirectUrl).build()
        val secondResponse = noRetryHttp.newCall(redirectedRequest).execute()
        if (secondResponse.code in 300..399 && secondResponse.code != 304) {
            logRedirect(secondResponse)
            secondResponse.close()
            throw IOException("Additional Picacg redirect rejected (${secondResponse.code})")
        }
        return secondResponse
    }

    private fun logRedirect(response: Response) {
        diagnosticLog(
            PicacgDiagnosticPolicy.redirectResponse(response.request.url, response.code, response.header("Location")),
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val random = SecureRandom()
        private const val NONCE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private fun newNonce(): String = buildString(32) {
            repeat(32) { append(NONCE_CHARS[random.nextInt(NONCE_CHARS.length)]) }
        }
    }
}
