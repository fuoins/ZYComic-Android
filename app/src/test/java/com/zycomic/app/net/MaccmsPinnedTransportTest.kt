package com.zycomic.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.UnknownHostException

class MaccmsPinnedTransportTest {
    @Test
    fun `each macapi DNS returns only its configured numeric address and rejects other queries`() {
        val ip = "203.0.113.17"

        MaccmsSpeedTester.routes.forEach { host ->
            val dns = MaccmsPinnedTransport.dns(host, ip)

            assertEquals(listOf(ip), dns.lookup(host).map { it.hostAddress }, host)
            MaccmsSpeedTester.routes.filterNot { it == host }.forEach { otherHost ->
                assertThrows(UnknownHostException::class.java) {
                    dns.lookup(otherHost)
                }
            }
            assertThrows(UnknownHostException::class.java) {
                dns.lookup("unrelated.example")
            }
        }
    }

    @Test
    fun `missing or invalid pinned address fails without falling back to system DNS`() {
        val hosts = MaccmsSpeedTester.routes
        val base = OkHttpClient.Builder()
            .dns { requestedHost -> throw AssertionError("Base/system DNS was called for $requestedHost") }
            .build()

        listOf(null, "", "macapi2.com", "256.1.1.1", "192.0.2.1%eth0").forEach { ip ->
            hosts.forEach { host ->
                val client = MaccmsPinnedTransport.client(base, host, ip)
                assertThrows(UnknownHostException::class.java) {
                    client.dns.lookup(host)
                }
            }
        }
    }

    @Test
    fun `client preserves TLS verifier and socket factory and request hostname semantics`() {
        val base = OkHttpClient()
        val client = MaccmsPinnedTransport.client(base, "macapi1.com", "203.0.113.17")
        val request = Request.Builder()
            .url("https://macapi1.com/recommendation?source=test")
            .build()

        assertSame(base.hostnameVerifier, client.hostnameVerifier)
        assertSame(base.sslSocketFactory, client.sslSocketFactory)
        assertEquals("macapi1.com", request.url.host)
        // No explicit Host override: OkHttp derives Host from the unchanged request URL.
        assertNull(request.header("Host"))
    }
}
