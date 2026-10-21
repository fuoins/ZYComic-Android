package com.zycomic.app.picacg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

class PicacgLoginErrorPolicyTest {
    @Test
    fun `structured response preserves HTTP business and authorization codes`() {
        listOf(
            PicacgResponseException(429, 429, "", "service reply\nwith extra detail"),
            PicacgResponseException(200, 1023, "", "request rejected"),
            PicacgResponseException(400, 400, "1023", "request rejected"),
        ).forEach {
            assertEquals(PicacgLoginFailure.RATE_LIMITED, PicacgLoginErrorPolicy.classify(it))
        }
        assertEquals(
            PicacgLoginFailure.UNAUTHORIZED,
            PicacgLoginErrorPolicy.classify(PicacgResponseException(401, 401, "", "expired")),
        )
        assertEquals(
            PicacgLoginFailure.UNAUTHORIZED,
            PicacgLoginErrorPolicy.classify(PicacgResponseException(400, 400, "1005", "expired")),
        )
    }

    @Test
    fun `classifies connection timeout before inspecting any message text`() {
        val timeout = SocketTimeoutException("HTTP 429 too many requests: 1023")
        val wrappedTimeout = IOException("request failed", timeout)

        assertEquals(PicacgLoginFailure.CONNECTION_TIMEOUT, PicacgLoginErrorPolicy.classify(timeout))
        assertEquals(PicacgLoginFailure.CONNECTION_TIMEOUT, PicacgLoginErrorPolicy.classify(wrappedTimeout))
        assertEquals(
            "网络连接超时，请检查网络连接后重试。",
            PicacgLoginErrorPolicy.message(PicacgLoginErrorPolicy.classify(timeout)),
        )
    }

    @Test
    fun `recognizes exact HTTP 429 and business error 1023 response fields`() {
        assertEquals(
            PicacgLoginFailure.RATE_LIMITED,
            PicacgLoginErrorPolicy.classify(IOException("Picacg request failed (429/): HTTP error")),
        )
        assertEquals(
            PicacgLoginFailure.RATE_LIMITED,
            PicacgLoginErrorPolicy.classify(IOException("Picacg request failed (200/1023): Too many requests")),
        )
        assertEquals(
            "请求过于频繁，请稍后再试。",
            PicacgLoginErrorPolicy.message(PicacgLoginFailure.RATE_LIMITED),
        )
    }

    @Test
    fun `does not infer rate limiting from vague text or partial numeric matches`() {
        val ambiguousFailures = listOf(
            IOException("Too many requests"),
            IOException("Picacg request failed (200/11023): HTTP error"),
            IOException("Picacg request failed (200/10230): HTTP error"),
            IOException("connection timeout: 1023"),
            IllegalArgumentException("untranslated client failure"),
        )

        ambiguousFailures.forEach { failure ->
            assertEquals(PicacgLoginFailure.UNKNOWN, PicacgLoginErrorPolicy.classify(failure), failure.message)
        }
    }

    @Test
    fun `maps known account responses and keeps all user messages Chinese`() {
        assertEquals(
            PicacgLoginFailure.INVALID_CREDENTIALS,
            PicacgLoginErrorPolicy.classify(IOException("Picacg request failed (400/): invalid email or password")),
        )
        assertEquals(
            PicacgLoginFailure.BANNED,
            PicacgLoginErrorPolicy.classify(IOException("Picacg request failed (403/): account banned")),
        )
        assertEquals(
            PicacgLoginFailure.LOCKED,
            PicacgLoginErrorPolicy.classify(IOException("Picacg request failed (403/): account locked")),
        )

        PicacgLoginFailure.entries.forEach { failure ->
            assertFalse(PicacgLoginErrorPolicy.message(failure).any { it in 'A'..'Z' || it in 'a'..'z' }, failure.name)
        }
    }
}
