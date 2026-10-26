package com.zycomic.app.net

import com.zycomic.routecache.MemoryContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

class ManwaTwoStageSpeedTesterRuntimeTest {
    private data class Routes(val lines: List<String>, val images: List<String>) {
        val lineHosts get() = lines.map { it.substringAfter("https://") }
        val allHosts get() = lineHosts + images
    }

    private fun configure(label: String, lineCount: Int = 4, imageCount: Int = 4): Routes {
        DevConfig.appContext = MemoryContext()
        RouteManager.setAutoSelectEnabled(true)
        RouteManager.clearPinnedImgDomain()
        RouteManager.updateUseFastestImgForAll(true)
        val lines = (0 until lineCount).map { "https://$label-line$it.test" }
        val images = (0 until imageCount).map { "$label-img$it.test" }
        RouteManager.applyBaseLists(lines, images, remoteVersion = 9101)
        RouteManager.setCustomRule(
            (lines.mapIndexed { index, url -> url.substringAfter("https://") to listOf("192.0.2.${index + 1}") } +
                images.mapIndexed { index, domain -> domain to listOf("198.51.100.${index + 1}") })
                .toMap(),
        )
        ManwaProbeFixture.reset()
        return Routes(lines, images)
    }

    private fun lineResults(routes: Routes): Int = routes.lineHosts.count { host ->
        ManwaProbeFixture.completed.contains(host to "/api/users/info")
    }

    private suspend fun assertEarlyReadyAndDrain(quick: Boolean) = coroutineScope {
        val routes = configure(if (quick) "quick" else "full")
        ManwaProbeFixture.delaysByDomain = routes.allHosts.mapIndexed { index, host ->
            host to if (index == 0 || index == routes.lines.size) 5L else 700L
        }.toMap()
        val ready = CompletableDeferred<Unit>()
        val qualified = CompletableDeferred<Unit>()
        val completionObserved = AtomicBoolean(false)
        val maxLineCount = AtomicInteger()
        val maxImageCount = AtomicInteger()
        val run = async(Dispatchers.Default) {
            SpeedTester.runAndApply(
                onProgress = { lines, images, _ ->
                    maxLineCount.updateAndGet { maxOf(it, lines.size) }
                    maxImageCount.updateAndGet { maxOf(it, images.size) }
                },
                onRouteReady = { ready.complete(Unit) },
                onQualifiedEndpoint = { qualified.complete(Unit) },
                onStageProgress = { if (it.phase == SpeedTester.Phase.COMPLETE) completionObserved.set(true) },
                reuseLines = quick,
                allowImageReuse = quick,
            )
        }
        try {
            withTimeout(2_500L) { ready.await() }
        } catch (error: Throwable) {
            throw AssertionError(
                "ready timeout: started=${ManwaProbeFixture.started}, completed=${ManwaProbeFixture.completed}, " +
                    "selectedLine=${RouteManager.selectedLineIp}, selectedImage=${RouteManager.selectedImgIp}",
                error,
            )
        }
        withTimeout(2_500L) { qualified.await() }
        assertTrue(SpeedTester.isRunning, "qualified publication must precede the exhaustive tail drain")
        assertFalse(completionObserved.get(), "qualified publication must not complete a full run")
        assertTrue(SpeedTester.isRunning, "the stage-one route callback must precede the tail drain")
        assertFalse(completionObserved.get(), "COMPLETE must not be published while the tail is in flight")
        assertTrue(ManwaProbeFixture.active.get() > 0, "slow tail probes should still be active at ready publication")
        run.await()
        assertTrue(completionObserved.get(), "the exhaustive run must eventually complete")
        assertEquals(routes.lines.size, maxLineCount.get(), "all configured line IPs must contribute results")
        assertEquals(routes.images.size, maxImageCount.get(), "all configured image IPs must contribute results")
        assertEquals(routes.lines.size, lineResults(routes))
        assertEquals(0, ManwaProbeFixture.active.get(), "COMPLETE follows all endpoint probes")
    }

