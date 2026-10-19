package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmSpeedTestQuickProbePolicyTest {
    private val oldTime = 1_000L

    @Test
    fun `selects five prior domain IP pairs only when config version matches`() {
        val endpoints = (1..7).associate { index ->
            DomainIp("line-$index.example", "192.0.2.$index") to index.toLong()
        } + (DomainIp("stale.example", "198.51.100.1") to 0L)

        val selected = JmSpeedTestQuickProbePolicy.selectPriorEndpoints(
            priorVersion = 12,
            currentVersion = 12,
            priorCompletedAtEpochMs = oldTime,
            delays = linkedMapOf(
                DomainIp("stale.example", "198.51.100.1") to 0L,
                DomainIp("line-1.example", "192.0.2.1") to 4L,
                DomainIp("line-2.example", "192.0.2.2") to 3L,
                DomainIp("line-3.example", "192.0.2.3") to 2L,
                DomainIp("line-4.example", "192.0.2.4") to 1L,
                DomainIp("line-5.example", "192.0.2.5") to 5L,
                DomainIp("line-6.example", "192.0.2.6") to 6L,
            ),
        )

        assertEquals(
            listOf(DomainIp("stale.example", "198.51.100.1")) + (1..4).map { DomainIp("line-$it.example", "192.0.2.$it") },
            selected,
        )
        assertTrue(
            JmSpeedTestQuickProbePolicy.selectPriorEndpoints(
                priorVersion = 11,
                currentVersion = 12,
                priorCompletedAtEpochMs = oldTime,
                delays = endpoints,
            ).isEmpty(),
        )
    }

    @Test
    fun `matching versions allow historical endpoints without checking the current host pool`() {
        val historical = DomainIp("removed-from-pool.example", "192.0.2.90")

        assertEquals(
            listOf(historical),
            JmSpeedTestQuickProbePolicy.selectPriorEndpoints(
                priorVersion = 41,
                currentVersion = 41,
                priorCompletedAtEpochMs = oldTime,
                delays = linkedMapOf(historical to 4L),
            ),
        )
    }

    @Test
    fun `fresh successes and failures rank before untouched history while old timestamps remain`() {
        val fast = DomainIp("line-fast.example", "192.0.2.1")
        val failed = DomainIp("line-failed.example", "192.0.2.2")
        val untouchedA = DomainIp("line-old-a.example", "192.0.2.3")
        val untouchedB = DomainIp("line-old-b.example", "192.0.2.4")
        val prior = linkedMapOf(failed to 10L, untouchedA to 40L, untouchedB to 70L)
        val tested = setOf(fast, failed)

        val merged = JmSpeedTestQuickProbePolicy.mergeDelays(
            prior = prior,
            fresh = linkedMapOf(failed to Long.MAX_VALUE, fast to 8L),
            tested = tested,
        )
        val times = JmSpeedTestQuickProbePolicy.mergeTestedAt(
            prior = mapOf(failed to oldTime, untouchedA to oldTime + 1, untouchedB to oldTime + 2),
            fresh = mapOf(failed to 3_000L, fast to 3_001L),
            tested = tested,
            freshOrder = listOf(fast, failed),
        )

        assertEquals(listOf(fast, failed, untouchedA, untouchedB), merged.keys.toList())
        assertEquals(8L, merged[fast])
        assertEquals(40L, merged[untouchedA])
        assertEquals(Long.MAX_VALUE, merged[failed])
        assertEquals(listOf(fast, failed), times.filterKeys { it in tested }.keys.toList())
        assertEquals(oldTime + 1, times[untouchedA])
        assertEquals(3_000L, times[failed])
    }

    @Test
    fun `saved fresh ranking precedes untouched history without sorting untouched old latencies`() {
        val freshSlow = DomainIp("fresh-slow.example", "192.0.2.1")
        val untouchedFastOld = DomainIp("untested-fast-old.example", "192.0.2.2")
        val freshFast = DomainIp("fresh-fast.example", "192.0.2.3")
        val untouchedSlowOld = DomainIp("untested-slow-old.example", "192.0.2.4")
        val priorFresh = linkedMapOf(freshSlow to 60L, freshFast to 20L)
        val priorUntested = linkedMapOf(untouchedFastOld to 1L, untouchedSlowOld to 90L)

        val savedRank = JmSpeedTestQuickProbePolicy.rankPriorDelays(priorFresh, priorUntested)
        val selected = JmSpeedTestQuickProbePolicy.selectPriorEndpoints(12, 12, oldTime, savedRank)

        assertEquals(listOf(freshFast, freshSlow, untouchedFastOld, untouchedSlowOld), savedRank.keys.toList())
        assertEquals(savedRank.keys.toList(), selected)
    }
}
