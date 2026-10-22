package com.zycomic.app.picacg

import com.zycomic.app.net.MaccmsSpeedTester
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MaccmsRecommendationRouterTest {
    @BeforeEach
    fun establishKnownSelectedRoute() = runBlocking {
        MaccmsSpeedTester.installProbeTransportForTests { route ->
            val latency = if (route == MaccmsSpeedTester.routes.first()) 1L else 20L
            MaccmsSpeedTester.Probe(route, latency, success = true, httpStatus = 200)
        }
        try {
            val result = MaccmsSpeedTester.run(quick = false)
            assertEquals(MaccmsSpeedTester.routes.first(), result.selectedRoute)
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `5xx fails over and succeeds on next route`() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val ids = MaccmsRecommendationRouter.recommendationIds("0123456789abcdef01234567") { host, _ ->
            calls += host
            if (host == MaccmsSpeedTester.routes[0]) throw MaccmsRecommendationHttpException(503)
            listOf("abcdef0123456789abcdef01")
        }

        assertEquals(MaccmsSpeedTester.routes.take(2), calls.toList())
        assertEquals(listOf("abcdef0123456789abcdef01"), ids)
        assertEquals(MaccmsSpeedTester.routes[1], MaccmsSpeedTester.currentRoute())
    }

    @Test
    fun `ConnectException transport failure fails over`() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val ids = MaccmsRecommendationRouter.recommendationIds("0123456789abcdef01234567") { host, _ ->
            calls += host
            if (host == MaccmsSpeedTester.routes[0]) throw ConnectException("offline route")
            listOf("abcdef0123456789abcdef01")
        }

        assertEquals(MaccmsSpeedTester.routes.take(2), calls.toList())
        assertEquals(listOf("abcdef0123456789abcdef01"), ids)
        assertEquals(MaccmsSpeedTester.routes[1], MaccmsSpeedTester.currentRoute())
    }

    @Test
    fun `well shaped unknown ID 404 is one reachable request and leaves selection unchanged`() = runBlocking {
        val initialRoute = MaccmsSpeedTester.currentRoute()
        val calls = AtomicInteger()
        val failure = assertThrows(MaccmsRecommendationHttpException::class.java) {
            runBlocking {
                MaccmsRecommendationRouter.recommendationIds("0123456789abcdef01234567") { _, _ ->
                    calls.incrementAndGet()
                    throw MaccmsRecommendationHttpException(404)
                }
            }
        }

        assertEquals(404, failure.statusCode)
        assertEquals(1, calls.get())
        assertEquals(initialRoute, MaccmsSpeedTester.currentRoute())
        val event = latestRuntimeEvent()
        assertEquals(404, event.getInt("httpStatus"))
        assertTrue(event.getBoolean("success"), "HTTP 404 indicates connectivity, not a dead route")
        assertFalse(event.getBoolean("failover"))
    }

    @Test
    fun `401 403 and 429 do not fail over`() {
        for (status in listOf(401, 403, 429)) {
            val initialRoute = MaccmsSpeedTester.currentRoute()
            val calls = AtomicInteger()
            val failure = assertThrows(MaccmsRecommendationHttpException::class.java) {
                runBlocking {
                    MaccmsRecommendationRouter.recommendationIds("0123456789abcdef01234567") { _, _ ->
                        calls.incrementAndGet()
                        throw MaccmsRecommendationHttpException(status)
                    }
                }
            }

            assertEquals(status, failure.statusCode)
            assertEquals(1, calls.get(), "HTTP $status must not try another host")
            assertEquals(initialRoute, MaccmsSpeedTester.currentRoute())
            assertFalse(latestRuntimeEvent().getBoolean("failover"))
        }
    }

    @Test
    fun `JSON IOException does not fail over`() {
        val initialRoute = MaccmsSpeedTester.currentRoute()
        val calls = AtomicInteger()
        val failure = assertThrows(IOException::class.java) {
            runBlocking {
                MaccmsRecommendationRouter.recommendationIds("0123456789abcdef01234567") { _, _ ->
                    calls.incrementAndGet()
                    throw IOException("malformed JSON")
                }
            }
        }

        assertEquals("malformed JSON", failure.message)
        assertEquals(1, calls.get())
        assertEquals(initialRoute, MaccmsSpeedTester.currentRoute())
        assertFalse(latestRuntimeEvent().getBoolean("failover"))
    }

    @Test
    fun `older concurrent success cannot overwrite newer failover selection`() = runBlocking {
        val oldCallEntered = CompletableDeferred<Unit>()
        val releaseOldCall = CompletableDeferred<Unit>()
        val old = async {
            MaccmsRecommendationRouter.recommendationIds("111111111111111111111111") { host, _ ->
                assertEquals(MaccmsSpeedTester.routes[0], host)
                oldCallEntered.complete(Unit)
                releaseOldCall.await()
                listOf("aaaaaaaaaaaaaaaaaaaaaaaa")
            }
        }
        oldCallEntered.await()

        val newerIds = MaccmsRecommendationRouter.recommendationIds("222222222222222222222222") { host, _ ->
            if (host == MaccmsSpeedTester.routes[0]) throw MaccmsRecommendationHttpException(503)
            listOf("bbbbbbbbbbbbbbbbbbbbbbbb")
        }
        assertEquals(listOf("bbbbbbbbbbbbbbbbbbbbbbbb"), newerIds)
        assertEquals(MaccmsSpeedTester.routes[1], MaccmsSpeedTester.currentRoute())

        releaseOldCall.complete(Unit)
        assertEquals(listOf("aaaaaaaaaaaaaaaaaaaaaaaa"), old.await())
        assertEquals(MaccmsSpeedTester.routes[1], MaccmsSpeedTester.currentRoute())
    }

    @Test
    fun `cancellation is propagated without retry`() {
        val initialRoute = MaccmsSpeedTester.currentRoute()
        val calls = AtomicInteger()
        val cancellation = kotlinx.coroutines.CancellationException("caller cancelled")
        val thrown = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                MaccmsRecommendationRouter.recommendationIds("0123456789abcdef01234567") { _, _ ->
                    calls.incrementAndGet()
                    throw cancellation
                }
            }
        }

        assertEquals("caller cancelled", thrown.message)
        assertEquals(1, calls.get())
        assertEquals(initialRoute, MaccmsSpeedTester.currentRoute())
    }

    private fun latestRuntimeEvent(): JSONObject {
        val events = MaccmsSpeedTester.logJson().getJSONArray("runtimeEvents")
        return events.getJSONObject(events.length() - 1)
    }
}
