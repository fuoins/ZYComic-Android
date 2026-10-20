package com.zycomic.app.picacg

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class PicacgRedirectPolicyTest {
    private val source = "http://api.example/api/v2/login?x=1".toHttpUrl()

    @Test
    fun `allows only same-host HTTPS redirect with identical encoded path and query`() {
        val target = PicacgRedirectPolicy.targetFor301(
            source,
            "https://API.EXAMPLE:8443/api/v2/login?x=1",
        )

        assertEquals("https", target.scheme)
        assertEquals(source.host, target.host)
        assertEquals(source.encodedPath, target.encodedPath)
        assertEquals(source.encodedQuery, target.encodedQuery)
        assertEquals(8443, target.port)
    }

    @Test
    fun `rejects cross-host downgrade path and query changes and missing location`() {
        val invalidLocations = listOf(
            "https://elsewhere.example/api/v2/login?x=1",
            "http://api.example/api/v2/login?x=1",
            "https://api.example/api/v2/other?x=1",
            "https://api.example/api/v2/login?x=2",
            null,
        )

        invalidLocations.forEach { location ->
            assertThrows(IOException::class.java) {
                PicacgRedirectPolicy.targetFor301(source, location)
            }
        }
    }

    @Test
    fun `rejects redirect from an already HTTPS request and URL user info`() {
        assertThrows(IOException::class.java) {
            PicacgRedirectPolicy.targetFor301("https://api.example/api/v2/login?x=1".toHttpUrl(), "https://api.example/api/v2/login?x=1")
        }
        assertThrows(IOException::class.java) {
            PicacgRedirectPolicy.targetFor301(source, "https://user@api.example/api/v2/login?x=1")
        }
    }
}
