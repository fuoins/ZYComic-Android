package com.zycomic.app.net

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Real OkHttp application-interceptor chain; the terminal interceptor never opens a socket. */
class JmImageCancellationTest {
    @Test
    fun `cancelled unscoped cover cannot poison endpoints used by later search and browse covers`() {
        val health = Health()
        var cancelFirst = true
        val visited = mutableListOf<String>()
        val client = client(health) { chain ->
            visited += chain.request().url.host
            if (cancelFirst) {
                cancelFirst = false
                chain.call().cancel()
                // Do not infer cancellation from exception text.
                throw IOException("socket closed")
            }
            response(chain)
        }
        assertThrows(IOException::class.java) { client.newCall(request()).execute().close() }
        health.assertUnchanged()
        assertEquals(listOf(FIRST), visited, "A cancelled call must not try another host")
        assertNull(JmEndpointRequestContext.current(FIRST, image = true))

        for (album in listOf("search-result", "browse-result")) {
            client.newCall(request(album)).execute().use { assertEquals("GIF89a", it.body!!.string()) }
        }
        assertEquals(listOf(FIRST, FIRST, FIRST), visited)
        assertEquals(listOf(FIRST, FIRST), health.hostSuccesses)
        assertTrue(health.hostFailures.isEmpty())
        assertTrue(health.endpointFailures.isEmpty())
    }

    @Test
    fun `cancellation before image interception cannot prepare or request a route`() {
        val health = Health()
        var prepared = 0
        var requested = 0
        val client = client(
            health,
            before = Interceptor { chain ->
                chain.call().cancel()
                chain.proceed(chain.request())
            },
            prepare = { prepared++ },
        ) { chain ->
            requested++
            response(chain)
        }
        assertThrows(IOException::class.java) { client.newCall(request()).execute().close() }
        assertEquals(0, prepared)
        assertEquals(0, requested)
        health.assertUnchanged()
    }

    @Test
    fun `cancellation during route preparation stops before transport`() {
        val health = Health()
        var requested = 0
        val call = arrayOfNulls<okhttp3.Call>(1)
        val client = client(health, prepare = { call[0]!!.cancel() }) { chain ->
            requested++
            response(chain)
        }
        call[0] = client.newCall(request())
        assertThrows(IOException::class.java) { call[0]!!.execute().close() }
        assertEquals(0, requested)
        health.assertUnchanged()
    }

    @Test
    fun `cancelled HTTP error is closed without failure writes or fallback`() {
        val health = Health()
        var closed = false
        var requests = 0
        val client = client(health) { chain ->
            requests++
            chain.call().cancel()
            response(chain, code = 503, body = body(onClose = { closed = true }))
        }
        assertThrows(IOException::class.java) { client.newCall(request()).execute().close() }
        assertEquals(1, requests)
        assertTrue(closed)
        health.assertUnchanged()
    }

    @Test
    fun `cancelled bootstrap response cannot persist a successful endpoint`() {
        val health = Health()
        var closed = false
        val client = client(health, measured = false) { chain ->
            chain.call().cancel()
            response(chain, body = body(onClose = { closed = true }))
        }
        assertThrows(IOException::class.java) { client.newCall(request()).execute().close() }
        assertTrue(closed)
        health.assertUnchanged()
    }

    @Test
    fun `cover body cancellation neither marks host successful nor records failure`() {
        for (throwOnRead in listOf(true, false)) {
            val health = Health()
            var closed = false
            var requests = 0
            val client = client(health) { chain ->
                requests++
                response(
                    chain,
                    body = body(
                        onRead = {
                            chain.call().cancel()
                            if (throwOnRead) throw IOException("stream closed")
                        },
                        onClose = { closed = true },
                    ),
                )
            }
            assertThrows(IOException::class.java) { client.newCall(request()).execute().close() }
            assertEquals(1, requests)
            assertTrue(closed)
            health.assertUnchanged()
        }
    }

