package com.zycomic.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestHistoryPolicyTest {
    private fun endpoint(domain: String, ip: String = "192.0.2.1") = DomainIp(domain, ip)

    @Test
    fun historyRequiresOnlyEqualRemoteConfigVersion() {
        assertTrue(SpeedTestHistoryPolicy.isVersionCompatible(17, 17))
        assertTrue(SpeedTestHistoryPolicy.isVersionCompatible(null, null))
        assertFalse(SpeedTestHistoryPolicy.isVersionCompatible(17, 18))
        assertFalse(SpeedTestHistoryPolicy.isVersionCompatible(null, 17))
    }

    @Test
    fun selectsOnlyFiveFastestSuccessfulDomainIpEndpoints() {
        val delays = (1..8).associate { endpoint("line$it.example", "192.0.2.$it") to (it * 10L) }.toMutableMap()
        delays[endpoint("failed.example")] = Long.MAX_VALUE

        assertEquals(
            (1..5).map { endpoint("line$it.example", "192.0.2.$it") },
            SpeedTestHistoryPolicy.selectTopSuccessful(delays),
        )
    }

    @Test
    fun mergeReplacesOnlySelectedSamplesAndKeepsOtherSamplesUnchanged() {
        val first = endpoint("a.example")
        val second = endpoint("b.example")
        val third = endpoint("c.example")
        val selected = setOf(first, second)

        assertEquals(
            linkedMapOf(first to 4L, second to Long.MAX_VALUE, third to 300L),
            SpeedTestHistoryPolicy.merge(
                previous = linkedMapOf(first to 100L, second to 200L, third to 300L),
                selected = selected,
                fresh = linkedMapOf(first to 4L, second to Long.MAX_VALUE),
            ),
        )
    }

    @Test
    fun imageRankingCanExcludeEndpointsWithoutSuccessfulImageVerdict() {
        val delays = linkedMapOf(endpoint("ok.example") to 12L, endpoint("bad.example") to 1L)
        val selected = SpeedTestHistoryPolicy.selectTopSuccessful(delays) { endpoint, _ -> endpoint.domain == "ok.example" }

        assertEquals(listOf(endpoint("ok.example")), selected)
    }

    @Test
    fun nextQuickRetestUsesPersistedOrderIncludingFreshlyFailedEndpoint() {
        val endpoints = (1..7).map { endpoint("line$it.example") }
        val ranking = linkedMapOf(
            endpoints[0] to Long.MAX_VALUE,
            endpoints[1] to 9L,
            endpoints[2] to 12L,
            endpoints[3] to 20L,
            endpoints[4] to 30L,
            endpoints[5] to 1L,
            endpoints[6] to 2L,
        )

        assertEquals(endpoints.take(5), SpeedTestHistoryPolicy.selectRankedTop(ranking))
    }

    @Test
    fun freshMeasurementsAreSortedBeforeUnchangedOldTailAndFailuresAreRetained() {
        val endpoints = (1..7).map { endpoint("line$it.example") }
        val previous = endpoints.zip((100L..700L step 100L).toList()).toMap(LinkedHashMap())
        val fresh = linkedMapOf(endpoints[0] to Long.MAX_VALUE, endpoints[1] to 5L)

        assertEquals(
            linkedMapOf(
                endpoints[1] to 5L,
                endpoints[0] to Long.MAX_VALUE,
                endpoints[2] to 300L,
                endpoints[3] to 400L,
                endpoints[4] to 500L,
                endpoints[5] to 600L,
                endpoints[6] to 700L,
            ),
            SpeedTestHistoryPolicy.rankAfterRun(previous, fresh),
        )
    }
}
