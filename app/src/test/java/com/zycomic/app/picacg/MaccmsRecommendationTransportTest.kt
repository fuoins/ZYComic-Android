package com.zycomic.app.picacg

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaccmsRecommendationTransportTest {
    @Test
    fun `sends an anonymous GET to the requested ID and parses distinct IDs`() = withServer { server ->
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("[\"aaaaaaaaaaaaaaaaaaaaaaaa\",\"bbbbbbbbbbbbbbbbbbbbbbbb\",\"bbbbbbbbbbbbbbbbbbbbbbbb\",\"not-an-id\"]")
                .build(),
        )

        val actual = runBlocking { MaccmsRecommendationClient(server.baseUrl()).recommendationIds("111111111111111111111111") }
        val request = server.takeRequest()

        assertEquals(listOf("aaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbb"), actual)
        assertEquals("GET", request.method)
        assertEquals("/picacomic/rec/111111111111111111111111?limit=10", request.target)
        assertEquals(null, request.headers["Cookie"])
        assertEquals(null, request.headers["Authorization"])
        val headers = request.headers.names().map { it.lowercase() }
        assertFalse(headers.any { it.contains("pica") || it.contains("token") || it.contains("sign") })
    }

    @Test
    fun `does not follow redirects`() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build())
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        val failure = assertThrows(MaccmsRecommendationHttpException::class.java) {
            runBlocking { MaccmsRecommendationClient(server.baseUrl()).recommendationIds("111111111111111111111111") }
        }
        assertEquals(302, failure.statusCode)
        assertEquals(1, server.requestCount, "The client must not follow a server redirect")
    }

    @Test
    fun `rejects malformed requested IDs before making a request`() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(200).build())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { MaccmsRecommendationClient(server.baseUrl()).recommendationIds("not-a-comic-id") }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `accepts only IDs matching the documented shape and excludes the current ID case-insensitively`() = withServer { server ->
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("[\"AAAAAAAAAAAAAAAAAAAAAAAA\",\"bbbbbbbbbbbbbbbbbbbbbbbb\",42,null,\"bad\"]")
                .build(),
        )

        val actual = runBlocking { MaccmsRecommendationClient(server.baseUrl()).recommendationIds("aaaaaaaaaaaaaaaaaaaaaaaa") }
        assertEquals(listOf("bbbbbbbbbbbbbbbbbbbbbbbb"), actual)
        assertTrue(actual.all { it.matches(Regex("[0-9a-fA-F]{24}")) })
    }

    private fun withServer(test: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            test(server)
        } finally {
            server.close()
        }
    }

    private fun MockWebServer.baseUrl() = url("/")
}
