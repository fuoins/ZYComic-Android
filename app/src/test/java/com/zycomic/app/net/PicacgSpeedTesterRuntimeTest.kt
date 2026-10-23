package com.zycomic.app.net

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun `same-version fast API explores beyond five until two fresh quick successes`() = runBlocking {
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
        val published = mutableListOf<DomainIp?>()

        val snapshot = PicacgSpeedTester.run(fastApi = true, onApiPublished = published::add)

        assertEquals((0..6).map { "api$it.picacg.test" }.toSet(), values("runtimeTestResolutions").toSet())
        assertEquals((0..6).map { "api$it.picacg.test" }.toSet(), values("runtimeTestRequests").toSet())
        assertEquals(7, snapshot.apiProbes.size)
        assertTrue(snapshot.apiProbes.all { it.testedThisRound }, "all candidates up to two quick successes must be fresh measurements")
        assertEquals(setOf("api5.picacg.test", "api6.picacg.test"), snapshot.apiProbes.filter { it.success }.map { it.endpoint.domain }.toSet())
        val progress = requireNotNull(PicacgSpeedTester.progress.value)
        assertEquals(2, progress.successful)
        assertEquals(5, progress.failed)
        assertEquals(emptyList<PicacgSpeedTester.Probe>(), snapshot.imageProbes)
        assertTrue(snapshot.imageBlockedReason.orEmpty().contains("正文图源"), "with no configured content source the image stage is blocked")
        assertEquals(1, values("runtimeTestApiSelections").size)
        assertTrue(values("runtimeTestApiSelections").single() in setOf("api5.picacg.test", "api6.picacg.test"))
        assertTrue(published.isNotEmpty())
        assertTrue(published.all { it?.domain in setOf("api5.picacg.test", "api6.picacg.test") })
        assertEquals(snapshot.roundSelectedLine, published.last())

        val refreshed = PicacgSpeedTestHistoryStorage.loadApi()
        assertEquals(7, refreshed.measurements.size, "quick refresh must not append duplicates")
        (0..4).forEach { index ->
            val row = refreshed.measurements.single { it.identity.domain == "api$index.picacg.test" }
            assertFalse(row.success, "fresh failure must replace previous success for row $index")
            assertTrue(row.measuredAtEpochMs > measuredAt.getValue(index))
        }
        (5..6).forEach { index ->
            val row = refreshed.measurements.single { it.identity.domain == "api$index.picacg.test" }
            assertTrue(row.success)
            assertTrue(row.measuredAtEpochMs > measuredAt.getValue(index), "quick probes refresh both successful rows")
        }
        assertEquals(snapshot.apiProbes.size, snapshot.apiProbes.map { it.endpoint }.distinct().size)
        assertFalse(snapshot.complete, "with no configured content source the image stage is incomplete")
    }

    @Test
    fun `full API pass probes every current configured endpoint beyond five`() = runBlocking {
        val urls = (0..7).map { "https://full$it.picacg.test" }
        val ips = urls.mapIndexed { index, url -> host(url) to listOf("192.0.2.${index + 70}") }.toMap()
        configure(urls, ips, version = 9)
        val published = mutableListOf<DomainIp?>()

        val snapshot = PicacgSpeedTester.run(onApiPublished = published::add)

        val expected = (0..7).map { "full$it.picacg.test" }.toSet()
        assertEquals(expected, values("runtimeTestRequests").toSet())
        assertEquals(expected, snapshot.apiProbes.map { it.endpoint.domain }.toSet())
        assertTrue(snapshot.apiProbes.all { it.testedThisRound })
        assertTrue(published.isNotEmpty())
        assertNotNull(published.last())
        assertEquals(snapshot.roundSelectedLine, published.last())
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

        assertEquals(setOf("old0.picacg.test", "added.picacg.test"), values("runtimeTestRequests").toSet(), "reconcile still measures every active endpoint")
        assertEquals(2, snapshot.apiProbes.size, "snapshot includes the retained identity alongside the new measurement")
        val reconciled = PicacgSpeedTestHistoryStorage.loadApi()
        assertEquals(11, reconciled.remoteVersion)
        assertEquals(setOf("old0.picacg.test", "added.picacg.test"), reconciled.measurements.map { it.identity.domain }.toSet())
        assertTrue(reconciled.measurements.single { it.identity.domain == "old0.picacg.test" }.measuredAtEpochMs > 123_456L)
        assertTrue(reconciled.measurements.single { it.identity.domain == "added.picacg.test" }.success)
        assertEquals(2, reconciled.measurements.size)
        val fastestFresh = snapshot.apiProbes.filter { it.testedThisRound && it.success }
            .minWithOrNull(compareBy({ it.elapsedMs }, { it.endpoint.domain }, { it.endpoint.ip }))
        assertEquals(fastestFresh?.endpoint, PicacgRouteManager.selectedApiEndpoint, "selection follows measured latency, not interceptor scheduling order")
    }

    @Test
    fun `zero additions revalidates selected matching history before image stage`() = runBlocking {
        val url = "https://steady.picacg.test"
        val ip = "192.0.2.101"
        configure(listOf(url), mapOf(host(url) to listOf(ip)), version = 12)
        PicacgSpeedTestHistoryStorage.saveApi(
            PicacgSpeedTestHistoryPolicy.PhaseHistory(11, listOf(measurement(url, ip, true, 4, 123_500))),
        )
        setResponses(mapOf("steady.picacg.test" to 200))
        val events = mutableListOf<String>()
        val published = mutableListOf<DomainIp?>()

        val snapshot = PicacgSpeedTester.run(
            reconcileConfig = true,
            onApiPublished = {
                events += "published"
                published += it
            },
            onProgress = { if (it.stage == PicacgSpeedTester.Stage.PREPARING_IMAGES) events += "images" },
        )

        val selected = DomainIp("steady.picacg.test", ip)
        assertEquals(listOf("steady.picacg.test"), values("runtimeTestRequests"))
        assertEquals(listOf(selected), published)
        assertTrue(events.contains("published"))
        assertTrue(events.contains("images"), "API and image phases now progress independently")
        assertEquals(listOf(selected), snapshot.apiProbes.filter { it.testedThisRound && it.success }.map { it.endpoint })
        assertTrue(snapshot.apiProbes.single().testedThisRound)
        assertTrue(PicacgSpeedTestHistoryStorage.loadApi().measurements.single().measuredAtEpochMs > 123_500)
    }

    @Test
    fun `removed historical endpoint is neither verified nor bound`() = runBlocking {
        val retainedUrl = "https://retained.picacg.test"
        val deletedUrl = "https://deleted.picacg.test"
        val retainedIp = "192.0.2.111"
        val deletedIp = "192.0.2.112"
        PicacgSpeedTestHistoryStorage.saveApi(
            PicacgSpeedTestHistoryPolicy.PhaseHistory(
                20,
                listOf(
                    measurement(retainedUrl, retainedIp, true, 20, 123_510),
                    measurement(deletedUrl, deletedIp, true, 1, 123_511),
                ),
            ),
        )
        configure(listOf(retainedUrl), mapOf(host(retainedUrl) to listOf(retainedIp)), version = 21)
        setResponses(mapOf("retained.picacg.test" to 200))
        val published = mutableListOf<DomainIp?>()

        val snapshot = PicacgSpeedTester.run(reconcileConfig = true, onApiPublished = published::add)

        assertEquals(listOf("retained.picacg.test"), values("runtimeTestRequests"))
        assertEquals(listOf(DomainIp("retained.picacg.test", retainedIp)), published)
        assertFalse(snapshot.apiProbes.any { it.endpoint.domain == "deleted.picacg.test" })
        assertEquals(setOf("retained.picacg.test"), PicacgSpeedTestHistoryStorage.loadApi().measurements.map { it.identity.domain }.toSet())
    }

    @Test
    fun `failed selected historical verification does not fallback and records fresh failure`() = runBlocking {
        val alternativeUrl = "https://faster.picacg.test"
        val pinnedUrl = "https://pinned.picacg.test"
        val alternativeIp = "192.0.2.120"
        val pinnedIp = "192.0.2.121"
        configure(
            listOf(alternativeUrl, pinnedUrl),
            mapOf(host(alternativeUrl) to listOf(alternativeIp), host(pinnedUrl) to listOf(pinnedIp)),
            version = 31,
        )
        val selected = DomainIp(host(pinnedUrl), pinnedIp)
        invokeRoute("applyAutomaticApiEndpoint", selected)
        routeManager.javaClass.getMethod("setApiManuallyPinned", Boolean::class.javaPrimitiveType).invoke(routeManager, true)
        val oldAlternative = measurement(alternativeUrl, alternativeIp, true, 1, 123_519)
        val oldPinned = measurement(pinnedUrl, pinnedIp, true, 2, 123_520)
        PicacgSpeedTestHistoryStorage.saveApi(PicacgSpeedTestHistoryPolicy.PhaseHistory(30, listOf(oldAlternative, oldPinned)))
        setResponses(mapOf("pinned.picacg.test" to 503))
        val published = mutableListOf<DomainIp?>()

        val snapshot = PicacgSpeedTester.run(reconcileConfig = true, onApiPublished = published::add)

        assertEquals(setOf("pinned.picacg.test", "faster.picacg.test"), values("runtimeTestRequests").toSet(), "reconciliation explores every current candidate while preserving the manual pin")
        assertTrue(published.singleOrNull() == null, "a failed verification publishes null, never a fallback")
        assertNull(snapshot.roundSelectedLine)
        val current = snapshot.apiProbes.single { it.endpoint == selected }
        assertFalse(current.success)
        assertTrue(current.testedThisRound)
        assertEquals(503, current.httpStatus)
        assertEquals(setOf("faster.picacg.test", "pinned.picacg.test"), values("runtimeTestResolutions").toSet())
        assertTrue(values("runtimeTestApiSelections").isEmpty(), "the failed pinned endpoint cannot switch API host")
        assertEquals(selected, PicacgRouteManager.selectedApiEndpoint, "the probe never binds a different endpoint")
        val storedRows = PicacgSpeedTestHistoryStorage.loadApi().measurements
        assertEquals(2, storedRows.size)
        assertTrue(storedRows.single { it.identity.domain == "faster.picacg.test" }.success, "the alternative is measured, but a manual pin prevents automatic publication")
        val stored = storedRows.single { it.identity.domain == "pinned.picacg.test" }
        assertFalse(stored.success)
        assertEquals(503, stored.httpStatus)
        assertTrue(stored.measuredAtEpochMs > oldPinned.measuredAtEpochMs)
    }

    @Test
    fun `early quick improvements never replace a manually pinned API endpoint`() = runBlocking {
        val automatic = "https://manual-a.picacg.test"
        val pinned = "https://manual-b.picacg.test"
        val firstIp = "192.0.2.124"
        val pinnedIp = "192.0.2.125"
        configure(
            listOf(automatic, pinned),
            mapOf(host(automatic) to listOf(firstIp), host(pinned) to listOf(pinnedIp)),
            version = 32,
        )
        val selected = DomainIp(host(pinned), pinnedIp)
        invokeRoute("applyAutomaticApiEndpoint", selected)
        routeManager.javaClass.getMethod("setApiManuallyPinned", Boolean::class.javaPrimitiveType).invoke(routeManager, true)
        val published = mutableListOf<DomainIp?>()

        val snapshot = PicacgSpeedTester.run(fastApi = true, onApiPublished = published::add)

        assertEquals(selected, PicacgRouteManager.selectedApiEndpoint)
        assertEquals(selected, snapshot.roundSelectedLine)
        assertTrue(published.all { it == selected }, "non-pinned valid alternatives cannot be published over the manual route")
        assertEquals(setOf("manual-a.picacg.test", "manual-b.picacg.test"), values("runtimeTestRequests").toSet())
    }

    @Test
    fun `rejected apply guard permits exploration but publishes no selected history`() = runBlocking {
        val url = "https://guarded.picacg.test"
        val ip = "192.0.2.131"
        configure(listOf(url), mapOf(host(url) to listOf(ip)), version = 42)
        val old = measurement(url, ip, true, 1, 123_530)
        val history = PicacgSpeedTestHistoryPolicy.PhaseHistory(41, listOf(old))
        PicacgSpeedTestHistoryStorage.saveApi(history)
        var beforeApplyCalls = 0
        val published = mutableListOf<DomainIp?>()

        PicacgSpeedTester.run(
            reconcileConfig = true,
            beforeApply = {
                beforeApplyCalls++
                false
            },
            onApiPublished = published::add,
        )

        assertEquals(listOf("guarded.picacg.test"), values("runtimeTestRequests"), "active candidates are explored before the publication guard")
        assertTrue(published.isEmpty())
        assertNull(PicacgSpeedTester.snapshot.value)
        assertEquals(history, PicacgSpeedTestHistoryStorage.loadApi())
        assertEquals(1, beforeApplyCalls)
    }

    @Test
    fun `configuration change during publication guard suppresses publication after exploration`() = runBlocking {
        val url = "https://racing.picacg.test"
        val ip = "192.0.2.141"
        configure(listOf(url), mapOf(host(url) to listOf(ip)), version = 52)
        val history = PicacgSpeedTestHistoryPolicy.PhaseHistory(51, listOf(measurement(url, ip, true, 1, 123_540)))
        PicacgSpeedTestHistoryStorage.saveApi(history)
        val published = mutableListOf<DomainIp?>()

        PicacgSpeedTester.run(
            reconcileConfig = true,
            beforeApply = {
                invokeRoute("invalidateRuntimeTestRevision")
                true
            },
            onApiPublished = published::add,
        )

        assertEquals(listOf("racing.picacg.test"), values("runtimeTestRequests"), "the active pool is probed before the publication barrier")
        assertTrue(published.isEmpty())
        assertNull(PicacgSpeedTester.snapshot.value)
        assertEquals(history, PicacgSpeedTestHistoryStorage.loadApi())
    }

    @Test
    fun `reuseApi is only a quick hint and still probes before publishing health`() = runBlocking {
        val url = "https://cached.picacg.test"
        val ip = "192.0.2.29"
        configure(listOf(url), mapOf(host(url) to listOf(ip)), version = 4)
        val first = PicacgSpeedTester.run()
        assertTrue(first.apiProbes.single().success)
        invokeRoute("clearRuntimeTestTraffic")
        val published = mutableListOf<DomainIp?>()

        val reused = PicacgSpeedTester.run(reuseApi = true, onApiPublished = published::add)

        assertEquals(listOf("cached.picacg.test"), values("runtimeTestRequests"))
        assertTrue(reused.apiProbes.single().testedThisRound)
        assertEquals(DomainIp("cached.picacg.test", ip), reused.roundSelectedLine)
        assertEquals(listOf(DomainIp("cached.picacg.test", ip)), published, "only this run's fresh success is publishable")
        assertTrue(PicacgSpeedTestHistoryStorage.loadApi().measurements.single().measuredAtEpochMs > first.startedAtEpochMs)
    }

    @Test
    fun `cover success cannot substitute for body success and rankings stay independent`() = runBlocking {
        val apiUrl = "https://api-image.picacg.test"
        val apiIp = "192.0.2.81"
        val firstHost = "storage-b.go2778.com"
        val secondHost = "storage-b.picacomic.com"
        val firstIp = "192.0.2.82"
        val secondIp = "192.0.2.83"
        configure(
            listOf(apiUrl),
            mapOf(host(apiUrl) to listOf(apiIp), firstHost to listOf(firstIp), secondHost to listOf(secondIp)),
            version = 8,
            images = listOf(firstHost, secondHost),
        )
        setImageResponses(
            covers = mapOf("img.go2778.com" to 200, "img.picacomic.com" to 503),
            content = mapOf(firstHost to 503, secondHost to 200),
        )

        val snapshot = PicacgSpeedTester.run()

        assertEquals(setOf(false, true), snapshot.imageProbes.map { it.content }.toSet())
        assertEquals(setOf(firstHost), snapshot.imageProbes.filter { it.content.not() && it.success }.map { it.endpoint.domain }.toSet())
        assertEquals(setOf(secondHost), snapshot.imageProbes.filter { it.content && it.success }.map { it.endpoint.domain }.toSet())
        assertEquals(listOf(DomainIp(firstHost, firstIp)), values("runtimeTestCoverRanking").filterIsInstance<DomainIp>())
        assertEquals(listOf(DomainIp(secondHost, secondIp)), values("runtimeTestContentRanking").filterIsInstance<DomainIp>())
        assertEquals(DomainIp(secondHost, secondIp), snapshot.roundSelectedImage, "cover success must not be reported as body selection")
        assertTrue(values("runtimeTestImagePaths").any { it.startsWith("img.go2778.com/insecure/") })
        assertTrue(values("runtimeTestImagePaths").any { it.startsWith("storage-b.picacomic.com/static/") })
    }

    @Test
    fun `healthy quick API publishes current results and returns the complete measured pool`() = runBlocking {
        val urls = (0..3).map { "https://quick$it.picacg.test" }
        configure(urls, urls.mapIndexed { index, url -> host(url) to listOf("192.0.2.${index + 151}") }.toMap(), version = 71)
        val publications = mutableListOf<DomainIp?>()
        val snapshot = PicacgSpeedTester.run(fastApi = true, onApiPublished = publications::add)
        val selected = PicacgRouteManager.selectedApiEndpoint
        val progress = PicacgSpeedTester.progress.value

        awaitBackgroundExploration()

        assertEquals(4, snapshot.apiProbes.count { it.testedThisRound }, "the full quick run must finish every configured endpoint after publishing readiness")
        assertEquals(snapshot, PicacgSpeedTester.snapshot.value)
        assertEquals(PicacgSpeedTester.Stage.COMPLETE, progress?.stage)
        assertEquals(selected, PicacgRouteManager.selectedApiEndpoint)
        assertEquals(snapshot.roundSelectedLine, publications.last())
        assertEquals(4, values("runtimeTestRequests").size, "all configured endpoints are included before the run completes")
    }

    @Test
    fun `quick images never enqueue already attempted cover or body endpoints`() = runBlocking {
        val api = "https://one-image.picacg.test"
        val image = "storage-b.go2778.com"
        configure(listOf(api), mapOf(host(api) to listOf("192.0.2.161"), image to listOf("192.0.2.162")), version = 72, images = listOf(image))

        val snapshot = PicacgSpeedTester.run(fastApi = true, fastImages = true)
        awaitBackgroundExploration()

        assertEquals(2, snapshot.imageProbes.count { it.testedThisRound })
        // One cover endpoint validates both storage-b and storage1 samples; the body validates one.
        assertEquals(3, values("runtimeTestImagePaths").size, "each service has only one candidate, so there is no separate unexplored peer")
        assertEquals(4, values("runtimeTestRequests").size)
    }

    @Test
    fun `quick cover preferred host is not constrained by the different current body endpoint`() = runBlocking {
        val api = "https://separate-images.picacg.test"
        val images = listOf("storage-b.go2778.com", "storage-b.picacomic.com", "storage-b.safedataplj.com")
        val ips = images.mapIndexed { index, image -> image to listOf("192.0.2.${index + 171}") }.toMap()
        configure(listOf(api), ips + (host(api) to listOf("192.0.2.170")), version = 73, images = images)
        val cover = DomainIp(images[2], ips.getValue(images[2]).single())
        val body = DomainIp(images[1], ips.getValue(images[1]).single())
        invokeRoute("applyImageRankings", listOf(cover), listOf(body))
        invokeRoute("applyAutomaticImageEndpoint", body)

        val snapshot = PicacgSpeedTester.run(fastImages = true)
        awaitBackgroundExploration()

        val freshCover = snapshot.imageProbes.filter { !it.content && it.testedThisRound }
        val freshBody = snapshot.imageProbes.filter { it.content && it.testedThisRound }
        assertEquals(images.toSet(), freshCover.map { it.endpoint.domain }.toSet(), "quick return waits for full background cover exploration")
        assertEquals(images.toSet(), freshBody.map { it.endpoint.domain }.toSet(), "quick return waits for full background content exploration")
        assertTrue(freshCover.any { it.endpoint == cover }, "the current cover source must be revalidated even when body uses a different source")
        assertTrue(freshBody.any { it.endpoint == body })
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
        var beforeApplyCalls = 0
        val published = mutableListOf<DomainIp?>()

        PicacgSpeedTester.run(
            isCurrent = {
                currentChecks++
                true
            },
            beforeApply = {
                beforeApplyCalls++
                checksWhenApplyInvalidated = currentChecks
                invokeRoute("invalidateRuntimeTestRevision")
                true
            },
            onApiPublished = published::add,
        )

        assertEquals(4, checksWhenApplyInvalidated, "the early publication callback rechecks the generation after probing")
        assertEquals(1, beforeApplyCalls, "a failed API publication barrier prevents the final snapshot barrier")
        assertEquals(5, currentChecks, "revision is checked around the early publication guard")
        assertTrue(published.isEmpty(), "a configuration revision race must not publish the API line")
        assertNull(PicacgSpeedTester.snapshot.value)
        assertEquals(oldHistory, PicacgSpeedTestHistoryStorage.loadApi(), "rejected generation cannot publish history")
        assertEquals(listOf("only.picacg.test"), values("runtimeTestRequests"))
    }

    @Test
    fun `fresh successful API is published during the full probe run and matches final snapshot`() = runBlocking {
        val url = "https://fresh.picacg.test"
        val freshIp = "192.0.2.51"
        configure(listOf(url), mapOf(host(url) to listOf(freshIp)), version = 5)
        val events = mutableListOf<String>()
        val published = mutableListOf<DomainIp?>()

        val snapshot = PicacgSpeedTester.run(
            onApiPublished = { endpoint ->
                events += "published"
                published += endpoint
            },
            onProgress = { progress ->
                if (progress.stage == PicacgSpeedTester.Stage.PREPARING_IMAGES) events += "preparing-images"
            },
        )

        val publishedEndpoint = requireNotNull(published.single())
        assertNotNull(publishedEndpoint)
        assertEquals(DomainIp(host(url), freshIp), publishedEndpoint)
        assertTrue(events.contains("published"), "API publication must occur while its fresh success is available")
        assertTrue(events.contains("preparing-images"), "the independent image phase must also start")
        assertEquals(publishedEndpoint, snapshot.roundSelectedLine)
        assertEquals(publishedEndpoint, PicacgSpeedTester.snapshot.value?.roundSelectedLine)
        assertTrue(snapshot.apiProbes.any { it.endpoint == publishedEndpoint && it.testedThisRound && it.success })
        assertEquals(listOf("fresh.picacg.test"), values("runtimeTestApiSelections"))
    }

    @Test
    fun `rejected beforeApply does not invoke API publication callback`() = runBlocking {
        val url = "https://rejected.picacg.test"
        configure(listOf(url), mapOf(host(url) to listOf("192.0.2.61")), version = 6)
        val published = mutableListOf<DomainIp?>()
        var beforeApplyCalls = 0

        PicacgSpeedTester.run(
            beforeApply = {
                beforeApplyCalls++
                false
            },
            onApiPublished = published::add,
        )

        assertEquals(1, beforeApplyCalls, "a rejected API publication barrier suppresses the final snapshot gate")
        assertTrue(published.isEmpty(), "a rejected API publication barrier must not invoke the callback")
        assertNull(PicacgSpeedTester.snapshot.value)
    }

    @Test
    fun `quick run publishes the best current API while retaining full-run completion data`() = runBlocking {
        val urls = (0..4).map { "https://ready$it.picacg.test" }
        configure(urls, urls.mapIndexed { index, url -> host(url) to listOf("192.0.2.${index + 181}") }.toMap(), version = 74)
        val publications = mutableListOf<DomainIp?>()
        var readyCallbacks = 0
        var snapshotWasIncompleteAtApiPublication = false

        val snapshot = PicacgSpeedTester.run(
            fastApi = true,
            onApiPublished = { endpoint ->
                publications += endpoint
                snapshotWasIncompleteAtApiPublication = PicacgSpeedTester.snapshot.value == null
            },
            onRouteReady = { readyCallbacks++ },
        )

        assertTrue(publications.isNotEmpty(), "a current tested API success must publish before the run returns")
        assertTrue(snapshotWasIncompleteAtApiPublication, "API publication precedes the final full-run snapshot")
        assertEquals(snapshot.roundSelectedLine, PicacgRouteManager.selectedApiEndpoint)
        assertTrue(snapshot.apiProbes.all { it.testedThisRound }, "the returned quick-run snapshot must include every completed API measurement")
        assertFalse(snapshot.complete, "API-only configuration has no real cover/body results")
        assertNotNull(PicacgSpeedTester.snapshot.value, "the final API-only snapshot is still written after all configured candidates end")
        assertEquals(0, readyCallbacks)
        assertEquals(urls.map(::host).toSet(), values("runtimeTestRequests").toSet())
    }

    @Test
    fun `onRouteReady waits for API cover and reader page routes and never marks a partial snapshot complete`() = runBlocking {
        val api = "https://ready-all.picacg.test"
        val image = "storage-b.go2778.com"
        configure(listOf(api), mapOf(host(api) to listOf("192.0.2.191"), image to listOf("192.0.2.192")), version = 75, images = listOf(image))
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        var callbacks = 0
        val snapshot = PicacgSpeedTester.run(
            onApiPublished = {},
            onRouteReady = {
                callbacks++
                assertNull(PicacgSpeedTester.snapshot.value, "ready is incremental; complete snapshot is published only after every candidate finishes")
                ready.complete(Unit)
            },
        )

        withTimeout(1_000L) { ready.await() }
        assertEquals(1, callbacks)
        assertTrue(snapshot.complete)
        assertEquals(1, snapshot.apiProbes.count { it.testedThisRound })
        assertEquals(2, snapshot.imageProbes.count { it.testedThisRound }, "cover and body are independent successful image checks")
        assertEquals(PicacgSpeedTester.Stage.COMPLETE, PicacgSpeedTester.progress.value?.stage)
    }

    @Test
    fun `later faster API publication is reflected in final route without duplicate final binding`() = runBlocking {
        val urls = (0..6).map { "https://improve$it.picacg.test" }
        configure(urls, urls.mapIndexed { index, url -> host(url) to listOf("192.0.2.${index + 201}") }.toMap(), version = 76)
        val publications = mutableListOf<DomainIp?>()

        val snapshot = PicacgSpeedTester.run(fastApi = true, onApiPublished = publications::add)

        assertTrue(publications.isNotEmpty())
        assertEquals(snapshot.roundSelectedLine, PicacgRouteManager.selectedApiEndpoint)
        assertEquals(snapshot.roundSelectedLine, publications.last(), "the last improvement must match the best full-run result")
        assertEquals(publications.distinct().size, publications.size, "the final full snapshot must not re-publish/restart the same Maccms binding")
        assertEquals(urls.map(::host).toSet(), values("runtimeTestRequests").toSet())
    }

    private suspend fun awaitBackgroundExploration() {
        val type = AdaptiveSpeedHistory.javaClass
        val lock = type.getDeclaredField("explorationLock").apply { isAccessible = true }.get(AdaptiveSpeedHistory)

        @Suppress("UNCHECKED_CAST")
        val jobs = type.getDeclaredField("explorationJobs").apply { isAccessible = true }.get(AdaptiveSpeedHistory) as Set<Job>
        val pending = synchronized(lock) { jobs.toList() }
        withTimeout(2_000L) { pending.forEach { it.join() } }
    }

    private fun configure(urls: List<String>, ips: Map<String, List<String>>, version: Int, images: List<String> = emptyList()) {
        invokeRoute("configureForRuntimeTest", urls, images, ips, version)
    }

    private fun setResponses(codes: Map<String, Int>) = invokeRoute("setRuntimeTestResponses", codes)

    private fun setImageResponses(covers: Map<String, Int>, content: Map<String, Int>) =
        invokeRoute("setRuntimeTestImageResponses", covers, content)

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