    @Test
    fun quickRunPublishesReadyBeforeSlowTailAndDrainsAllEndpoints() = runBlocking {
        assertEarlyReadyAndDrain(quick = true)
    }

    @Test
    fun fullRunPublishesReadyBeforeSlowTailAndDrainsAllEndpoints() = runBlocking {
        assertEarlyReadyAndDrain(quick = false)
    }

    @Test
    fun laterBetterLineAutomaticallyReplacesTheInitialUsableLine() = runBlocking {
        val routes = configure("improve", lineCount = 6, imageCount = 1)
        // The initial concurrency window contains three peers. The genuinely faster
        // candidate belongs to the background tail, not a simultaneous first batch.
        ManwaProbeFixture.delaysByDomain = routes.lineHosts.mapIndexed { index, host ->
            host to when (index) {
                0 -> 100L
                1, 2 -> 400L
                else -> 8L
            }
        }.toMap()
        val dispatchedLines = CopyOnWriteArrayList<String>()
        ManwaProbeFixture.onRequestStart = { domain, path ->
            if (path == "/api/users/info") {
                dispatchedLines += domain
            }
        }
        val ready = CompletableDeferred<String>()
        val measuredLineDelays = ConcurrentHashMap<String, Long>()
        val run = async(Dispatchers.Default) {
            SpeedTester.runAndApply(
                onProgress = { lines, _, _ -> lines.forEach { (endpoint, delay) -> measuredLineDelays[endpoint.domain] = delay } },
                onRouteReady = { ready.complete(RouteManager.lineHost) },
                reuseLines = true,
                allowImageReuse = true,
            )
        }
        val initialRoute = withTimeout(2_500L) { ready.await() }
        // 24 并发下首个完成的可用候选不一定是配置第一条，只要它是本轮真实可用端点即可。
        assertTrue(initialRoute in routes.lineHosts, "a usable candidate publishes readiness")
        run.await()
        assertEquals(routes.lineHosts[3], RouteManager.lineHost, "the fastest candidate wins after the round")
        assertEquals(6, measuredLineDelays.size)
        assertEquals(measuredLineDelays.values.min(), measuredLineDelays.getValue(RouteManager.lineHost))
        assertEquals("192.0.2.${routes.lineHosts.indexOf(RouteManager.lineHost) + 1}", RouteManager.selectedLineIp)
    }

    @Test
    fun manualEndpointOverridesAreNotChangedByEarlyOrFinalAutomaticResults() = runBlocking {
        val routes = configure("manual", lineCount = 3, imageCount = 1)
        RouteManager.setLineEndpoint(routes.lineHosts[1], "203.0.113.99")
        RouteManager.pinImgHost(0, "203.0.113.98")
        ManwaProbeFixture.delaysByDomain = mapOf(routes.lineHosts[0] to 5L, routes.lineHosts[2] to 8L)
        var qualified = false
        SpeedTester.runAndApply(
            reuseLines = true,
            allowImageReuse = true,
            onQualifiedEndpoint = { qualified = true },
        )
        assertEquals(routes.lineHosts[1], RouteManager.lineHost)
        assertEquals("203.0.113.99", RouteManager.selectedLineIp)
        assertEquals("203.0.113.98", RouteManager.selectedImgIp)
        assertFalse(qualified, "successful endpoints conflicting with both manual pins must not qualify")
    }

