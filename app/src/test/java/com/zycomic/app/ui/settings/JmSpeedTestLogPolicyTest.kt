package com.zycomic.app.ui.settings

import com.zycomic.app.net.DomainIp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JmSpeedTestLogPolicyTest {
    @Test
    fun `selected route marker matches domain rather than an individual IP`() {
        val selectedDomainEndpoint = DomainIp("route.example", "203.0.113.1")
        val sameDomainDifferentIp = DomainIp("ROUTE.EXAMPLE", "203.0.113.2")
        val differentDomainSameIp = DomainIp("other.example", "203.0.113.1")

        assertEquals(true, JmSpeedTestLogPolicy.isSelectedDomain(selectedDomainEndpoint, "route.example"))
        assertEquals(true, JmSpeedTestLogPolicy.isSelectedDomain(sameDomainDifferentIp, "route.example"))
        assertEquals(false, JmSpeedTestLogPolicy.isSelectedDomain(differentDomainSameIp, "route.example"))
    }

    @Test
    fun `endpoint ranking puts fastest first and failures last`() {
        val slow = DomainIp("b.example", "203.0.113.2")
        val fast = DomainIp("z.example", "203.0.113.9")
        val tieLaterDomain = DomainIp("b.example", "203.0.113.3")
        val tieEarlierDomain = DomainIp("a.example", "203.0.113.4")
        val failed = DomainIp("a.example", "203.0.113.1")
        val noImage = DomainIp("c.example", "203.0.113.8")

        val ranked = JmSpeedTestLogPolicy.rankEndpoints(
            delays = mapOf(
                slow to 80L,
                fast to 12L,
                tieLaterDomain to 40L,
                tieEarlierDomain to 40L,
                failed to Long.MAX_VALUE,
                noImage to 5L,
            ),
            failedEndpoints = setOf(noImage),
        )

        assertEquals(listOf(fast, tieEarlierDomain, tieLaterDomain, slow, noImage, failed), ranked)
    }

    @Test
    fun `host ranking uses latency then case insensitive host and failures last`() {
        assertEquals(
            listOf("fast.example", "a.example", "z.example", "failed.example"),
            JmSpeedTestLogPolicy.rankHosts(
                mapOf(
                    "z.example" to 20L,
                    "a.example" to 20L,
                    "failed.example" to Long.MAX_VALUE,
                    "fast.example" to 4L,
                ),
            ),
        )
    }
}
