package com.zycomic.app.net

import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmApiDiagnosticsTest {
    @Test
    fun `message redacts request response and endpoint values while reporting presence metadata`() {
        val bodyBuffer = Buffer().writeUtf8("BODY_SECRET_PAYLOAD")
        val request = Request.Builder()
            .url("https://api.example.test/unknown/private-path?query=QUERY_SECRET&auth=QUERY_AUTH_SECRET")
            .header("Authorization", "Bearer AUTH_SECRET")
            .header("Cookie", "AVS=AVS_COOKIE_SECRET; session=COOKIE_SECRET")
            .header("token", "TOKEN_SECRET")
            .header("tokenparam", "TOKENPARAM_SECRET")
            .header("X-Private-Request", "REQUEST_HEADER_SECRET")
            .build()
        val response = response(
            request = request,
            code = 403,
            headers = listOf(
                "Content-Type" to "application/x-private-content",
                "Server" to "cloudflare",
                "CF-Ray" to "CF_RAY_SECRET",
                "Set-Cookie" to "AVS=AVS_UPDATE_SECRET; Path=/",
                "X-Request-ID" to "REQUEST_ID_SECRET",
                "X-Private-Response" to "RESPONSE_HEADER_SECRET",
            ),
            body = bodyBuffer,
        )
        val selected = DomainIp("selected.endpoint.secret", "203.0.113.42")
        val actual = DomainIp("actual.endpoint.secret", "198.51.100.73")

        val message = JmApiDiagnostics.message(request, response, selected, actual)

        listOf(
            "QUERY_SECRET",
            "QUERY_AUTH_SECRET",
            "AUTH_SECRET",
            "AVS_COOKIE_SECRET",
            "COOKIE_SECRET",
            "TOKEN_SECRET",
            "TOKENPARAM_SECRET",
            "REQUEST_HEADER_SECRET",
            "CF_RAY_SECRET",
            "AVS_UPDATE_SECRET",
            "REQUEST_ID_SECRET",
            "RESPONSE_HEADER_SECRET",
            "BODY_SECRET_PAYLOAD",
            selected.domain,
            selected.ip,
            actual.domain,
            actual.ip,
            "/unknown/private-path",
        ).forEach { secret -> assertFalse(message.contains(secret), "diagnostics leaked $secret") }

        assertTrue(message.contains("path=/:other"))
        assertTrue(message.contains("contentType=other"))
        assertTrue(message.contains("cloudflareHeaderPresent=true"))
        assertTrue(message.contains("challengeMarked=false"))
        assertTrue(message.contains("wafHeaderPresent=false"))
        assertTrue(message.contains("requestIdPresent=true"))
        assertTrue(message.contains("authorizationPresent=true"))
        assertTrue(message.contains("cookiePresent=true"))
        assertTrue(message.contains("avsPresent=true"))
        assertTrue(message.contains("tokenPresent=true"))
        assertTrue(message.contains("tokenparamPresent=true"))
        assertTrue(message.contains("avsUpdatePresent=true"))
        assertTrue(message.contains("avsUpdateSuppressed=true"))
        assertFalse(message.contains("rootCause"))
        assertFalse(message.contains("cause="))
        assertEquals("BODY_SECRET_PAYLOAD".length.toLong(), bodyBuffer.size)
    }

    @Test
    fun `message keeps allowlisted paths but redacts unknown paths and useredit ids`() {
        val knownRequest = request("https://api.example.test/login?next=QUERY_SECRET")
        val knownMessage = JmApiDiagnostics.message(knownRequest, response(knownRequest), null, null)
        assertTrue(knownMessage.contains("path=/login"))
        assertFalse(knownMessage.contains("QUERY_SECRET"))

        val userEditRequest = request("https://api.example.test/useredit/987654321?token=QUERY_SECRET")
        val userEditMessage = JmApiDiagnostics.message(userEditRequest, response(userEditRequest), null, null)
        assertTrue(userEditMessage.contains("path=/useredit/:id"))
        assertFalse(userEditMessage.contains("987654321"))
        assertFalse(userEditMessage.contains("QUERY_SECRET"))

        val unknownRequest = request("https://api.example.test/private/987654321")
        val unknownMessage = JmApiDiagnostics.message(unknownRequest, response(unknownRequest), null, null)
        assertTrue(unknownMessage.contains("path=/:other"))
        assertFalse(unknownMessage.contains("private"))
        assertFalse(unknownMessage.contains("987654321"))
    }

    @Test
    fun `unrecognized content types are reported only as other`() {
        listOf(
            "application/x-private-content",
            "text/csv",
            "image/png; boundary=CONTENT_TYPE_SECRET",
            "not a media type",
        ).forEach { contentType ->
            val request = request("https://api.example.test/album")
            val message = JmApiDiagnostics.message(
                request,
                response(request, headers = listOf("Content-Type" to contentType)),
                null,
                null,
            )
            assertTrue(message.contains("contentType=other"), "unexpected classification for $contentType")
            assertFalse(message.contains(contentType.substringBefore(';').trim()))
        }
    }

    @Test
    fun `missing credentials cookies request ids endpoints and cdn headers are reported absent`() {
        val request = request("https://api.example.test/favorite")
        val message = JmApiDiagnostics.message(request, response(request), null, null)

        assertTrue(message.contains("selected=unknown"))
        assertTrue(message.contains("actual=unknown"))
        assertTrue(message.contains("contentType=other"))
        assertTrue(message.contains("cloudflareHeaderPresent=false"))
        assertTrue(message.contains("challengeMarked=false"))
        assertTrue(message.contains("wafHeaderPresent=false"))
        assertTrue(message.contains("requestIdPresent=false"))
        assertTrue(message.contains("authorizationPresent=false"))
        assertTrue(message.contains("cookiePresent=false"))
        assertTrue(message.contains("avsPresent=false"))
        assertTrue(message.contains("tokenPresent=false"))
        assertTrue(message.contains("tokenparamPresent=false"))
        assertTrue(message.contains("avsUpdatePresent=false"))
        assertTrue(message.contains("avsUpdateSuppressed=false"))
    }

    private fun request(url: String): Request = Request.Builder().url(url).build()

    private fun response(
        request: Request,
        code: Int = 200,
        headers: List<Pair<String, String>> = emptyList(),
        body: Buffer = Buffer(),
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .body(
            object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = body.size
                override fun source(): BufferedSource = body
            },
        )
        .build()
}
