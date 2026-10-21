package com.zycomic.app.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Exercises the production PicacgSpeedTester and history adapter against an interceptor-only route manager. */
class PicacgSpeedTesterRuntimeTest {
    private val routeManager: Any by lazy {
        val type = Class.forName("com.zycomic.app.net.PicacgRouteManager")
        type.getField("INSTANCE").get(null)
    }

    @BeforeEach
    fun resetRuntimeState() {
        assumeTrue(routeManager.javaClass.methods.any { it.name == "resetForRuntimeTest" }, "Run with /tmp/config-speed-picacg-runtime-tests/run.sh fake manager")
        invokeRoute("resetForRuntimeTest")
        PicacgSpeedTestHistoryStorage.saveApi(PicacgSpeedTestHistoryPolicy.PhaseHistory(null))
        PicacgSpeedTestHistoryStorage.saveImages(PicacgSpeedTestHistoryPolicy.PhaseHistory(null))
        (
            PicacgSpeedTester.javaClass.getDeclaredField("_snapshot").apply { isAccessible = true }
                .get(PicacgSpeedTester) as MutableStateFlow<PicacgSpeedTester.Snapshot?>
            ).value = null
        (
            PicacgSpeedTester.javaClass.getDeclaredField("_progress").apply { isAccessible = true }
                .get(PicacgSpeedTester) as MutableStateFlow<PicacgSpeedTester.Progress?>
            ).value = null
    }

    @Test
    fun `same-version fast API probes fixed five and refreshes selected failures without stale selection`() = runBlocking {
        val urls = (0..6).map { "https://api$it.picacg.test" }
        val ips = urls.mapIndexed { index, url -> host(url) to listOf("192.0.2.${index + 1}") }.toMap()
        configure(urls, ips, version = 7)
        val measuredAt = (0..6).associate { it to (100_000L + it) }
        val history = PicacgSpeedTestHistoryPolicy.PhaseHistory(
            7,
            urls.mapIndexed { index, url ->
                measurement(
                    url,
                    ips.getValue(host(url)).single(),
                    success = index < 6,
                    elapsed = if (index == 5) 1_000L else 10L + index,
                    measuredAt = measuredAt.getValue(index),
                )
            },
        )
        PicacgSpeedTestHistoryStorage.saveApi(history)
        setResponses((0..4).associate { "api$it.picacg.test" to 503 })

        val snapshot = PicacgSpeedTester.run(fastApi = true)

        assertEquals(emptyList<String>(), values("runtimeTestResolutions"))
        assertEquals((0..4).map { "api$it.picacg.test" }.toSet(), values("runtimeTestRequests").toSet())
        assertFalse("api5.picacg.test" in values("runtimeTestRequests"), "the sixth-ranked row is not probed")
        assertEquals(7, snapshot.apiProbes.size)
        assertEquals("api5.picacg.test", snapshot.apiProbes.first().endpoint.domain)
        assertTrue(snapshot.apiProbes.first().success, "the unselected historical success remains visible but cannot drive selection")
        assertTrue(snapshot.apiProbes.drop(1).all { !it.success })
        val progress = requireNotNull(PicacgSpeedTester.progress.value)
        assertEquals(0, progress.successful, "historical successes are not current-round progress")
        assertEquals(5, progress.failed, "progress counts only the five fresh failures")
        assertEquals(emptyList<PicacgSpeedTester.Probe>(), snapshot.imageProbes)
        assertTrue(snapshot.imageBlockedReason.orEmpty().contains("真实 Picacg"), "without an actual sample image verification is skipped")
        assertEquals(emptyList<String>(), values("runtimeTestApiSelections"), "stale success and untested sixth row must not be selected")

        val refreshed = PicacgSpeedTestHistoryStorage.loadApi()
        assertEquals(7, refreshed.measurements.size, "quick refresh must not append duplicates")
        (0..4).forEach { index ->
            val row = refreshed.measurements.single { it.identity.domain == "api$index.picacg.test" }
            assertFalse(row.success, "fresh failure must replace previous success for selected row $index")
            assertTrue(row.measuredAtEpochMs > measuredAt.getValue(index))
        }
        (5..6).forEach { index ->
            val row = refreshed.measurements.single { it.identity.domain == "api$index.picacg.test" }
            assertEquals(measuredAt.getValue(index), row.measuredAtEpochMs, "untested history retains its timestamp")
        }
        assertEquals(snapshot.apiProbes.size, snapshot.apiProbes.map { it.endpoint }.distinct().size)
        assertFalse(snapshot.complete, "no authentic image sample is configured")
    }

