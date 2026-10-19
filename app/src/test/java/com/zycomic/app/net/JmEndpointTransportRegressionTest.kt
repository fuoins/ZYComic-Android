package com.zycomic.app.net

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JmEndpointTransportRegressionTest {
    @Test
    fun `DNS and failure attribution stay pinned when selected endpoint changes during request`() {
        val server = server(200)
        val selected = AtomicReference(DomainIp("race.local", "127.0.0.1"))
        val endpointReads = AtomicInteger()
        val failure = AtomicReference<DomainIp>()
        val dns = JmNetworkModule.singleAddressDns(
            endpoint = {
                endpointReads.incrementAndGet()
                selected.get()
            },
            configuredIps = { listOf("127.0.0.2") },
            measuredResults = { true },
            image = false,
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .retryOnConnectionFailure(false)
            .addInterceptor(
                JmEndpointTransportInterceptor(
                    prepareApiRoute = { selected.set(DomainIp(it, "127.0.0.2")) },
                    prepareImageRoute = {},
                    apiEndpoint = {
                        endpointReads.incrementAndGet()
                        selected.get()
                    },
                    apiFailure = { endpoint, _ -> failure.set(endpoint) },
                    apiHasMeasurements = { true },
                ),
            )
            .addNetworkInterceptor { throw ConnectException("offline-test connection failure") }
            .build()
        try {
            assertThrows(ConnectException::class.java) {
                client.newCall(Request.Builder().url("http://race.local:${server.port}/").build()).execute().close()
            }
            assertEquals(1, endpointReads.get(), "DNS must not re-read the endpoint after the request pin")
            assertEquals(DomainIp("race.local", "127.0.0.1"), failure.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `failed bootstrap is attributed to actual DNS IP but is not persisted`() {
        val persisted = AtomicInteger()
        val failure = AtomicReference<DomainIp>()
        val dns = JmNetworkModule.singleAddressDns(
            endpoint = { null },
            configuredIps = { listOf("127.0.0.2") },
            measuredResults = { false },
            image = false,
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                JmEndpointTransportInterceptor(
                    prepareApiRoute = {},
                    prepareImageRoute = {},
                    apiEndpoint = { null },
                    apiHasMeasurements = { false },
                    apiSuccess = { persisted.incrementAndGet() },
                    apiFailure = { endpoint, _ -> failure.set(endpoint) },
                ),
            )
            .build()
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("http://bootstrap.local:1/").build()).execute().close()
        }
        assertEquals(DomainIp("bootstrap.local", "127.0.0.2"), failure.get())
        assertEquals(0, persisted.get(), "DNS/bootstrap lookup alone must not persist an endpoint")
    }

    @Test
    fun `successful bootstrap is persisted only after response succeeds`() {
        val server = server(200)
        val persisted = AtomicReference<DomainIp>()
        val dns = JmNetworkModule.singleAddressDns(
            endpoint = { DomainIp(it, "127.0.0.2") },
            configuredIps = { listOf("127.0.0.2") },
            measuredResults = { false },
            image = false,
        )
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(
                JmEndpointTransportInterceptor(
                    prepareApiRoute = {},
                    prepareImageRoute = {},
                    apiEndpoint = { DomainIp(it, "127.0.0.1") },
                    apiHasMeasurements = { false },
                    apiSuccess = { persisted.set(it) },
                ),
            )
            .build()
        try {
            client.newCall(Request.Builder().url("http://bootstrap.local:${server.port}/").build())
                .execute().use { assertEquals(200, it.code) }
            assertEquals(DomainIp("bootstrap.local", "127.0.0.1"), persisted.get())
        } finally {
            server.close()
        }
    }

    private fun server(code: Int): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(code)
                .body("offline-test")
                .build()
        }
        start()
    }
}
