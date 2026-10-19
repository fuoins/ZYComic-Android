package com.zycomic.app.net

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPageLoadStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class JmImageReceiveProgressTest {
    @Test
    fun `reader receives live percentages before the full network body is buffered`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val scope = scope(page, coordinator)
            val body = ChunkedBody(IMAGE, onRead = { offset ->
                if (offset == 0) {
                    assertEquals(ReaderPageLoadStatus.Requesting, page.loadStatus)
                    assertFalse(scope.receivedAnyBytes.get())
                } else {
                    assertEquals(ReaderPageLoadStatus.Receiving(100 * offset / IMAGE.size), page.loadStatus)
                    assertEquals(100 * offset / IMAGE.size, page.progress)
                    assertTrue(scope.hasRecentByteProgress())
                    assertTrue(scope.hasActiveEndpointRequest)
                }
            })
            load(client { body }, scope).use { response ->
                assertArrayEquals(IMAGE, response.body!!.bytes())
            }
            assertEquals(ReaderPageLoadStatus.Receiving(100), page.loadStatus)
            assertTrue(body.closed)
            assertFalse(scope.hasActiveEndpointRequest)
            assertEquals(1, page.endpointsAttemptedInRound.get())
            // Receiving bytes must not claim cache/validation ownership.
            assertTrue(scope.claimWinner())
            assertFalse(scope.claimWinner())
        }
    }

    @Test
    fun `unknown content length reports shared indeterminate receiving state while reading`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val scope = scope(page, coordinator)
            val body = ChunkedBody(IMAGE, length = -1, onRead = { offset ->
                if (offset > 0) {
                    assertEquals(ReaderPageLoadStatus.Receiving(null), page.loadStatus)
                    assertEquals(-1, page.progress)
                    assertTrue(scope.receivedAnyBytes.get())
                }
            })
            load(client { body }, scope).close()
            assertEquals(ReaderPageLoadStatus.Receiving(null), page.loadStatus)
            assertTrue(body.closed)
        }
    }

    @Test
    fun `gzip progress counts encoded transport bytes and keeps GIF restoration`() {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(IMAGE) }
        }.toByteArray()
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val body = ChunkedBody(compressed, onRead = { offset ->
                if (offset > 0) {
                    assertEquals(ReaderPageLoadStatus.Receiving(100 * offset / compressed.size), page.loadStatus)
                }
            })
            load(client(gzip = true) { body }, scope(page, coordinator)).use { response ->
                assertArrayEquals(IMAGE, response.body!!.bytes())
                assertEquals(null, response.header("Content-Encoding"))
                assertEquals(null, response.header("Content-Length"))
            }
            assertTrue(body.closed)
        }
    }

    @Test
    fun `cancellation during body read stops progress and prevents fallback failure bookkeeping`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val scope = scope(page, coordinator)
            val failures = mutableListOf<String>()
            val visited = mutableListOf<String>()
            val body = ChunkedBody(IMAGE, onRead = { offset ->
                if (offset > 0) scope.cancel(ImageCancelReason.USER_RETRY)
            })
            val client = client(listOf("first.example", "second.example"), failures = failures) { host ->
                visited += host
                body
            }
            assertThrows(IOException::class.java) { load(client, scope) }
            assertEquals(ReaderPageLoadStatus.Receiving(100 * CHUNK_SIZE / IMAGE.size), page.loadStatus)
            assertEquals(listOf("first.example"), visited)
            assertTrue(failures.isEmpty())
            assertTrue(body.closed)
            assertFalse(scope.hasActiveEndpointRequest)
        }
    }

    @Test
    fun `new page generation cannot receive stale transport progress`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val scope = scope(page, coordinator)
            val body = ChunkedBody(IMAGE, onRead = { offset ->
                if (offset > 0) {
                    page.generation.incrementAndGet()
                    page.loadStatus = ReaderPageLoadStatus.WaitingForRequest
                }
            })
            assertThrows(IOException::class.java) { load(client { body }, scope) }
            assertEquals(ReaderPageLoadStatus.WaitingForRequest, page.loadStatus)
            assertTrue(body.closed)
        }
    }

    @Test
    fun `body connection failure stays on the active source for coordinated recovery`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val failedBody = ChunkedBody(IMAGE, onRead = { offset ->
                if (offset > 0) throw java.net.SocketException("body connection reset")
            })
            val goodBody = ChunkedBody(IMAGE)
            val failures = mutableListOf<String>()
            val visited = mutableListOf<String>()
            val client = client(listOf("first.example", "second.example"), failures = failures) { host ->
                visited += host
                if (host == "first.example") failedBody else goodBody
            }
            assertThrows(IOException::class.java) { load(client, scope(page, coordinator)) }
            assertEquals(listOf("first.example"), visited)
            assertTrue(failures.isEmpty())
            assertEquals(1, page.endpointsAttemptedInRound.get())
            assertEquals(1, page.automaticRetryRound.get())
            assertEquals(ReaderPageLoadStatus.Receiving(100 * CHUNK_SIZE / IMAGE.size), page.loadStatus)
            assertTrue(failedBody.closed)
            assertFalse(goodBody.closed)
        }
    }

    @Test
    fun `declared body length mismatch remains an IO failure`() {
        ImageAttemptCoordinator().use { coordinator ->
            val page = ReaderPage(0, imageUrl = IMAGE_URL)
            val body = ChunkedBody(IMAGE, length = IMAGE.size.toLong() + 1)
            val error = assertThrows(IOException::class.java) { load(client { body }, scope(page, coordinator)) }
            assertTrue(error.message.orEmpty().contains("disagree"))
            assertTrue(body.closed)
        }
    }

    @Test
    fun `unscoped images retain the original buffered read path`() {
        val body = ChunkedBody(IMAGE)
        load(client { body }, null).use { assertArrayEquals(IMAGE, it.body!!.bytes()) }
        assertTrue(body.closed)
    }

    private fun scope(page: ReaderPage, coordinator: ImageAttemptCoordinator) =
        PageAttemptScope(page, page.generation.get(), coordinator, canRace = false)

    private fun load(client: OkHttpClient, scope: PageAttemptScope?): Response = client.newCall(
        Request.Builder().url(IMAGE_URL).tag(PageAttemptScope::class.java, scope).build(),
    ).execute()

    private fun client(
        hosts: List<String> = listOf("image.example"),
        gzip: Boolean = false,
        failures: MutableList<String> = mutableListOf(),
        body: (String) -> ResponseBody,
    ): OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .addInterceptor(
            JmImageInterceptor(
                imageCandidates = { hosts },
                prepareImageRoute = {},
                recordImageSuccess = {},
                recordImageFailure = { host, _ -> failures += host },
                activeImageDomain = { hosts.first() },
            ),
        )
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .header("Content-Type", "image/gif")
                .apply { if (gzip) header("Content-Encoding", "gzip") }
                .body(body(chain.request().url.host))
                .build()
        }
        .build()

    private class ChunkedBody(
        private val bytes: ByteArray,
        private val length: Long = bytes.size.toLong(),
        private val onRead: (Int) -> Unit = {},
    ) : ResponseBody() {
        var closed = false
            private set
        private val bufferedSource = object : Source {
            private var offset = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                onRead(offset)
                if (offset == bytes.size) return -1
                val count = minOf(CHUNK_SIZE, bytes.size - offset, byteCount.toInt())
                sink.write(bytes, offset, count)
                offset += count
                return count.toLong()
            }

            override fun timeout() = Timeout.NONE
            override fun close() {
                closed = true
            }
        }.buffer()

        override fun contentType() = "image/gif".toMediaType()
        override fun contentLength() = length
        override fun source() = bufferedSource
    }

    private companion object {
        const val IMAGE_URL = "https://origin.example/media/photos/123/00001.gif?_jm_scramble=220980"
        const val CHUNK_SIZE = 3
        val IMAGE = "GIF89a-live-transfer-progress".toByteArray()
    }
}
