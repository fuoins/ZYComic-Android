package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JmImageEndpointSelectionPolicyTest {
    private val slow = DomainIp("images.example", "192.0.2.1")
    private val fast = DomainIp("images.example", "192.0.2.2")
    private val other = DomainIp("backup.example", "198.51.100.1")
    private val domains = listOf(slow.domain, other.domain)
    private val delays = linkedMapOf(slow to 50L, fast to 10L, other to 1L)
    private val verdicts = delays.keys.associateWith { JmSpeedTester.ImgVerdict.OK }

    @Test
    fun `multiple IP rows under one domain have exactly one automatic current endpoint`() {
        val current = select()
        assertEquals(fast, current)
        assertEquals(1, delays.keys.count { it == current })
    }

    @Test
    fun `manual click pins the exact slower IP rather than every row or fastest IP`() {
        val current = select(manual = slow.copy(domain = "IMAGES.EXAMPLE"))
        assertEquals(slow, current)
        assertEquals(1, delays.keys.count { it == current })
    }

    @Test
    fun `automatic selection stays within current domain even if another domain is faster`() {
        assertEquals(fast, select(domain = "IMAGES.EXAMPLE"))
        assertEquals(other, select(domain = other.domain))
    }

    @Test
    fun `automatic selection skips failed no-image missing verdict and invalid timings`() {
        val noImage = DomainIp(slow.domain, "192.0.2.3")
        val missing = DomainIp(slow.domain, "192.0.2.4")
        val negative = DomainIp(slow.domain, "192.0.2.5")
        assertEquals(
            slow,
            select(
                measurements = mapOf(slow to 50L, fast to Long.MAX_VALUE, noImage to 1L, missing to 2L, negative to -1L),
                results = mapOf(
                    slow to JmSpeedTester.ImgVerdict.OK,
                    fast to JmSpeedTester.ImgVerdict.OK,
                    noImage to JmSpeedTester.ImgVerdict.NO_IMAGE,
                    negative to JmSpeedTester.ImgVerdict.OK,
                ),
            ),
        )
    }

    @Test
    fun `no valid endpoint in active domain leaves every row unselected`() {
        val current = select(measurements = mapOf(slow to Long.MAX_VALUE, fast to Long.MAX_VALUE, other to 1L))
        assertNull(current)
        assertEquals(0, delays.keys.count { it == current })
        assertNull(select(measurements = emptyMap()))
    }

    @Test
    fun `manual measured row remains exact even when its last probe failed`() {
        assertEquals(fast, select(manual = slow, measurements = delays + (slow to Long.MAX_VALUE)))
    }

    @Test
    fun `removed manual endpoint is not silently replaced by another IP`() {
        assertNull(select(manual = slow, measurements = mapOf(fast to 10L)))
    }

    @Test
    fun `foreign domains and manual pins cannot escape sources2`() {
        val foreign = DomainIp("manwa.example", "203.0.113.1")
        assertNull(select(domain = foreign.domain, manual = foreign, measurements = delays + (foreign to 0L)))
        assertEquals(fast, select(manual = foreign))
        assertNull(
            JmImageEndpointSelectionPolicy.currentEndpoint(slow.domain, listOf(other.domain), delays, verdicts, slow),
        )
    }

    @Test
    fun `equal successful timings choose one stable IP regardless of input order`() {
        assertEquals(slow, select(measurements = linkedMapOf(fast to 10L, slow to 10L)))
    }

    private fun select(
        domain: String = slow.domain,
        manual: DomainIp? = null,
        measurements: Map<DomainIp, Long> = delays,
        results: Map<DomainIp, JmSpeedTester.ImgVerdict> = verdicts,
    ): DomainIp? = JmImageEndpointSelectionPolicy.currentEndpoint(domain, domains, measurements, results, manual)
}
