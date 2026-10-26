package com.zycomic.app.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Interceptors return in-memory responses: these tests never resolve DNS or open a socket. */
class PicacgApiProbeResponseTest {
    @Test
    fun `API probe rejects redirects without following or rewriting their target`() {
        listOf(301, 302, 303, 307, 308).forEach { status ->
            var requests = 0
            val client = responseClient(status) { requests++ }
            val result = probe("https://picaapi.picacomic.com/", client)
            assertFalse(result.success, "HTTP $status must not pass")
            assertEquals(status, result.httpStatus)
            assertEquals(1, requests)
        }
    }

    @Test
    fun `public website is rejected before sending a probe`() {
        var requests = 0
        val result = probe("https://picacomic.com/", responseClient(200) { requests++ })
        assertFalse(result.success)
        assertEquals(null, result.httpStatus)
        assertEquals(0, requests)
    }

    @Test
    fun `documented API probe preserves hostname while accepting non-redirect reachability`() {
        listOf(200, 401, 403, 404).forEach { status ->
            val result = probe("https://picaapi.picacomic.com/", responseClient(status))
            assertTrue(result.success)
            assertEquals(status, result.httpStatus)
            assertEquals("picaapi.picacomic.com", result.endpoint.domain)
            assertEquals("192.0.2.10", result.endpoint.ip)
        }
    }

    private fun responseClient(status: Int, onRequest: () -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            onRequest()
            assertEquals("picaapi.picacomic.com", chain.request().url.host)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Offline response")
                .header("Location", "https://other.invalid/must-not-follow")
                .body("".toResponseBody())
                .build()
        }
        .build()

    private fun probe(base: String, client: OkHttpClient): PicacgSpeedTester.Probe {
        // probeApi 增加了可选的 ownerJob 参数；Kotlin 默认参数不会生成三参数重载，
        // 反射必须按完整签名查找，否则 NoSuchMethodException。
        val method = PicacgSpeedTester::class.java.getDeclaredMethod(
            "probeApi",
            String::class.java,
            DomainIp::class.java,
            OkHttpClient::class.java,
            kotlinx.coroutines.Job::class.java,
        ).apply { isAccessible = true }
        return method.invoke(
            PicacgSpeedTester,
            base,
            DomainIp(java.net.URI(base).host, "192.0.2.10"),
            client,
            null,
        ) as PicacgSpeedTester.Probe
    }
}
