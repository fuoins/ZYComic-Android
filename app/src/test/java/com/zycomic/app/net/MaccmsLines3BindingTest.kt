package com.zycomic.app.net

import com.zycomic.app.picacg.MaccmsRecommendationClient
import com.zycomic.app.picacg.MaccmsRecommendationRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MaccmsLines3BindingTest {
    @Test
    fun `all hosts share the selected lines3 IP but retain independent health`() = runBlocking {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        MaccmsSpeedTester.installProbeTransportForTests { host, ip ->
            calls += host to ip
            val success = host != "macapi2.com"
            MaccmsSpeedTester.Probe(host, if (host == "macapi3.com") 1 else 10, success, if (success) 200 else 503)
        }
        try {
            val result = MaccmsSpeedTester.run(lines3Ip = "192.0.2.71")
            assertEquals(MaccmsSpeedTester.routes.map { it to "192.0.2.71" }.toSet(), calls.toSet())
            assertEquals(mapOf("macapi1.com" to true, "macapi2.com" to false, "macapi3.com" to true), result.probes.associate { it.route to it.success })
            assertEquals("macapi3.com", result.selectedRoute)
            assertEquals("192.0.2.71", result.lines3Ip)
            assertEquals("192.0.2.71", MaccmsSpeedTester.routeSelection().lines3Ip)
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `missing current-round IP clears old binding and records not tested without transport`() = runBlocking {
        MaccmsSpeedTester.installProbeTransportForTests { host, _ -> MaccmsSpeedTester.Probe(host, 1, true, 200) }
        try {
            MaccmsSpeedTester.run(lines3Ip = "192.0.2.72")
            MaccmsSpeedTester.installProbeTransportForTests { _, _ -> throw AssertionError("No-IP round must never probe or resolve a host") }
            listOf(null, "", "macapi1.com", "999.0.0.1").forEach { ip ->
                val result = MaccmsSpeedTester.run(lines3Ip = ip)
                assertEquals(3, result.probes.size)
                assertTrue(result.probes.all { !it.tested && !it.success && it.elapsedMs == null && it.httpStatus == null })
                assertNull(result.lines3Ip)
                assertNull(MaccmsSpeedTester.routeSelection().lines3Ip)
                assertThrows(IOException::class.java) {
                    runBlocking { MaccmsRecommendationRouter.recommendationIds("111111111111111111111111") }
                }
            }
            val log = MaccmsSpeedTester.logJson().getJSONObject("snapshot")
            assertTrue(log.isNull("lines3Ip"))
            assertFalse(log.getJSONArray("probes").getJSONObject(0).getBoolean("tested"))
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `new round invalidation prevents older probes and recommendation telemetry from restoring an IP`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        MaccmsSpeedTester.installProbeTransportForTests { host, _ ->
            entered.complete(Unit)
            release.await()
            MaccmsSpeedTester.Probe(host, 1, true, 200)
        }
        try {
            val before = MaccmsSpeedTester.snapshot.value
            val oldSelection = MaccmsSpeedTester.routeSelection()
            val pending = async { MaccmsSpeedTester.run(lines3Ip = "192.0.2.73") }
            entered.await()
            MaccmsSpeedTester.invalidateLines3Ip()
            release.complete(Unit)
            pending.await()
            assertEquals(before, MaccmsSpeedTester.snapshot.value)
            assertNull(MaccmsSpeedTester.routeSelection().lines3Ip)
            assertNull(MaccmsSpeedTester.recordRuntime(oldSelection.route, 1, expectedGeneration = oldSelection.generation))
            assertNull(MaccmsSpeedTester.routeSelection().lines3Ip)
        } finally {
            release.complete(Unit)
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `cancellation while awaiting commit does not publish an IP or snapshot`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        MaccmsSpeedTester.invalidateLines3Ip()
        val before = MaccmsSpeedTester.snapshot.value
        MaccmsSpeedTester.installProbeTransportForTests { host, _ -> MaccmsSpeedTester.Probe(host, 1, true, 200) }
        try {
            val pending = async {
                MaccmsSpeedTester.run(lines3Ip = "192.0.2.74", beforeApply = {
                    entered.complete(Unit)
                    release.await()
                    true
                })
            }
            entered.await()
            pending.cancelAndJoin()
            assertEquals(before, MaccmsSpeedTester.snapshot.value)
            assertNull(MaccmsSpeedTester.routeSelection().lines3Ip)
            assertFalse(MaccmsSpeedTester.running.value)
        } finally {
            release.complete(Unit)
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `recommendation cancellation closes socket before headers and during body reads`() = runBlocking {
        listOf(false, true).forEach { sendPartialBody ->
            val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val executor = Executors.newSingleThreadExecutor()
            val entered = CompletableDeferred<Unit>()
            val peerClosed = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    if (sendPartialBody) {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n[".toByteArray())
                        socket.getOutputStream().flush()
                    }
                    entered.complete(Unit)
                    reader.read() == -1
                }
            }
            try {
                val client = MaccmsRecommendationClient("http://macapi1.com:${server.localPort}/".toHttpUrl(), "127.0.0.1")
                val request = async { client.recommendationIds("111111111111111111111111") }
                entered.await()
                request.cancelAndJoin()
                assertTrue(peerClosed.get(3, TimeUnit.SECONDS), "Cancellation must close the actual TCP connection")
            } finally {
                server.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `recommendation TCP uses captured IP and keeps each macapi URL and Host`() {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse.Builder()
                    .code(200)
                    .body("[\"aaaaaaaaaaaaaaaaaaaaaaaa\"]")
                    .build()
            }
        }
        server.start()
        try {
            MaccmsSpeedTester.routes.forEach { host ->
                val client = MaccmsRecommendationClient("http://$host:${server.port}/".toHttpUrl(), "127.0.0.1")
                val result = runBlocking { client.recommendationIds("111111111111111111111111") }
                assertEquals(listOf("aaaaaaaaaaaaaaaaaaaaaaaa"), result)
            }
            assertEquals(
                MaccmsSpeedTester.routes.map { "$it:${server.port}" },
                requests.map { it.headers["Host"] },
            )
            assertEquals(
                List(MaccmsSpeedTester.routes.size) { "/picacomic/rec/111111111111111111111111?limit=10" },
                requests.map { it.target },
            )
        } finally {
            server.close()
        }
    }
}
