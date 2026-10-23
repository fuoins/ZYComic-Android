package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException

class BoundedImageProbeSamplerTest {
    @Test
    fun `slow stream returning short chunks is read through the declared body`() {
        val bytes = ByteArray(257) { it.toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            var calls = 0

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                calls++
                Thread.sleep(2)
                return super.read(target, offset, minOf(length, 3))
            }
        }

        assertArrayEquals(bytes, BoundedImageProbeSampler.read(input, declaredLength = bytes.size.toLong()))
    }

    @Test
    fun `early EOF against declared content length is rejected`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))

        assertThrows(EOFException::class.java) {
            BoundedImageProbeSampler.read(input, declaredLength = 8L)
        }
    }

    @Test
    fun `a complete short image body remains eligible`() {
        val jpegPrefix = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val sampled = BoundedImageProbeSampler.read(
            ByteArrayInputStream(jpegPrefix),
            declaredLength = jpegPrefix.size.toLong(),
        )

        assertArrayEquals(jpegPrefix, sampled)
        assertTrue(JmSpeedProbeProtocol.isValidImageResponse("image/jpeg", sampled))
    }

    @Test
    fun `sampler never reads beyond the configured prefix limit`() {
        val bytes = ByteArray(BoundedImageProbeSampler.DEFAULT_MAX_BYTES + 5_000) { it.toByte() }
        val input = ByteArrayInputStream(bytes)
        val sampled = BoundedImageProbeSampler.read(input, declaredLength = bytes.size.toLong())

        assertEquals(BoundedImageProbeSampler.DEFAULT_MAX_BYTES, sampled.size)
        assertEquals(5_000, input.available())
    }

    @Test
    fun `read failures propagate instead of yielding a successful partial sample`() {
        val input = object : ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (pos >= 2) throw IOException("simulated transport read failure")
                return super.read(target, offset, minOf(length, 2))
            }
        }

        assertThrows(IOException::class.java) {
            BoundedImageProbeSampler.read(input, declaredLength = -1L)
        }
    }
}