    @Test
    fun `newer config preserves unchanged measurement probes additions and drops removed rows`() = runBlocking {
        val oldUrls = (0..2).map { "https://old$it.picacg.test" }
        val oldIps = oldUrls.mapIndexed { index, url -> host(url) to listOf("192.0.2.${index + 11}") }.toMap()
        val retained = measurement(oldUrls[0], oldIps.getValue(host(oldUrls[0])).single(), true, 18, 123_456)
        val removedA = measurement(oldUrls[1], oldIps.getValue(host(oldUrls[1])).single(), true, 12, 123_457)
        val removedB = measurement(oldUrls[2], oldIps.getValue(host(oldUrls[2])).single(), false, 0, 123_458)
        PicacgSpeedTestHistoryStorage.saveApi(PicacgSpeedTestHistoryPolicy.PhaseHistory(10, listOf(retained, removedA, removedB)))

        val addedUrl = "https://added.picacg.test"
        val activeUrls = listOf(oldUrls[0], addedUrl)
        val activeIps = mapOf(host(oldUrls[0]) to oldIps.getValue(host(oldUrls[0])), host(addedUrl) to listOf("192.0.2.99"))
        configure(activeUrls, activeIps, version = 11)
        setResponses(mapOf("added.picacg.test" to 200))

        val snapshot = PicacgSpeedTester.run(reconcileConfig = true)

        assertEquals(listOf("added.picacg.test"), values("runtimeTestRequests"))
        assertEquals(2, snapshot.apiProbes.size, "snapshot includes the retained identity alongside the new measurement")
        val reconciled = PicacgSpeedTestHistoryStorage.loadApi()
        assertEquals(11, reconciled.remoteVersion)
        assertEquals(setOf("old0.picacg.test", "added.picacg.test"), reconciled.measurements.map { it.identity.domain }.toSet())
        assertEquals(123_456L, reconciled.measurements.single { it.identity.domain == "old0.picacg.test" }.measuredAtEpochMs)
        assertTrue(reconciled.measurements.single { it.identity.domain == "added.picacg.test" }.success)
        assertEquals(2, reconciled.measurements.size)
    }

    @Test
    fun `force fresh ignores history and reusable stage results`() = runBlocking {
        val urls = listOf("https://current0.picacg.test", "https://current1.picacg.test")
        val ips = mapOf(host(urls[0]) to listOf("192.0.2.31"), host(urls[1]) to listOf("192.0.2.32"))
        configure(urls, ips, version = 4)
        PicacgSpeedTester.run()
        assertNotNull(PicacgSpeedTester.snapshot.value, "the first run establishes reusable stage results")
        val stale = measurement("https://removed.picacg.test", "192.0.2.33", true, 1, 888_888)
        PicacgSpeedTestHistoryStorage.saveApi(PicacgSpeedTestHistoryPolicy.PhaseHistory(4, listOf(stale)))
        invokeRoute("clearRuntimeTestTraffic")

        val result = PicacgSpeedTester.run(reuseApi = true, fastApi = true, reconcileConfig = true, forceFresh = true)

        assertEquals(setOf("current0.picacg.test", "current1.picacg.test"), values("runtimeTestRequests").toSet())
        assertEquals(setOf("current0.picacg.test", "current1.picacg.test"), result.apiProbes.map { it.endpoint.domain }.toSet())
        assertEquals(setOf("current0.picacg.test", "current1.picacg.test"), PicacgSpeedTestHistoryStorage.loadApi().measurements.map { it.identity.domain }.toSet())
        assertEquals(4, PicacgSpeedTestHistoryStorage.loadApi().remoteVersion)
    }

    @Test
    fun `beforeApply revision invalidation prevents snapshot and history publication`() = runBlocking {
        val url = "https://only.picacg.test"
        configure(listOf(url), mapOf(host(url) to listOf("192.0.2.41")), version = 3)
        setResponses(mapOf("only.picacg.test" to 200))
        val old = measurement(url, "192.0.2.41", false, 0, 555_555)
        val oldHistory = PicacgSpeedTestHistoryPolicy.PhaseHistory(3, listOf(old))
        PicacgSpeedTestHistoryStorage.saveApi(oldHistory)
        var currentChecks = 0
        var checksWhenApplyInvalidated = -1

        PicacgSpeedTester.run(
            isCurrent = {
                currentChecks++
                true
            },
            beforeApply = {
                checksWhenApplyInvalidated = currentChecks
                invokeRoute("invalidateRuntimeTestRevision")
                true
            },
        )

        assertEquals(1, checksWhenApplyInvalidated, "the one earlier check guards the probe itself")
        assertEquals(2, currentChecks, "the final current-generation check runs after beforeApply")
        assertNull(PicacgSpeedTester.snapshot.value)
        assertEquals(oldHistory, PicacgSpeedTestHistoryStorage.loadApi(), "rejected generation cannot publish history")
        assertEquals(listOf("only.picacg.test"), values("runtimeTestRequests"))
    }

    private fun configure(urls: List<String>, ips: Map<String, List<String>>, version: Int) {
        invokeRoute("configureForRuntimeTest", urls, emptyList<String>(), ips, version)
    }

    private fun setResponses(codes: Map<String, Int>) = invokeRoute("setRuntimeTestResponses", codes)

    private fun measurement(url: String, ip: String, success: Boolean, elapsed: Long, measuredAt: Long) =
        PicacgSpeedTestHistoryPolicy.Measurement(
            identity = PicacgSpeedTestHistoryPolicy.Identity(
                platform = PicacgSpeedTestHistoryPolicy.PLATFORM,
                phase = PicacgSpeedTestHistoryPolicy.API_PHASE,
                url = url,
                domain = host(url),
                ip = ip,
                routeCondition = "${PicacgSpeedTestHistoryPolicy.API_ROUTE_CONDITION};ip-source=configured-rules",
                probeCondition = "GET-root;redirects-disabled;accept-non-redirect-http-status",
            ),
            success = success,
            elapsedMs = elapsed,
            measuredAtEpochMs = measuredAt,
            httpStatus = if (success) 200 else 503,
        )

    private fun host(url: String) = java.net.URI(url).host

    private fun invokeRoute(name: String, vararg args: Any?) {
        val method = routeManager.javaClass.methods.single { it.name == name && it.parameterCount == args.size }
        method.invoke(routeManager, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun values(method: String): List<String> =
        routeManager.javaClass.getMethod("get${method.replaceFirstChar(Char::uppercase)}").invoke(routeManager) as List<String>
}
