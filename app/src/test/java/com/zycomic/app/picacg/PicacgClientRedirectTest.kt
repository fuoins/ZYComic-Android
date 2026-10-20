package com.zycomic.app.picacg

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class PicacgClientRedirectTest {
    @Test
    fun `safe HTTP 301 preserves POST body and signature`() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String?>()
        val client = testClient(
            baseUrl = "http://api.example/api/v2",
            replies = listOf(
                Reply(301, location = "https://api.example/api/v2/login?x=1"),
                Reply(200, body = """{"code":200,"data":{}}"""),
            ),
            requests = requests,
            bodies = bodies,
        )
        val body = JSONObject().put("email", "reader@example.com").put("password", "offline")

        client.request("/login?x=1", method = "POST", body = body, authenticated = false)

        assertEquals(2, requests.size)
        assertEquals(listOf("http", "https"), requests.map { it.url.scheme })
        assertEquals(listOf("POST", "POST"), requests.map { it.method })
        assertEquals(listOf(body.toString(), body.toString()), bodies)
        assertEquals(requests[0].header("signature"), requests[1].header("signature"))
        assertEquals(requests[0].url.encodedPath, requests[1].url.encodedPath)
        assertEquals(requests[0].url.encodedQuery, requests[1].url.encodedQuery)
    }

    @Test
    fun `sign-in diagnostics identify API base and redact request query and body`() = runBlocking {
        val requests = mutableListOf<Request>()
        val diagnostics = mutableListOf<String>()
        val client = testClient(
            baseUrl = "http://api.example/api/v2",
            replies = listOf(
                Reply(301, location = "https://api.example/api/v2/auth/sign-in?marker=private-query"),
                Reply(200, body = """{"code":200,"data":{}}"""),
            ),
            requests = requests,
            bodies = mutableListOf(),
            diagnostics = diagnostics,
        )

        client.request(
            "/auth/sign-in?marker=private-query",
            method = "POST",
            body = JSONObject().put("password", "offline-test-value"),
            authenticated = false,
        )

        assertEquals(
            listOf(
                "Picacg sign-in configured endpoint=api-base",
                "Picacg HTTP 301 redirect; source=api-base; location=same-host-https",
            ),
            diagnostics,
        )
        assertEquals(2, requests.size)
    }

    @Test
    fun `invalid redirect targets and non-301 redirects are not followed`() = runBlocking {
        val invalidReplies = listOf(
            Reply(301, location = "https://other.example/api/v2/item?q=1"),
            Reply(301, location = "http://api.example/api/v2/item?q=1"),
            Reply(301, location = "https://api.example/api/v2/other?q=1"),
            Reply(301, location = "https://api.example/api/v2/item?q=2"),
            Reply(302, location = "https://api.example/api/v2/item?q=1"),
        )

        invalidReplies.forEach { reply ->
            val requests = mutableListOf<Request>()
            val client = testClient(
                baseUrl = "http://api.example/api/v2",
                replies = listOf(reply),
                requests = requests,
                bodies = mutableListOf(),
            )
            assertThrows(IOException::class.java) {
                runBlocking { client.request("/item?q=1", authenticated = false) }
            }
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `second redirect is rejected without following a third request`() = runBlocking {
        val requests = mutableListOf<Request>()
        val diagnostics = mutableListOf<String>()
        val client = testClient(
            baseUrl = "http://api.example/api/v2",
            replies = listOf(
                Reply(301, location = "https://api.example/api/v2/item"),
                Reply(301, location = "https://private.example/item?token=must-not-be-logged"),
            ),
            requests = requests,
            bodies = mutableListOf(),
            diagnostics = diagnostics,
        )

        assertThrows(IOException::class.java) {
            runBlocking { client.request("/item", authenticated = false) }
        }
        assertEquals(2, requests.size)
        assertEquals(listOf("http", "https"), requests.map { it.url.scheme })
        assertEquals(
            listOf(
                "Picacg HTTP 301 redirect; source=api-base; location=same-host-https",
                "Picacg HTTP 301 redirect; source=api-base; location=source-not-http",
            ),
            diagnostics,
        )
    }

    private fun testClient(
        baseUrl: String,
        replies: List<Reply>,
        requests: MutableList<Request>,
        bodies: MutableList<String?>,
        diagnostics: MutableList<String> = mutableListOf(),
    ): PicacgClient {
        val remaining = ArrayDeque(replies)
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    bodies += request.body?.let { requestBody -> Buffer().also(requestBody::writeTo).readUtf8() }
                    val reply = remaining.removeFirst()
                    val response = Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(reply.code)
                        .message(if (reply.code == 200) "OK" else "Redirect")
                        .body(reply.body.toResponseBody("application/json".toMediaType()))
                    if (reply.location != null) response.header("Location", reply.location)
                    response.build()
                },
            )
            .build()
        return PicacgClient(
            baseUrl = { baseUrl },
            token = { null },
            http = http,
            epochSeconds = { 1_700_000_000L },
            nonce = { "0123456789abcdef0123456789abcdef" },
            appUuid = "redirect-offline-test",
            diagnosticLog = diagnostics::add,
        )
    }

    private data class Reply(val code: Int, val body: String = "", val location: String? = null)
}
