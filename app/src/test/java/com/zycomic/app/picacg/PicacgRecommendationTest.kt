package com.zycomic.app.picacg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class PicacgRecommendationTest {
    private val selfId = "aaaaaaaaaaaaaaaaaaaaaaaa"

    @Test
    fun `request is fixed GET path query and carries no credentials`() = runBlocking {
        MockServer(listOf(MockResponse("[]"))).use { server ->
            val client = MaccmsRecommendationClient(server.baseUrl)
            assertEquals(emptyList<String>(), client.recommendationIds(selfId))
            val request = server.requests.single()
            assertTrue(request.startsWith("GET /picacomic/rec/$selfId?limit=10 HTTP/1.1"))
            val forbiddenHeaders = listOf("authorization:", "cookie:", "signature:", "api-key:", "app-uuid:", "nonce:")
            assertFalse(
                request.lines().any {
                    forbiddenHeaders.any { header -> it.startsWith(header, true) }
                },
            )
        }
    }

    @Test
    fun `parses raw and wrapped arrays filtering invalid duplicates self and limiting to ten`() = runBlocking {
        val values = listOf(
            "bbbbbbbbbbbbbbbbbbbbbbbb",
            "bad",
            "",
            "BBBBBBBBBBBBBBBBBBBBBBBB",
            selfId,
            17,
            null,
            *List(12) { index -> (index + 1).toString(16).padStart(24, 'c') }.toTypedArray(),
        )
        val raw = values.joinToString(prefix = "[", postfix = "]") { value ->
            when (value) {
                null -> "null"
                is Number -> value.toString()
                else -> "\"$value\""
            }
        }
        MockServer(
            listOf(
                MockResponse(raw),
                MockResponse("{\"data\":$raw}"),
                MockResponse("{\"code\":1,\"count\":10,\"recommendations\":$raw}"),
                MockResponse("{\"code\":1,\"data\":$raw}"),
            ),
        ).use { server ->
            val client = MaccmsRecommendationClient(server.baseUrl)
            val result = client.recommendationIds(selfId)
            assertEquals(10, result.size)
            assertEquals("bbbbbbbbbbbbbbbbbbbbbbbb", result.first())
            assertEquals(10, result.distinctBy(String::lowercase).size)
            assertFalse(result.any { it.equals(selfId, true) })
            assertEquals(result, client.recommendationIds(selfId))
            assertEquals(result, client.recommendationIds(selfId))
            assertEquals(result, client.recommendationIds(selfId))
        }
    }

    @Test
    fun `documented recommendations envelope loads cards in order without reading data`() = runBlocking {
        val ids = listOf("bbbbbbbbbbbbbbbbbbbbbbbb", "cccccccccccccccccccccccc")
        val body = """{"code":1,"count":2,"recommendations":["${ids[0]}","${ids[1]}"],"data":[]}"""
        MockServer(listOf(MockResponse(body))).use { server ->
            val client = MaccmsRecommendationClient(server.baseUrl)
            val parsed = client.recommendationIds(selfId)
            assertEquals(ids, parsed)
            assertEquals(ids, loadPicacgRecommendations(parsed) { it })
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `envelopes reject business errors missing success codes and invalid arrays`() = runBlocking {
        val bodies = listOf(
            """{"code":0,"recommendations":[],"data":[]}""",
            """{"code":"0","recommendations":[]}""",
            """{"code":"1","recommendations":[]}""",
            """{"code":null,"recommendations":[]}""",
            """{"code":2,"recommendations":[]}""",
            """{"code":1.5,"recommendations":[]}""",
            """{"code":true,"recommendations":[]}""",
            """{"recommendations":[]}""",
            """{"code":1}""",
            """{"code":1,"recommendations":{},"data":[]}""",
            """{"code":1,"data":"not-an-array"}""",
        )
        MockServer(bodies.map(::MockResponse)).use { server ->
            val client = MaccmsRecommendationClient(server.baseUrl)
            repeat(bodies.size) {
                assertThrows(IOException::class.java) { runBlocking { client.recommendationIds(selfId) } }
            }
        }
    }

    @Test
    fun `empty malformed unsupported and error JSON are failures not empty recommendations`() = runBlocking {
        val responses = listOf("", "{broken", "{\"data\":{}}", "false", "{\"code\":404,\"message\":\"Not Found\"}").map(::MockResponse)
        MockServer(responses).use { server ->
            val client = MaccmsRecommendationClient(server.baseUrl)
            repeat(responses.size) {
                assertThrows(IOException::class.java) { runBlocking { client.recommendationIds(selfId) } }
            }
        }
    }

    @Test
    fun `HTTP 404 preserves upstream failure and never starts metadata lookups`() = runBlocking {
        MockServer(listOf(MockResponse("{\"code\":404,\"message\":\"Not Found\"}", code = 404))).use { server ->
            val client = MaccmsRecommendationClient(server.baseUrl)
            val failure = assertThrows(MaccmsRecommendationHttpException::class.java) {
                runBlocking {
                    val ids = client.recommendationIds(selfId)
                    loadPicacgRecommendations<String>(ids) { error("HTTP failure must not start metadata lookups") }
                }
            }
            assertEquals(404, failure.statusCode)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `valid empty arrays remain successful empty recommendations`() = runBlocking {
        MockServer(
            listOf(
                MockResponse("[]"),
                MockResponse("{\"data\":[]}"),
                MockResponse("{\"code\":1,\"count\":0,\"recommendations\":[]}"),
                MockResponse("{\"code\":1,\"data\":[]}"),
            ),
        ).use { server ->
            val client = MaccmsRecommendationClient(server.baseUrl)
            repeat(4) { assertEquals(emptyList<String>(), client.recommendationIds(selfId)) }
        }
    }

    @Test
    fun `redirect is not followed and invalid comic IDs are rejected`(): Unit = runBlocking {
        MockServer(listOf(MockResponse("", code = 302, location = "https://elsewhere.example/not-followed"))).use { server ->
            val client = MaccmsRecommendationClient(server.baseUrl)
            assertThrows(IOException::class.java) { runBlocking { client.recommendationIds(selfId) } }
            assertEquals(1, server.requests.size)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.recommendationIds("../not-an-id") }
            }
        }
    }

    @Test
    fun `loader caps at ten maintains order and bounds concurrency to three`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val ids = (0..12).map(Int::toString)
        val result = loadPicacgRecommendations(ids) { id ->
            val running = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, running) }
            delay((12 - id.toInt()) * 2L)
            active.decrementAndGet()
            id.toInt()
        }

        assertEquals((0..9).toList(), result)
        assertTrue(maximum.get() <= 3)
        assertEquals(3, maximum.get())
    }

    @Test
    fun `loader retains successful values after individual failures`() = runBlocking {
        val result = loadPicacgRecommendations(listOf("a", "b", "c", "d")) { id ->
            if (id == "b") throw IOException("item failed")
            if (id == "c") null else id.uppercase()
        }
        assertEquals(listOf("A", "D"), result)
    }

    @Test
    fun `loader propagates cancellation and reports all failures`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                loadPicacgRecommendations(listOf("cancel", "other")) { id ->
                    if (id == "cancel") throw CancellationException("stop")
                    delay(500)
                    id
                }
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking { loadPicacgRecommendations(listOf("a", "b")) { throw IOException("failed") } }
        }
        assertThrows(IOException::class.java) {
            runBlocking { loadPicacgRecommendations<String>(listOf("a", "b")) { null } }
        }
    }

    @Test
    fun `empty recommendation list never starts metadata requests`() = runBlocking {
        val result = loadPicacgRecommendations<String>(emptyList()) { error("No lookup should start") }
        assertTrue(result.isEmpty())
    }

    private data class MockResponse(val body: String, val code: Int = 200, val location: String? = null)

    private class MockServer(private val responses: List<MockResponse>) : AutoCloseable {
        private val socket = ServerSocket(0)
        val requests = mutableListOf<String>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}/".toHttpUrl()
        private val worker = Thread {
            responses.forEach { mockResponse ->
                socket.accept().use { connection ->
                    connection.soTimeout = 3000
                    val reader = connection.getInputStream().bufferedReader()
                    val requestLines = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        requestLines += line
                        if (line.isEmpty()) break
                    }
                    synchronized(requests) { requests += requestLines.joinToString("\n") }
                    val bytes = mockResponse.body.toByteArray(Charsets.UTF_8)
                    val reason = if (mockResponse.code == 200) "OK" else "Found"
                    val locationHeader = mockResponse.location?.let { "Location: $it\r\n" }.orEmpty()
                    val headers = "HTTP/1.1 ${mockResponse.code} $reason\r\n" +
                        "Content-Type: application/json\r\n" + locationHeader +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    connection.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.UTF_8))
                        write(bytes)
                        flush()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            worker.join(4000)
            socket.close()
            check(!worker.isAlive) { "Mock server did not finish" }
        }
    }
}
