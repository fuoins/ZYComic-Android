package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmSpeedTestQuickProbePolicyTest {
    private data class Candidate(val endpoint: DomainIp, val source: String)

    @Test
    fun `current pool retains every endpoint beyond five in configured order`() {
        val candidates = (1..7).map { index ->
            Candidate(DomainIp("line-$index.example", "192.0.2.$index"), "configured-$index")
        }

        val result = JmSpeedTestQuickProbePolicy.currentCandidates(
            candidates = candidates,
            key = { "${it.endpoint}:${it.source}" },
            endpoint = Candidate::endpoint,
        )

        assertEquals(candidates, result)
        assertEquals(7, result.size, "quick probing must not truncate the configured pool to five")
    }

    @Test
    fun `historical endpoints cannot enter the current candidate pool`() {
        val active = Candidate(DomainIp("active.example", "192.0.2.1"), "current")
        val removed = Candidate(DomainIp("removed.example", "198.51.100.8"), "historical")

        val result = JmSpeedTestQuickProbePolicy.currentCandidates(
            candidates = listOf(active, active),
            key = { "${it.endpoint}:${it.source}" },
            endpoint = Candidate::endpoint,
        )

        assertEquals(listOf(active), result)
        assertFalse(result.any { it == removed })
        assertTrue(result.all { it.source == "current" })
    }

    @Test
    fun `same host and IP retain separately configured request identities`() {
        val endpoint = DomainIp("api.example", "192.0.2.9")
        val firstUrl = Candidate(endpoint, "https://api.example/v1")
        val secondUrl = Candidate(endpoint, "https://api.example/v2")

        val result = JmSpeedTestQuickProbePolicy.currentCandidates(
            candidates = listOf(firstUrl, secondUrl),
            key = { "${it.endpoint}:${it.source}" },
            endpoint = Candidate::endpoint,
        )

        assertEquals(listOf(firstUrl, secondUrl), result)
    }

    @Test
    fun `independent display history preserves fresh results and untouched prior values`() {
        val fast = DomainIp("line-fast.example", "192.0.2.1")
        val failed = DomainIp("line-failed.example", "192.0.2.2")
        val untouchedA = DomainIp("line-old-a.example", "192.0.2.3")
        val untouchedB = DomainIp("line-old-b.example", "192.0.2.4")

        val displayed = JmSpeedTestQuickProbePolicy.rankPriorDelays(
            fresh = linkedMapOf(failed to Long.MAX_VALUE, fast to 8L),
            untested = linkedMapOf(fast to 1L, untouchedA to 40L, untouchedB to 70L),
        )

        assertEquals(listOf(fast, failed, untouchedA, untouchedB), displayed.keys.toList())
        assertEquals(8L, displayed[fast], "fresh data replaces old display values")
        assertEquals(Long.MAX_VALUE, displayed[failed])
        assertEquals(40L, displayed[untouchedA])
    }

    @Test
    fun `NO_IMAGE is not endpoint failure while unreachable is`() {
        assertFalse(JmSpeedTestQuickProbePolicy.isImageEndpointFailure(JmSpeedTester.ImgVerdict.NO_IMAGE, Long.MAX_VALUE))
        assertTrue(JmSpeedTestQuickProbePolicy.isImageEndpointFailure(JmSpeedTester.ImgVerdict.UNREACHABLE, Long.MAX_VALUE))
        assertFalse(JmSpeedTestQuickProbePolicy.isImageEndpointFailure(JmSpeedTester.ImgVerdict.OK, 42L))
    }
}
