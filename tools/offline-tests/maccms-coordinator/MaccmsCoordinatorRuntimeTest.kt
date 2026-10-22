package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the production coordinator and real Maccms tester with deterministic offline probes. */
class MaccmsCoordinatorRuntimeTest {
    private fun configure() {
        MaccmsProbeRecorder.install()
        PlatformSpeedTestCoordinator.finishStartupConfigCheck(false)
        StartupPlatformPolicy.state.value = StartupPlatformPolicy.State(
            disableManwa = true,
            disablePicacg = false,
            disableJm = true,
            useLastResult = true,
            useLastLineResult = true,
            revision = 71,
        )
        PicacgRouteManager.verifiedImageSampleUrl = "https://fixture.invalid/cover.jpg"
    }

    private fun assertBinding(ip: String?, startIndex: Int) {
        val probes = MaccmsProbeRecorder.invocations.drop(startIndex)
        if (ip == null) {
            assertTrue(probes.isEmpty(), "missing lines3 IP must not invoke any host transport")
            assertNull(MaccmsSpeedTester.routeSelection().lines3Ip, "a prior selection must not survive without this round's IP")
        } else {
            assertEquals(3, probes.size, "each of the three independent Maccms hosts must be probed")
            assertEquals(MaccmsSpeedTester.routes.toSet(), probes.map { it.host }.toSet())
            assertTrue(probes.all { it.ip == ip }, "all host probes must receive this round's selected IP")
            assertEquals(ip, MaccmsSpeedTester.routeSelection().lines3Ip, "published route selection must hold the latest round IP")
        }
    }

    @Test
    fun `complete phase cache still publishes a fresh current round IP for Maccms`() = runBlocking {
        configure()
        PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        val firstIp = PicacgSpeedTester.snapshot.value?.roundSelectedLine?.ip
        assertNotNull(firstIp)
        assertBinding(firstIp, 0)

        val secondRoundStart = MaccmsProbeRecorder.invocations.size
        PlatformSpeedTestCoordinator.run()

        val currentIp = PicacgSpeedTester.snapshot.value?.roundSelectedLine?.ip
        assertNotNull(currentIp)
        assertNotEquals(firstIp, currentIp, "reuseApi=false must produce a current-round selection")
        assertEquals(listOf(false to false, false to true), PicacgSpeedTester.reuseFlags)
        assertBinding(currentIp, secondRoundStart)
        assertEquals(6, MaccmsProbeRecorder.invocations.size)
        assertTrue(MaccmsProbeRecorder.invocations.take(3).all { it.ip == firstIp })
    }

    @Test
    fun `persisted historical selected IP is ignored in favor of this run`() = runBlocking {
        configure()
        val old = DomainIp("api.picacg.test", "198.51.100.9")
        PicacgSpeedTester.snapshot.value = PicacgSpeedTester.Snapshot(
            startedAtEpochMs = 1,
            completedAtEpochMs = 2,
            remoteVersion = DevConfig.version,
            apiProbes = listOf(PicacgSpeedTester.Probe(old, true, 3, 200)),
            imageProbes = listOf(PicacgSpeedTester.Probe(DomainIp("img.picacg.test", "198.51.100.10"), true, 3, 200)),
            imageBlockedReason = null,
            revision = PicacgRouteManager.configRevision,
            roundSelectedLine = old,
        )

        PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)

