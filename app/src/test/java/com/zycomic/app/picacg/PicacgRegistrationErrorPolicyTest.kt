package com.zycomic.app.picacg

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgRegistrationErrorPolicyTest {
    @Test
    fun `maps duplicate and rate limit codes without returning server text`() {
        val duplicate = PicacgRegistrationErrorPolicy.message(
            PicacgResponseException(400, 400, "1025", "private server response"),
        )
        val limited = PicacgRegistrationErrorPolicy.message(
            PicacgResponseException(429, 429, "", "private server response"),
        )

        assertTrue(duplicate.contains("账号或昵称"))
        assertTrue(limited.contains("频繁"))
        assertFalse(duplicate.contains("private server response"))
        assertFalse(limited.contains("private server response"))
    }

    @Test
    fun `unknown errors use a generic safe message`() {
        val safe = PicacgRegistrationErrorPolicy.message(IllegalStateException("private server response"))

        assertTrue(safe.contains("注册失败"))
        assertFalse(safe.contains("private server response"))
    }
}
