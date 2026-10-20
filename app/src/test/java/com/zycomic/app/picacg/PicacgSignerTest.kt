package com.zycomic.app.picacg

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PicacgSignerTest {
    private val nonce = "0123456789abcdef0123456789abcdef"

    @Test
    fun `official sample matches with no account credentials or request body`() {
        // Static-analysis document's official vector; nonce completed from its existing capture.
        // Only public signing metadata is used: no account, password, token, or live request.
        assertEquals(
            "c1c77fe6fdd7b97dcaba4288bbf59969f1be1ebfbba78e2a635b749aa84fea22",
            PicacgSigner.sign("/auth/sign-in", "POST", 1_792_509_228L, "6lycvl4nsw7i4ez86yowssfhqoubtoq0"),
        )
    }

    @Test
    fun `signing preserves query order encoding and existing whole message lowercase`() {
        val path = "/api/v2/comics?q=A%2Fb&page=2&q=C%20D"
        assertEquals(
            "a49eaa24346eba6640438c5b81faa8c61468f196be6c72e2e0ee815f1d6e73dd",
            PicacgSigner.sign(path, "GET", 1_700_000_000L, nonce),
        )
        assertEquals("/api/v2/comics?q=A%2Fb&page=2&q=C%20D", path)
    }

    @Test
    fun `only the first slash is removed and root path is supported`() {
        assertEquals(
            "267ead44c3ea63f5f3ba96c3a4852725ec69b357d94b6f5c92b7e923bede96d4",
            PicacgSigner.sign("//categories?x=Y", "GET", 1_700_000_000L, nonce),
        )
        assertEquals(
            "c2dedb0a195a24d36a2712a536ad4011d054b753bc99573ddd6007fc2b5b14ef",
            PicacgSigner.sign("/", "GET", 1_700_000_000L, nonce),
        )
    }

    @Test
    fun `offline interceptor observes unchanged URL query and method with corrected signature`() = runBlocking {
        var intercepted = false
        val http = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                // Terminal application interceptor: never proceed to DNS or any socket.
                val request = chain.request()
                intercepted = true
                assertEquals("https://api.example.test/api/v2/comics?q=A%2Fb&page=2&q=C%20D", request.url.toString())
                assertEquals("GET", request.method)
                assertNull(request.body)
                assertNull(request.header("authorization"))
                assertEquals("a49eaa24346eba6640438c5b81faa8c61468f196be6c72e2e0ee815f1d6e73dd", request.header("signature"))
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("Offline fixture")
                    .body("{\"code\":200,\"data\":{}}".toResponseBody("application/json".toMediaType()))
                    .build()
            },
        ).build()
        val client = PicacgClient(
            baseUrl = { "https://api.example.test/api/v2" },
            http = http,
            epochSeconds = { 1_700_000_000L },
            nonce = { nonce },
            appUuid = "offline-fixture",
            diagnosticLog = {},
        )
        val path = "/comics?q=A%2Fb&page=2&q=C%20D"
        client.request(path, authenticated = false)
        assertEquals(true, intercepted)
        assertEquals("/comics?q=A%2Fb&page=2&q=C%20D", path)
    }
}
