package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmSpeedProbeProtocolTest {

    @Test
    fun `uses the expected signed API and image paths`() {
        assertEquals("/setting?app_img_shunt=1&express=&lang=TW", JmSpeedProbeProtocol.LINE_PATH)
        assertEquals("/media/albums/553653_3x4.jpg", JmSpeedProbeProtocol.IMAGE_PATH)
        assertEquals(32 * 1024L, JmSpeedProbeProtocol.IMAGE_PROBE_BYTES)
        assertEquals(BoundedImageProbeSampler.DEFAULT_MAX_BYTES, JmSpeedProbeProtocol.IMAGE_PROBE_BYTES.toInt())
    }

    @Test
    fun `accepts only complete decrypted JSON objects and arrays`() {
        assertTrue(JmSpeedProbeProtocol.isValidDecryptedJson("{\"albums\":[]}"))
        assertTrue(JmSpeedProbeProtocol.isValidDecryptedJson("[{\"id\":553653}]"))
        assertTrue(JmSpeedProbeProtocol.isValidDecryptedJson("{}"))
        assertTrue(JmSpeedProbeProtocol.isValidDecryptedJson("[]"))
    }

    @Test
    fun `rejects empty html scalar malformed and encrypted envelope payloads`() {
        listOf(
            "",
            "   ",
            "<html><body>ok</body></html>",
            "\"text\"",
            "42",
            "true",
            "null",
            "{\"valid\":true} trailing",
            "{\"code\":200,\"data\":\"encrypted-base64\"}",
            "{\"code\":\"200\",\"data\":\"encrypted-base64\"}",
        ).forEach { payload ->
            assertFalse(JmSpeedProbeProtocol.isValidDecryptedJson(payload), "Should reject: $payload")
        }
    }

    @Test
    fun `image response requires image content type and recognized magic bytes`() {
        assertTrue(JmSpeedProbeProtocol.isValidImageResponse("image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)))
        assertTrue(JmSpeedProbeProtocol.isValidImageResponse("image/png; charset=binary", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertTrue(JmSpeedProbeProtocol.isValidImageResponse("image/gif", "GIF89a".encodeToByteArray()))
        assertTrue(
            JmSpeedProbeProtocol.isValidImageResponse(
                "image/webp",
                "RIFF0000WEBP".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `image response rejects html pseudo success wrong mime and truncated signatures`() {
        val html = "<html>not an image</html>".encodeToByteArray()
        assertFalse(JmSpeedProbeProtocol.isValidImageResponse("image/jpeg", html))
        assertFalse(JmSpeedProbeProtocol.isValidImageResponse("text/html", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(JmSpeedProbeProtocol.isValidImageResponse("image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertFalse(JmSpeedProbeProtocol.isValidImageResponse("image/jpeg", byteArrayOf()))
        assertFalse(JmSpeedProbeProtocol.isValidImageResponse(null, html))
    }
}
