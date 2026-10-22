package com.zycomic.app.picacg

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class MaccmsRecommendationTransportTest {
    @Test
    fun `sends an anonymous GET to the requested ID and parses distinct IDs`() = withServer { server ->
        val requestMethod = AtomicReference<String>()
        val requestPath = AtomicReference<String>()
        val cookie = AtomicReference<String?>()
        val authorization = AtomicReference<String?>()
        val seenHeaders = AtomicReference<Map<String, List<String>>>()
        server.createContext("/") { exchange ->
            requestMethod.set(exchange.requestMethod)
            requestPath.set(exchange.requestURI.toString())
            cookie.set(exchange.requestHeaders.getFirst("Cookie"))
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            seenHeaders.set(exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() })
            val body = "[\"aaaaaaaaaaaaaaaaaaaaaaaa\",\"bbbbbbbbbbbbbbbbbbbbbbbb\",\"bbbbbbbbbbbbbbbbbbbbbbbb\",\"not-an-id\"]"
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()

        val actual = runBlocking { MaccmsRecommendationClient(server.baseUrl()).recommendationIds("111111111111111111111111") }

        assertEquals(listOf("aaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbb"), actual)
        assertEquals("GET", requestMethod.get())
        assertEquals("/picacomic/rec/111111111111111111111111?limit=10", requestPath.get())
        assertEquals(null, cookie.get())
        assertEquals(null, authorization.get())
        val headers = seenHeaders.get().keys
        assertFalse(headers.any { it.contains("pica") || it.contains("token") || it.contains("sign") })
    }

    @Test
    fun `does not follow redirects`() = withServer { server ->
        val redirected = AtomicReference(false)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "/elsewhere")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/elsewhere") { exchange ->
            redirected.set(true)
            val body = "[]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val failure = assertThrows(MaccmsRecommendationHttpException::class.java) {
            runBlocking { MaccmsRecommendationClient(server.baseUrl()).recommendationIds("111111111111111111111111") }
        }
        assertEquals(302, failure.statusCode)
        assertFalse(redirected.get(), "The client must not follow a server redirect")
    }

    @Test
    fun `rejects malformed requested IDs before making a request`() = withServer { server ->
        val requests = AtomicReference(0)
        server.createContext("/") { exchange ->
            requests.set(requests.get() + 1)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { MaccmsRecommendationClient(server.baseUrl()).recommendationIds("not-a-comic-id") }
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun `accepts only IDs matching the documented shape and excludes the current ID case-insensitively`() = withServer { server ->
        server.createContext("/") { exchange ->
            val body = "[\"AAAAAAAAAAAAAAAAAAAAAAAA\",\"bbbbbbbbbbbbbbbbbbbbbbbb\",42,null,\"bad\"]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val actual = runBlocking { MaccmsRecommendationClient(server.baseUrl()).recommendationIds("aaaaaaaaaaaaaaaaaaaaaaaa") }
        assertEquals(listOf("bbbbbbbbbbbbbbbbbbbbbbbb"), actual)
        assertTrue(actual.all { it.matches(Regex("[0-9a-fA-F]{24}")) })
    }

    private fun withServer(test: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            test(server)
        } finally {
            server.stop(0)
        }
    }

    private fun HttpServer.baseUrl() = "http://127.0.0.1:${address.port}/".toHttpUrl()
}
