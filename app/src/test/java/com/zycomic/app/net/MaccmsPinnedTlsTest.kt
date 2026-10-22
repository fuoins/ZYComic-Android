package com.zycomic.app.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class MaccmsPinnedTlsTest {
    @Test
    fun `pinned loopback keeps each macapi URL Host SNI and rejects a trusted wrong hostname`() {
        val base = baseClient()
        MaccmsSpeedTester.routes.forEach { host ->
            val exchange = exchange(host, "macapi", base)
            assertNull(exchange.clientFailure, "$host request failed")
            assertNull(exchange.serverFailure, "$host TLS server failed")
            assertTrue(exchange.captured != null, "$host request was not received")
            val captured = exchange.captured!!
            assertEquals(200, exchange.statusCode, host)
            assertEquals("127.0.0.1", captured.localAddress, "TCP destination for $host")
            assertEquals("$host:${exchange.port}", captured.hostHeader, "HTTP Host header")
            assertEquals(host, captured.sniHost, "TLS SNI")
            assertTrue(captured.requestLine.startsWith("GET /maccms/json/baofeng/picacomic/rec/"), captured.requestLine)
        }

        val mismatch = exchange("macapi1.com", "mismatch", base)
        assertTrue(mismatch.clientFailure is SSLPeerUnverifiedException, "trusted certificate with non-matching SAN must fail hostname verification")
        assertTrue(mismatch.clientFailure?.message.orEmpty().contains("macapi1.com"))
        assertNull(mismatch.statusCode)
    }

    private fun baseClient(): OkHttpClient {
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore())
        }.trustManagers
        val trustManager = trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun exchange(host: String, alias: String, base: OkHttpClient): Exchange {
        val keyStore = fixtureKeyStore()
        val serverKeyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, PASSWORD)
            setKeyEntry(alias, keyStore.getKey(alias, PASSWORD), PASSWORD, arrayOf(keyStore.getCertificate(alias)))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(serverKeyStore, PASSWORD)
        }.keyManagers
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, null, SecureRandom())
        }
        val server = serverContext.serverSocketFactory.createServerSocket(
            0,
            10,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        server.soTimeout = SOCKET_TIMEOUT_MS
        val captured = AtomicReference<Captured?>()
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread({
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.startHandshake()
                    val session = socket.session as ExtendedSSLSession
                    val sni = session.requestedServerNames
                        .filterIsInstance<SNIHostName>()
                        .singleOrNull()?.asciiName
                    val input = BufferedReader(InputStreamReader(socket.inputStream, Charsets.ISO_8859_1))
                    val requestLine = input.readLine().orEmpty()
                    var hostHeader: String? = null
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Host:", ignoreCase = true)) hostHeader = line.substringAfter(':').trim()
                    }
                    captured.set(Captured(socket.localAddress.hostAddress, requestLine, hostHeader, sni))
                    val body = "[]"
                    socket.outputStream.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
                            .toByteArray(Charsets.ISO_8859_1),
                    )
                    socket.outputStream.flush()
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }, "maccms-pinned-tls-test-server").apply {
            isDaemon = true
            start()
        }

        val port = server.localPort
        var statusCode: Int? = null
        var clientFailure: Throwable? = null
        try {
            val client = MaccmsPinnedTransport.client(base, host, "127.0.0.1")
            assertSame(base.hostnameVerifier, client.hostnameVerifier, "helper must preserve the default hostname verifier")
            val url = "https://$host:$port/maccms/json/baofeng/picacomic/rec/0123456789abcdef01234567?limit=10".toHttpUrl()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response -> statusCode = response.code }
        } catch (failure: Throwable) {
            clientFailure = failure
        } finally {
            server.close()
            serverThread.join(JOIN_TIMEOUT_MS)
            if (serverThread.isAlive) {
                serverThread.interrupt()
                serverThread.join(JOIN_TIMEOUT_MS)
            }
        }
        assertFalse(serverThread.isAlive, "TLS server thread did not stop")
        return Exchange(port, statusCode, captured.get(), clientFailure, serverFailure.get())
    }

    private fun trustStore(): KeyStore {
        val fixture = fixtureKeyStore()
        return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            val aliases = fixture.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                setCertificateEntry(alias, fixture.getCertificate(alias))
            }
        }
    }

    private fun fixtureKeyStore(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(ByteArrayInputStream(Base64.getDecoder().decode(PKCS12_FIXTURE)), PASSWORD)
    }

    private data class Captured(
        val localAddress: String,
        val requestLine: String,
        val hostHeader: String?,
        val sniHost: String?,
    )

    private data class Exchange(
        val port: Int,
        val statusCode: Int?,
        val captured: Captured?,
        val clientFailure: Throwable?,
        val serverFailure: Throwable?,
    )

    private companion object {
        private const val SOCKET_TIMEOUT_MS = 4_000
        private const val JOIN_TIMEOUT_MS = 5_000L
        // Test-only PKCS12 with macapi SAN certificate and a deliberately mismatched SAN certificate.
        private val PKCS12_FIXTURE = """
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
        """.trimIndent().replace("\n", "")
        private val PASSWORD = "test-only-password".toCharArray()
    }
}