    @Test
    fun sampleLookupWaitsForUsableLineRatherThanEveryLineTail() = runBlocking {
        val routes = configure("sample", lineCount = 4, imageCount = 2)
        ManwaProbeFixture.invalidFixedImages = true
        ManwaProbeFixture.samplePath = "/fallback-sample.jpg"
        ManwaProbeFixture.delaysByDomain = mapOf(routes.lineHosts[0] to 5L) + routes.lineHosts.drop(1).associateWith { 900L }
        val sampleSawFreshLine = AtomicBoolean(false)
        val sampleSawLineTail = AtomicBoolean(false)
        ManwaProbeFixture.onSampleIndex = {
            sampleSawFreshLine.set(!RouteManager.selectedLineIp.isNullOrBlank())
            sampleSawLineTail.set(routes.lineHosts.drop(1).all { host ->
                ManwaProbeFixture.completed.any { it.first == host && it.second == "/api/users/info" }
            })
        }
        SpeedTester.runAndApply()
        assertTrue(sampleSawFreshLine.get(), "sample lookup starts only after a fresh selected line is ready")
        assertFalse(sampleSawLineTail.get(), "sample lookup should not wait for all unrelated line tails")
        routes.images.forEach { domain ->
            assertTrue(ManwaProbeFixture.completed.contains(domain to "/fallback-sample.jpg"), "fallback sample probed on $domain")
        }
    }

    @Test
    fun staleGenerationCannotApplyAutomaticRouteResults() = runBlocking {
        val routes = configure("stale", lineCount = 3, imageCount = 1)
        val initialHost = RouteManager.lineHost
        val generationCurrent = AtomicBoolean(true)
        val qualified = AtomicBoolean(false)
        // 第一个探测请求发出后就判定该轮过期：验证过期轮次不会应用路由或放行启动门。
        // 中途接管的场景由 StartupCoordinatorRuntimeTest 的 supersede 用例覆盖。
        ManwaProbeFixture.onRequestStart = { _, _ -> generationCurrent.set(false) }
        SpeedTester.runAndApply(
            isCurrent = { generationCurrent.get() },
            onQualifiedEndpoint = { qualified.set(true) },
        )
        assertEquals(initialHost, RouteManager.lineHost)
        assertTrue(RouteManager.selectedLineIp.isNullOrBlank(), "stale run must not pin an endpoint")
        assertFalse(qualified.get(), "a stale generation must not release the startup gate")
        assertNotNull(ManwaProbeFixture.started.firstOrNull(), "the stale round must still have probed before expiring")
    }
    @Test
    fun imageEndpointCanQualifyWhenManualLineEndpointDoesNotMatchProbe() = runBlocking {
        val routes = configure("image-only", lineCount = 1, imageCount = 1)
        RouteManager.setLineEndpoint(routes.lineHosts.single(), "203.0.113.77")
        val qualified = AtomicBoolean(false)
        SpeedTester.runAndApply(onQualifiedEndpoint = { qualified.set(true) })
        assertEquals("203.0.113.77", RouteManager.selectedLineIp)
        assertTrue(qualified.get(), "the real, applied image endpoint should release startup even when line pin mismatches; domain=${RouteManager.imgRewriteTarget ?: RouteManager.imgHost} ip=${RouteManager.selectedImgIp} fastest=${RouteManager.fastestImgEndpoint}")
    }

    @Test
    fun cancelledProbeClosesItsOwnBlockedSocketWithoutWaitingForReadTimeout() = runBlocking {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val releaseServer = java.util.concurrent.CountDownLatch(1)
        val bodyReadStarted = CompletableDeferred<Unit>()
        val serverThread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\nx".toByteArray())
                        flush()
                    }
                    releaseServer.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }.apply { isDaemon = true; start() }
        val client = okhttp3.OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        val call = client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/probe").build())
        val probe = async(Dispatchers.IO) {
            SpeedTestProbeCall.execute(call, kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]) { response ->
                bodyReadStarted.complete(Unit)
                response.body!!.string()
            }
        }
        try {
            withTimeout(5_000) { bodyReadStarted.await() }
            withTimeout(2_000) {
                probe.cancel()
                probe.join()
            }
            assertTrue(call.isCanceled(), "retiring the probe must close only its own Call immediately")
        } finally {
            probe.cancel()
            releaseServer.countDown()
            server.close()
            serverThread.join(2_000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

}
