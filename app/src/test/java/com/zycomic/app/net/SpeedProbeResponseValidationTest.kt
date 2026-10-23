package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedProbeResponseValidationTest {
    @Test
    fun `complete objects larger than the old head limit remain valid`() {
        val payload = "{\"message\":\"${"x".repeat(512)}\"}"
        assertTrue(SpeedProbeResponseValidation.isJsonObject(payload.encodeToByteArray()))
        assertFalse(SpeedProbeResponseValidation.isJsonObject(payload.take(128).encodeToByteArray()))
    }

    @Test
    fun `malformed truncated html and trailing payloads are not successes`() {
        listOf("", "{garbage", "{\"ok\":true", "<html>ok</html>", "{} trailing", "{}{}").forEach {
            assertFalse(SpeedProbeResponseValidation.isJsonObject(it.encodeToByteArray()), it)
        }
    }

    @Test
    fun `only objects match the manwa API contract`() {
        assertTrue(SpeedProbeResponseValidation.isJsonObject("  {\"ok\":true}  ".encodeToByteArray()))
        listOf("[]", "null", "true", "42", "\"text\"").forEach {
            assertFalse(SpeedProbeResponseValidation.isJsonObject(it.encodeToByteArray()), it)
        }
    }
}