        val current = PicacgSpeedTester.snapshot.value?.roundSelectedLine?.ip
        assertNotNull(current)
        assertNotEquals(old.ip, current)
        assertBinding(current, 0)
        assertFalse(MaccmsProbeRecorder.invocations.any { it.ip == old.ip })
    }

    @Test
    fun `failed Picacg run with old snapshot cannot fall back to old IP or probe Maccms`() = runBlocking {
        configure()
        val old = DomainIp("api.picacg.test", "198.51.100.22")
        PicacgSpeedTester.snapshot.value = PicacgSpeedTester.Snapshot(
            1,
            2,
            DevConfig.version,
            listOf(PicacgSpeedTester.Probe(old, true, 2, 200)),
            listOf(PicacgSpeedTester.Probe(DomainIp("img.picacg.test", "198.51.100.23"), true, 2, 200)),
            null,
            PicacgRouteManager.configRevision,
            old,
        )
        PicacgSpeedTester.failNextRun = true

        val reports = PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)

        assertEquals("incomplete", reports.single { it.platform == "哔咔" }.status)
        assertBinding(null, 0)
        assertEquals(old, PicacgSpeedTester.snapshot.value?.roundSelectedLine, "failed fixture leaves old published snapshot intact")
    }

    @Test
    fun `unpublished returned snapshot is discarded before Maccms binding`() = runBlocking {
        configure()
        PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        val publishedBefore = PicacgSpeedTester.snapshot.value
        val oldIp = publishedBefore?.roundSelectedLine?.ip
        assertBinding(oldIp, 0)
        val unpublishedRoundStart = MaccmsProbeRecorder.invocations.size
        PicacgSpeedTester.doNotPublishNextRun = true

        PlatformSpeedTestCoordinator.run(forceFresh = true, onlyPlatform = ContentPlatform.PICACG)

        val returnedCandidateIp = "192.0.2.${10 + PicacgSpeedTester.runCount}"
        assertNotNull(publishedBefore)
        assertEquals(oldIp, PicacgSpeedTester.snapshot.value?.roundSelectedLine?.ip)
        assertNotEquals(oldIp, returnedCandidateIp)
        assertBinding(null, unpublishedRoundStart)
        assertEquals(3, MaccmsProbeRecorder.invocations.size, "only the first published round may reach host transports")
        assertFalse(MaccmsProbeRecorder.invocations.any { it.ip == returnedCandidateIp })
    }

    @Test
    fun `configuration race discards stale selection and retries with only latest generation IP`() = runBlocking {
        configure()
        var changed = false
        PicacgSpeedTester.beforeApplyHook = {
            if (!changed) {
                changed = true
                PicacgRouteManager.configRevision++
                PicacgSpeedTester.beforeApplyHook = null
            }
        }

        PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)

        assertTrue(changed)
        assertEquals(2, PicacgSpeedTester.runCount, "stale generation must retry after config revision changes")
        val currentIp = PicacgSpeedTester.snapshot.value?.roundSelectedLine?.ip
        assertNotNull(currentIp)
        assertBinding(currentIp, 0)
        assertEquals(3, MaccmsProbeRecorder.invocations.size, "stale generation must not invoke the transport")
    }

    @Test
    fun `cancellation propagates and never hands a cached selection to Maccms`() = runBlocking {
        configure()
        val old = DomainIp("api.picacg.test", "198.51.100.31")
        PicacgSpeedTester.snapshot.value = PicacgSpeedTester.Snapshot(
            1,
            2,
            DevConfig.version,
            listOf(PicacgSpeedTester.Probe(old, true, 3, 200)),
            listOf(PicacgSpeedTester.Probe(DomainIp("img.picacg.test", "198.51.100.32"), true, 3, 200)),
            null,
            PicacgRouteManager.configRevision,
            old,
        )
        PicacgSpeedTester.progressHook = { progress ->
            if (progress.stage == PicacgSpeedTester.Stage.TESTING_API) throw CancellationException("cancel fixture")
        }

        var cancelled = false
        try {
            PlatformSpeedTestCoordinator.run(remoteConfigUpdated = true)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled, "cancellation must propagate out of the coordinator")
        assertBinding(null, 0)
        assertEquals(old.ip, PicacgSpeedTester.snapshot.value?.roundSelectedLine?.ip, "the old published result remains only old state")
        assertFalse(PlatformSpeedTestCoordinator.running.value)
    }
}

internal object MaccmsProbeRecorder {
    data class Invocation(val host: String, val ip: String)

    val invocations = CopyOnWriteArrayList<Invocation>()

    fun install() {
        invocations.clear()
        MaccmsSpeedTester.installProbeTransportForTests { host, ip ->
            invocations += Invocation(host, ip)
            MaccmsSpeedTester.Probe(
                route = host,
                elapsedMs = MaccmsSpeedTester.routes.indexOf(host).toLong() + 1,
                success = true,
                httpStatus = 204,
            )
        }
    }
}
