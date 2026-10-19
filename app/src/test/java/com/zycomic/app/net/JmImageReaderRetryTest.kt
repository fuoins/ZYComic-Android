package com.zycomic.app.net

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class JmImageReaderRetryTest {
    @Test
    fun `remaining JM routes exclude attempted hostnames without consulting Manwa endpoints`() {
        val hosts = JmRouteManager.imageCandidates()
        val attempted = setOf(DomainIp(hosts.first().uppercase(), "192.0.2.1"))
        assertEquals(
            hosts.drop(1).map { DomainIp(it, "").normalizedForComparison() },
            JmRouteManager.imageRetryEndpoints(attempted),
        )
        assertTrue(JmRouteManager.imageRetryEndpoints(hosts.map { DomainIp(it, "") }.toSet()).isEmpty())
    }

    @Test
    fun `manual retry round cannot restart automatic rounds after the existing limit`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            page.automaticRetryRound.set(PageAttemptScope.MAX_AUTOMATIC_RETRY_ROUNDS + 1)
            val attempt = scope(page, coordinator)
            repeat(PageAttemptScope.MAX_ENDPOINTS_PER_ROUND) {
                assertTrue(attempt.recordEndpointAttempt(DomainIp("image$it.example", "")))
            }
            assertFalse(attempt.retryAfterFailure(true))
            assertEquals(4, page.automaticRetryRound.get())
        }
    }

    @Test
    fun `502 does not independently advance the shared JM source pool`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val hosts = (1..4).map { "image$it.example" }
            val visited = mutableListOf<String>()
            val failures = mutableListOf<Pair<String, String>>()
            val client = client(hosts, failures) { request ->
                assertEquals(null, request.url.queryParameter("_jm_scramble"))
                assertTrue(request.tag(PageAttemptScope::class.java) != null)
                visited += request.url.host
                502
            }
            val first = scope(page, coordinator)
            val error = assertThrows(IOException::class.java) { load(client, first) }
            assertTrue(error.message.orEmpty().contains("HTTP 502"))
            assertEquals(1, page.endpointsAttemptedInRound.get())
            assertEquals(listOf(hosts.first()), visited)
            assertFalse(first.retryAfterFailure(false))
            assertEquals(1, page.automaticRetryRound.get())
            assertTrue(failures.isEmpty())
            assertFalse(first.hasActiveEndpointRequest)
        }
    }

    @Test
    fun `transient HTTP errors do not rotate across JM domains in one reader worker`() {
        for (status in listOf(502, 503, 504)) {
            ImageAttemptCoordinator().use { coordinator ->
                val page = ReaderPage(0, imageUrl = IMAGE_URL)
                val hosts = (1..10).map { "image$it.example" }
                val visited = mutableListOf<String>()
                val failures = mutableListOf<Pair<String, String>>()
                val client = client(hosts, failures) { request ->
                    visited += request.url.host
                    status
                }
                val attempt = scope(page, coordinator)
                assertThrows(IOException::class.java) { load(client, attempt) }
                assertEquals(listOf(hosts.first()), visited)
                assertFalse(attempt.retryAfterFailure(false))
                assertTrue(failures.isEmpty())
            }
        }
    }

    @Test
    fun `small JM pool does not rotate an active reader request to another host`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val hosts = listOf("first.example", "second.example")
            val failures = mutableListOf<Pair<String, String>>()
            val client = client(hosts, failures) { 502 }
            val attempt = scope(page, coordinator)
            assertThrows(IOException::class.java) { load(client, attempt) }
            assertEquals(1, page.endpointsAttemptedInRound.get())
            assertFalse(attempt.retryAfterFailure(false))
            assertEquals(1, page.automaticRetryRound.get())
        }
    }

    @Test
    fun `transport failure does not independently rotate the JM source pool`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val failures = mutableListOf<Pair<String, String>>()
            val visited = mutableListOf<String>()
            val client = client(listOf("first.example", "second.example"), failures) { request ->
                visited += request.url.host
                throw java.net.SocketException("connection reset")
            }
            assertThrows(IOException::class.java) { load(client, scope(page, coordinator)) }
            assertEquals(listOf("first.example"), visited)
            assertTrue(failures.isEmpty())
            assertEquals(1, page.endpointsAttemptedInRound.get())
        }
    }

    @Test
    fun `stale or cancelled page cannot request or record a failed endpoint`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val failures = mutableListOf<Pair<String, String>>()
            var requests = 0
            val client = client(listOf("first.example"), failures) {
                requests++
                502
            }
            val attempt = scope(page, coordinator)
            attempt.cancel(ImageCancelReason.USER_RETRY)
            assertThrows(IOException::class.java) { load(client, attempt) }
            assertEquals(0, requests)
            assertEquals(0, failures.size)
            assertEquals(0, page.endpointsAttemptedInRound.get())
            assertFalse(attempt.retryAfterFailure(true))
        }
    }

    @Test
    fun `unscoped cover does not scan JM domains within one request`() {
        val hosts = (1..4).map { "image$it.example" }
        val failures = mutableListOf<Pair<String, String>>()
        val visited = mutableListOf<String>()
        val client = client(hosts, failures) { request ->
            visited += request.url.host
            502
        }
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://${hosts.first()}/media/albums/123_3x4.jpg").build()).execute().close()
        }
        assertEquals(listOf(hosts.first()), visited)
        assertTrue(failures.isEmpty())
    }

    private fun scope(page: ReaderPage, coordinator: ImageAttemptCoordinator) =
        PageAttemptScope(page, page.generation.get(), coordinator, canRace = false)

    private fun remaining(hosts: List<String>, page: ReaderPage): Boolean =
        hosts.any { DomainIp(it, "").normalizedForComparison() !in page.attemptedImageEndpoints }

    private fun load(client: OkHttpClient, attempt: PageAttemptScope) {
        client.newCall(
            Request.Builder().url(IMAGE_URL).tag(PageAttemptScope::class.java, attempt).build(),
        ).execute().use { assertTrue(it.isSuccessful) }
    }

    private fun client(
        hosts: List<String>,
        failures: MutableList<Pair<String, String>>,
        status: (Request) -> Int,
    ): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .addInterceptor(
            JmImageInterceptor(
                imageCandidates = { hosts },
                prepareImageRoute = {},
                recordImageSuccess = {},
                recordImageFailure = { host, reason -> failures += host to reason },
                activeImageDomain = { hosts.first() },
            ),
        )
        .addInterceptor { chain ->
            val request = chain.request()
            val code = status(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .header("Content-Type", if (code == 200) "image/gif" else "text/html")
                .body((if (code == 200) "GIF89a" else "Bad gateway").toResponseBody("image/gif".toMediaType()))
                .build()
        }
        .build()

    private companion object {
        const val IMAGE_URL = "https://origin.example/media/photos/123/00001.jpg?_jm_scramble=220980"
    }
}
