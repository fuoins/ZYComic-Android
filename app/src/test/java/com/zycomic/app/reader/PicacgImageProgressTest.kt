package com.zycomic.app.reader

import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class PicacgImageProgressTest {
    @Test
    fun `known size reports bytes actually consumed and reaches complete only after reading`() {
        val events = mutableListOf<PicacgImageProgress>()
        val bytes = ByteArray(32768) { (it % 251).toByte() }
        response(bytes).withPicacgImageProgress({ true }, events::add).use { observed ->
            assertTrue(events.isEmpty())
            val sink = Buffer()
            assertTrue(observed.body.source().read(sink, 1024) > 0)
            assertTrue(events.first().bytesRead in 1L until bytes.size.toLong())
            assertTrue(events.first().percentage!! in 1..99)
            observed.body.source().readAll(sink)
            assertTrue(bytes.contentEquals(sink.readByteArray()))
            assertEquals(32768L, events.last().bytesRead)
            assertEquals(100, events.last().percentage)
        }
    }

    @Test
    fun `unknown length never invents a percentage even at EOF`() {
        val events = mutableListOf<PicacgImageProgress>()
        response(ByteArray(12000), length = -1).withPicacgImageProgress({ true }, events::add).use {
            assertEquals(12000, it.body.bytes().size)
        }
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.percentage == null && it.contentLength == -1L })
        assertEquals(12000L, events.last().bytesRead)
    }

    @Test
    fun `superseded request cannot publish into a new page generation`() {
        var current = true
        val events = mutableListOf<PicacgImageProgress>()
        response(ByteArray(32768)).withPicacgImageProgress({ current }, events::add).use {
            val sink = Buffer()
            it.body.source().read(sink, 1024)
            assertFalse(events.isEmpty())
            val before = events.toList()
            current = false
            it.body.source().readAll(sink)
            assertEquals(before, events)
        }
    }

    @Test
    fun `two simultaneous responses keep their progress independent`() {
        val first = mutableListOf<PicacgImageProgress>()
        val second = mutableListOf<PicacgImageProgress>()
        response(ByteArray(40000)).withPicacgImageProgress({ true }, first::add).use { a ->
            response(ByteArray(16000)).withPicacgImageProgress({ true }, second::add).use { b ->
                a.body.source().read(Buffer(), 1024)
                val firstBefore = first.toList()
                b.body.bytes()
                assertEquals(firstBefore, first)
                assertEquals(16000L, second.last().bytesRead)
                a.body.source().readAll(Buffer())
                assertEquals(40000L, first.last().bytesRead)
            }
        }
    }

    @Test
    fun `observer preserves response metadata and close propagates to original body`() {
        var closed = false
        val original = response(ByteArray(12), onClose = { closed = true })
        original.withPicacgImageProgress({ true }) {}.use {
            assertEquals(original.request, it.request)
            assertEquals(original.code, it.code)
            assertEquals(original.headers, it.headers)
            assertEquals(original.body.contentType(), it.body.contentType())
            assertEquals(original.body.contentLength(), it.body.contentLength())
        }
        assertTrue(closed)
    }

    @Test
    fun `percentage handles empty unknown oversized and long byte counts safely`() {
        assertNull(PicacgImageProgress(0, 0).percentage)
        assertNull(PicacgImageProgress(123, -1).percentage)
        assertEquals(0, PicacgImageProgress(-1, 100).percentage)
        assertEquals(50, PicacgImageProgress(Long.MAX_VALUE / 2, Long.MAX_VALUE).percentage)
        assertEquals(100, PicacgImageProgress(Long.MAX_VALUE, Long.MAX_VALUE).percentage)
        assertEquals(100, PicacgImageProgress(500, 100).percentage)
    }

    @Test
    fun `failed body remains a failure while a following image still loads`() {
        val failedSource = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("offline test failure")
        }.buffer()
        val failedBody = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = failedSource
        }
        val events = mutableListOf<PicacgImageProgress>()
        val failedResponse = response(byteArrayOf()).newBuilder().body(failedBody).build()
        failedResponse.withPicacgImageProgress({ true }, events::add).use {
            assertThrows(IOException::class.java) { it.body.bytes() }
        }
        assertTrue(events.isEmpty())
        response(ByteArray(32)).withPicacgImageProgress({ true }, events::add).use {
            assertEquals(32, it.body.bytes().size)
        }
        assertEquals(PicacgImageProgress(32, 32), events.last())
    }

    private fun response(bytes: ByteArray, length: Long = bytes.size.toLong(), onClose: () -> Unit = {}): Response {
        val body = object : ResponseBody() {
            private val buffer = Buffer().write(bytes)
            private val observedSource = object : ForwardingSource(buffer) {
                override fun close() {
                    onClose()
                    super.close()
                }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = length
            override fun source(): BufferedSource = observedSource
        }
        return Response.Builder()
            .request(Request.Builder().url("https://picacg.invalid/image").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("X-Test", "unchanged")
            .body(body)
            .build()
    }
}
