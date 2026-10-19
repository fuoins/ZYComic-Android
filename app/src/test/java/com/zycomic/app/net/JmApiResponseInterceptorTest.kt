package com.zycomic.app.net

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class JmApiResponseInterceptorTest {
    @Test
    fun `favorite and history failure cannot poison subsequent home via shared or snapshot jar`() {
        for (path in listOf("/favorite", "/watch_list")) {
            for (status in listOf(302, 401, 403, 429, 500)) {
                for (cookie in listOf("AVS=; Max-Age=0; Path=/", "AVS=rejected; Path=/")) {
                    for (snapshot in listOf(false, true)) {
                        sequence(path, status, cookie, snapshot)
                    }
                }
            }
        }
    }

    private fun sequence(path: String, status: Int, failedCookie: String, snapshot: Boolean) {
        val calls = AtomicInteger()
        val jar = MemoryJar()
        val logs = mutableListOf<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.incrementAndGet()
                val currentPath = request.url.encodedPath
                val hasAvs = request.headers["Cookie"].orEmpty().contains("AVS=accepted")
                val code = when (currentPath) {
                    "/confirm" -> 200
                    path -> status
                    else -> if (hasAvs) 200 else 403
                }
                val response = MockResponse.Builder().code(code).body("offline-response")
                when (currentPath) {
                    "/confirm" -> response.addHeader("Set-Cookie", "AVS=accepted; Path=/")
                    path -> {
                        response.addHeader("Set-Cookie", failedCookie)
                        response.addHeader("Set-Cookie", "other=preserved; Path=/")
                    }
                }
                return response.build()
            }
        }
        server.start()
        try {
            val base = "http://api.local:${server.port}"
            val client = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
                })
                .cookieJar(jar)
                .followRedirects(false)
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor(JmApiResponseInterceptor { _, message -> logs += message })
                .build()
            fun get(target: String) = client.newCall(Request.Builder().url(base + target).build()).execute().use { it.code }
            assertEquals(200, get("/confirm"))
            assertEquals(200, get("/"))
            assertEquals(200, get("/categories"))
            val url = Request.Builder().url(base + path).build().url
            // Same forwarding behavior as JmRequestSession, without needing any account credentials.
            val sessionJar = if (snapshot) {
                object : CookieJar {
                    private val captured = jar.loadForRequest(url).toList()
                    override fun loadForRequest(url: HttpUrl) = captured.filter { it.matches(url) }
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = jar.saveFromResponse(url, cookies)
                }
            } else {
                jar
            }
            client.newBuilder().cookieJar(sessionJar).build()
                .newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(status, it.code)
                    assertEquals("offline-response", it.body!!.string())
                    assertFalse(it.headers.values("Set-Cookie").any(JmApiDiagnostics::isAvsCookie))
                    assertTrue(it.headers.values("Set-Cookie").contains("other=preserved; Path=/"))
                }
            assertEquals("accepted", jar.loadForRequest(url).single { it.name == "AVS" }.value)
            assertEquals(200, get("/"))
            assertEquals(5, calls.get(), "HTTP failure is never retried")
            assertTrue(logs.any { "avsUpdateSuppressed=true" in it && "status=$status" in it })
            assertTrue(logs.last().contains("avsPresent=true"), "Observe Cookie after BridgeInterceptor attaches it")
        } finally {
            server.close()
        }
    }

    @Test
    fun `successful AVS replacement and deletion remain effective and failed writes are not replayed`() {
        val calls = AtomicInteger()
        val jar = MemoryJar()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls.incrementAndGet()
                val path = request.url.encodedPath
                val code = if (path == "/favorite") 403 else 200
                val cookie = when (path) {
                    "/delete" -> "AVS=; Max-Age=0; Path=/"
                    "/favorite" -> "AVS=poison; Path=/"
                    else -> "AVS=${path.removePrefix("/")}; Path=/"
                }
                return MockResponse.Builder()
                    .code(code)
                    .addHeader("Set-Cookie", cookie)
                    .body("unchanged")
                    .build()
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.port}"
            val client = OkHttpClient.Builder().cookieJar(jar).retryOnConnectionFailure(false)
                .addNetworkInterceptor(JmApiResponseInterceptor { _, _ -> error("diagnostic sink unavailable") }).build()
            val url = Request.Builder().url(base).build().url
            for (path in listOf("/initial", "/replacement")) {
                client.newCall(Request.Builder().url(base + path).build()).execute().close()
                assertEquals(path.removePrefix("/"), jar.loadForRequest(url).single().value)
            }
            client.newCall(Request.Builder().url("$base/favorite").post("offline-form".toRequestBody()).build()).execute().use {
                assertEquals(403, it.code)
                assertEquals("unchanged", it.body!!.string())
            }
            assertEquals("replacement", jar.loadForRequest(url).single().value)
            client.newCall(Request.Builder().url("$base/delete").build()).execute().close()
            assertTrue(jar.loadForRequest(url).isEmpty())
            assertEquals(4, calls.get(), "POST is submitted exactly once even if diagnostics fail")
        } finally {
            server.close()
        }
    }

    private class MemoryJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
            }
        }
    }
}
