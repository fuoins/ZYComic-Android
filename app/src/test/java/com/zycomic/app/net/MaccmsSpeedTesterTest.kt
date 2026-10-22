package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MaccmsSpeedTesterTest {
    @Test
    fun `full mode checks all three routes and quick mode reuses historical fastest first`() = runBlocking {
        val order = CopyOnWriteArrayList<String>()
        MaccmsSpeedTester.installProbeTransportForTests { route ->
            order += route
            val ms = when (route) {
                MaccmsSpeedTester.routes[0] -> 30L
                MaccmsSpeedTester.routes[1] -> 8L
                else -> 20L
            }
            probe(route, ms)
        }
        try {
            val full = MaccmsSpeedTester.run(quick = false)
            assertEquals(MaccmsSpeedTester.routes, order.toList())
            assertEquals(3, full.probes.size)
            assertFalse(full.quick)
            assertEquals(MaccmsSpeedTester.routes[1], full.selectedRoute)
            assertNotEquals(null, full.completedAtEpochMs)

            order.clear()
            val quick = MaccmsSpeedTester.run(quick = true)
            assertEquals(3, quick.probes.size, "Quick mode still evaluates the complete three-host pool")
            assertEquals(MaccmsSpeedTester.routes[1], order.first(), "Quick mode should reuse the prior fastest route first")
            assertTrue(quick.quick)
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `separate Maccms JSON log contains independent routes probes and history`() = runBlocking {
        MaccmsSpeedTester.installProbeTransportForTests { route -> probe(route, 12L, status = 204) }
        try {
            MaccmsSpeedTester.run(quick = false)
            val log = MaccmsSpeedTester.logJson()
            assertEquals("maccms", log.getString("service"))
            assertEquals(MaccmsSpeedTester.routes.size, log.getJSONArray("hosts").length())
            val snapshot = log.getJSONObject("snapshot")
            assertEquals(false, snapshot.getBoolean("quick"))
            assertEquals(3, snapshot.getJSONArray("probes").length())
            val firstProbe = snapshot.getJSONArray("probes").getJSONObject(0)
            assertTrue(firstProbe.has("route"))
            assertTrue(firstProbe.has("elapsedMs"))
            assertTrue(firstProbe.has("success"))
            assertTrue(firstProbe.has("httpStatus"))
            assertTrue(firstProbe.has("testedAt"))
            assertTrue(log.getJSONArray("history").length() > 0)
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `runtime telemetry is separate from the last completed HEAD probe snapshot`() = runBlocking {
        MaccmsSpeedTester.installProbeTransportForTests { route -> probe(route, 12L, status = 204) }
        try {
            MaccmsSpeedTester.run(quick = false)
            val headProbes = MaccmsSpeedTester.snapshot.value.probes
            val selection = MaccmsSpeedTester.routeSelection()
            MaccmsSpeedTester.recordRuntime(
                route = MaccmsSpeedTester.routes[2],
                latencyMs = 71,
                statusCode = 404,
                expectedGeneration = selection.generation,
            )
            assertEquals(
                headProbes,
                MaccmsSpeedTester.snapshot.value.probes,
                "Runtime telemetry must not replace the independent HEAD scan results",
            )
            val events = MaccmsSpeedTester.logJson().getJSONArray("runtimeEvents")
            assertTrue(events.length() > 0)
            assertEquals(404, events.getJSONObject(events.length() - 1).getInt("httpStatus"))
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `a valid-shaped fake comic ID 404 is reachable and does not trigger route death`() {
        val selection = MaccmsSpeedTester.routeSelection()
        val route = selection.route
        MaccmsSpeedTester.recordRuntime(
            route = route,
            latencyMs = 6,
            statusCode = 404,
            failure = IOException("Comic not found (HTTP 404)"),
            expectedGeneration = selection.generation,
        )
        val events = MaccmsSpeedTester.logJson().getJSONArray("runtimeEvents")
        val matching = events.getJSONObject(events.length() - 1)
        assertTrue(matching.getBoolean("success"), "A semantic 404 for an unknown but well-formed ID is not host failure")
        assertFalse(matching.getBoolean("recommendationSuccess"))
        assertFalse(matching.getBoolean("failover"))
        assertEquals(404, matching.getInt("httpStatus"))
        assertEquals(route, MaccmsSpeedTester.currentRoute())
    }

    @Test
    fun `all failed probes keep the previously selected route`() = runBlocking {
        MaccmsSpeedTester.installProbeTransportForTests { route -> probe(route, 10L) }
        try {
            val chosen = MaccmsSpeedTester.run(quick = false).selectedRoute
            assertTrue(chosen in MaccmsSpeedTester.routes)
            MaccmsSpeedTester.installProbeTransportForTests { route ->
                MaccmsSpeedTester.Probe(route, 25L, success = false, httpStatus = 503, testedAt = System.currentTimeMillis())
            }
            val allFailed = MaccmsSpeedTester.run(quick = false)
            assertTrue(allFailed.probes.all { !it.success })
            assertEquals(chosen, allFailed.selectedRoute)
            assertEquals(chosen, MaccmsSpeedTester.currentRoute())
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `two speed runs are serialized by a Maccms-specific run lock`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        MaccmsSpeedTester.installProbeTransportForTests { route ->
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                release.await()
            }
            probe(route, 9L)
        }
        try {
            val first = async { MaccmsSpeedTester.run(quick = false, beforeApply = { false }) }
            entered.await()
            val second = async { MaccmsSpeedTester.run(quick = false, beforeApply = { false }) }
            delay(100)
            assertEquals(3, calls.get(), "One active run may probe its three hosts concurrently, but the queued run must not start")
            assertTrue(MaccmsSpeedTester.running.value)
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(6, calls.get())
        } finally {
            if (!release.isCompleted) release.complete(Unit)
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `outdated UI round cannot apply probe results`() = runBlocking {
        val before = MaccmsSpeedTester.snapshot.value
        val beforeApplyCalls = AtomicInteger()
        MaccmsSpeedTester.installProbeTransportForTests { route -> probe(route, 1L) }
        try {
            val result = MaccmsSpeedTester.run(
                quick = false,
                isCurrent = { false },
                beforeApply = {
                    beforeApplyCalls.incrementAndGet()
                    true
                },
            )
            assertEquals(0, beforeApplyCalls.get(), "beforeApply is not called after isCurrent becomes false")
            assertEquals(before, MaccmsSpeedTester.snapshot.value)
            assertEquals(3, result.probes.size)
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `beforeApply rejection leaves the published snapshot unchanged`() = runBlocking {
        val before = MaccmsSpeedTester.snapshot.value
        val beforeApplyCalls = AtomicInteger()
        MaccmsSpeedTester.installProbeTransportForTests { route -> probe(route, 2L) }
        try {
            MaccmsSpeedTester.run(quick = false, isCurrent = { true }, beforeApply = {
                beforeApplyCalls.incrementAndGet()
                false
            })
            assertEquals(1, beforeApplyCalls.get())
            assertEquals(before, MaccmsSpeedTester.snapshot.value)
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    @Test
    fun `cancellation always clears running state`() = runBlocking {
        MaccmsSpeedTester.installProbeTransportForTests { throw kotlinx.coroutines.CancellationException("test cancellation") }
        try {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { MaccmsSpeedTester.run(quick = false) }
            }
            assertEquals("test cancellation", failure.message)
            assertFalse(MaccmsSpeedTester.running.value)
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
            assertFalse(MaccmsSpeedTester.running.value)
        }
    }

    @Test
    fun `runtime route generation change wins over an older in-flight test`() = runBlocking {
        val selection = MaccmsSpeedTester.routeSelection()
        val route = selection.route
        val beforeApplyCalls = AtomicInteger()
        lateinit var stateAfterRuntimeUpdate: MaccmsSpeedTester.Snapshot
        MaccmsSpeedTester.installProbeTransportForTests { checkedRoute ->
            if (checkedRoute == route) {
                MaccmsSpeedTester.recordRuntime(
                    route = route,
                    latencyMs = 3,
                    statusCode = 503,
                    failure = IOException("runtime server failure"),
                    failover = true,
                    expectedGeneration = selection.generation,
                )
                stateAfterRuntimeUpdate = MaccmsSpeedTester.snapshot.value
            }
            probe(checkedRoute, 1L)
        }
        try {
            val result = MaccmsSpeedTester.run(
                quick = false,
                isCurrent = { true },
                beforeApply = {
                    beforeApplyCalls.incrementAndGet()
                    true
                },
            )
            assertEquals(3, result.probes.size)
            assertEquals(1, beforeApplyCalls.get())
            assertEquals(
                stateAfterRuntimeUpdate,
                MaccmsSpeedTester.snapshot.value,
                "The stale whole-run result must not overwrite the newer runtime failover decision",
            )
            assertNotEquals(route, MaccmsSpeedTester.currentRoute())
        } finally {
            MaccmsSpeedTester.resetProbeTransportForTests()
        }
    }

    private fun probe(route: String, latency: Long, status: Int = 200) =
        MaccmsSpeedTester.Probe(route, latency, success = status in 200..299, httpStatus = status, testedAt = System.currentTimeMillis())
}