    @Test
    fun `scope invalidation without Call cancellation cannot record IP or host failure`() {
        for (returnHttpError in listOf(true, false)) {
            ImageAttemptCoordinator().use { coordinator ->
                val page = ReaderPage(0)
                val scope = PageAttemptScope(page, page.generation.get(), coordinator, canRace = false)
                val health = Health()
                var requests = 0
                var closed = false
                val client = client(health) { chain ->
                    requests++
                    page.generation.incrementAndGet()
                    assertFalse(chain.call().isCanceled())
                    if (!returnHttpError) throw IOException("connection reset")
                    response(chain, code = 503, body = body(onClose = { closed = true }))
                }
                assertThrows(IOException::class.java) {
                    client.newCall(request().newBuilder().tag(PageAttemptScope::class.java, scope).build()).execute().close()
                }
                assertEquals(1, requests)
                assertEquals(returnHttpError, closed)
                assertFalse(scope.hasActiveEndpointRequest)
                health.assertUnchanged()
            }
        }
    }

    @Test
    fun `already invalidated scope never prepares a route`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0)
            val scope = PageAttemptScope(page, page.generation.get(), coordinator, canRace = false)
            page.generation.incrementAndGet()
            val health = Health()
            var prepared = 0
            val client = client(health, prepare = { prepared++ }) { error("Must not request stale image") }
            assertThrows(IOException::class.java) {
                client.newCall(request().newBuilder().tag(PageAttemptScope::class.java, scope).build()).execute().close()
            }
            assertEquals(0, prepared)
            health.assertUnchanged()
        }
    }

    @Test
    fun `transport cancellation cannot record API or image IP failures`() {
        for (path in listOf("/search", "/media/albums/123.jpg")) {
            val health = Health()
            val client = OkHttpClient.Builder()
                .addInterceptor(transport(health))
                .addInterceptor { chain ->
                    chain.call().cancel()
                    throw IOException("connection reset")
                }
                .build()
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url("https://$FIRST$path").build()).execute().close()
            }
            health.assertUnchanged()
            assertNull(JmEndpointRequestContext.current(FIRST, path.startsWith("/media/")))
        }
    }

    @Test
    fun `outer API interceptor cannot reclassify cancelled search as host failure`() {
        val health = Health()
        val logsBefore = JmRouteManager.logs.value
        val selectionBefore = JmRouteManager.endpointSelection.value
        val client = OkHttpClient.Builder()
            .addInterceptor(JmApiInterceptor(recordRouteHealth = true, allowAuthentication = false))
            .addInterceptor(transport(health))
            .addInterceptor { chain ->
                chain.call().cancel()
                throw IOException("socket closed")
            }
            .build()
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://$FIRST/search").build()).execute().close()
        }
        health.assertUnchanged()
        assertEquals(logsBefore, JmRouteManager.logs.value)
        assertEquals(selectionBefore, JmRouteManager.endpointSelection.value)
    }

    @Test
    fun `genuine connection failure still records IP and host then falls back`() {
        val health = Health()
        val visited = mutableListOf<String>()
        val client = client(health) { chain ->
            visited += chain.request().url.host
            if (chain.request().url.host == FIRST) throw IOException("connection reset")
            response(chain)
        }
        client.newCall(request()).execute().use { assertEquals("GIF89a", it.body!!.string()) }
        assertEquals(listOf(FIRST, SECOND), visited)
        assertEquals(listOf(DomainIp(FIRST, "192.0.2.1")), health.endpointFailures)
        assertEquals(listOf(FIRST), health.hostFailures)
        assertEquals(listOf(SECOND), health.hostSuccesses)
    }

    @Test
    fun `genuine HTTP failure still records IP and host then falls back`() {
        val health = Health()
        val visited = mutableListOf<String>()
        val client = client(health) { chain ->
            visited += chain.request().url.host
            response(chain, code = if (chain.request().url.host == FIRST) 503 else 200)
        }
        client.newCall(request()).execute().use { assertTrue(it.isSuccessful) }
        assertEquals(listOf(FIRST, SECOND), visited)
        assertEquals(listOf(DomainIp(FIRST, "192.0.2.1")), health.endpointFailures)
        assertEquals(listOf(FIRST), health.hostFailures)
    }

    @Test
    fun `IOException message alone does not suppress real failure`() {
        val health = Health()
        val client = client(health) { chain ->
            if (chain.request().url.host == FIRST) throw IOException("Canceled")
            response(chain)
        }
        client.newCall(request()).execute().close()
        assertEquals(listOf(FIRST), health.hostFailures)
        assertEquals(listOf(DomainIp(FIRST, "192.0.2.1")), health.endpointFailures)
    }

    private class Health {
        val endpointFailures = mutableListOf<DomainIp>()
        val endpointSuccesses = mutableListOf<DomainIp>()
        val hostFailures = mutableListOf<String>()
        val hostSuccesses = mutableListOf<String>()

        fun assertUnchanged() {
            assertTrue(endpointFailures.isEmpty(), "Cancelled calls cannot write IP exclusions")
            assertTrue(endpointSuccesses.isEmpty(), "Cancelled calls cannot persist bootstrap selection")
            assertTrue(hostFailures.isEmpty(), "Cancelled calls cannot write host failure state")
            assertTrue(hostSuccesses.isEmpty(), "Cancelled calls cannot reset host health")
        }
    }

    private fun transport(health: Health, measured: Boolean = true) = JmEndpointTransportInterceptor(
        prepareApiRoute = {},
        prepareImageRoute = {},
        apiEndpoint = { DomainIp(it, "192.0.2.1") },
        imageEndpoint = { DomainIp(it, "192.0.2.1") },
        apiHasMeasurements = { measured },
        imageHasMeasurements = { measured },
        apiFailure = { endpoint, _ -> health.endpointFailures += endpoint },
        imageFailure = { endpoint, _ -> health.endpointFailures += endpoint },
        apiSuccess = { health.endpointSuccesses += it },
        imageSuccess = { health.endpointSuccesses += it },
    )

    private fun client(
        health: Health,
        measured: Boolean = true,
        before: Interceptor? = null,
        prepare: (String) -> Unit = {},
        terminal: Interceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .apply { if (before != null) addInterceptor(before) }
        .addInterceptor(JmApiInterceptor(recordRouteHealth = true, allowAuthentication = false))
        .addInterceptor(
            JmImageInterceptor(
                imageCandidates = { listOf(FIRST, SECOND).filterNot { it in health.hostFailures } },
                prepareImageRoute = prepare,
                recordImageFailure = { host, _ -> health.hostFailures += host },
                recordImageSuccess = { health.hostSuccesses += it },
            ),
        )
        .addInterceptor(transport(health, measured))
        .addInterceptor(terminal)
        .build()

    private fun request(album: String = "old-cover") = Request.Builder()
        .url("https://$FIRST/media/albums/$album.jpg")
        .build()

    private fun response(chain: Interceptor.Chain, code: Int = 200, body: ResponseBody = "GIF89a".toResponseBody()) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("offline")
            .header("Content-Type", if (code == 200) "image/gif" else "text/plain")
            .body(body).build()

    private fun body(onRead: () -> Unit = {}, onClose: () -> Unit): ResponseBody {
        val source = object : ForwardingSource(Buffer().writeUtf8("GIF89a")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                onRead()
                return super.read(sink, byteCount)
            }

            override fun close() {
                onClose()
                super.close()
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = "image/gif".toMediaType()
            override fun contentLength() = 6L
            override fun source() = source
        }
    }

    private companion object {
        const val FIRST = "first.example"
        const val SECOND = "second.example"
    }
}
