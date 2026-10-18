package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReusableImageSpeedTestPolicyTest {
    @Test
    fun `fingerprint includes only image pool and applicable image IP mappings`() {
        val base = ReusableImageSpeedTestPolicy.fingerprint(
            imageDomains = listOf("img-a.example", "img-b.example"),
            ipMappings = mapOf("img-a.example" to listOf("192.0.2.1")),
        )
        val withUnrelatedRouteChanges = ReusableImageSpeedTestPolicy.fingerprint(
            imageDomains = listOf("img-a.example", "img-b.example"),
            ipMappings = mapOf(
                "img-a.example" to listOf("192.0.2.1"),
                "line.example" to listOf("198.51.100.8"),
                "unrelated-img.example" to listOf("203.0.113.4"),
            ),
        )

        assertEquals(base, withUnrelatedRouteChanges)
        assertEquals(
            base,
            ReusableImageSpeedTestPolicy.fingerprint(
                imageDomains = listOf(" IMG-A.EXAMPLE ", "img-b.example"),
                ipMappings = mapOf("IMG-A.EXAMPLE" to listOf("192.0.2.1")),
            ),
        )
    }

    @Test
    fun `fingerprint invalidates when image pool or applicable IP mapping changes`() {
        val base = ReusableImageSpeedTestPolicy.fingerprint(
            imageDomains = listOf("img-a.example", "img-b.example"),
            ipMappings = mapOf("img-a.example" to listOf("192.0.2.1")),
        )

        assertFalse(
            base == ReusableImageSpeedTestPolicy.fingerprint(
                imageDomains = listOf("img-a.example"),
                ipMappings = mapOf("img-a.example" to listOf("192.0.2.1")),
            ),
        )
        assertFalse(
            base == ReusableImageSpeedTestPolicy.fingerprint(
                imageDomains = listOf("img-a.example", "img-b.example", "img-c.example"),
                ipMappings = mapOf("img-a.example" to listOf("192.0.2.1")),
            ),
        )
        assertFalse(
            base == ReusableImageSpeedTestPolicy.fingerprint(
                imageDomains = listOf("img-a.example", "img-b.example"),
                ipMappings = mapOf("img-a.example" to listOf("192.0.2.2")),
            ),
        )
    }

    @Test
    fun `reuse requires unchanged image fingerprint network identity and fresh timestamp`() {
        val fingerprint = "image-fingerprint"
        val network = "wifi-network-a"
        val now = 5_000_000L
        val completed = now - ReusableImageSpeedTestPolicy.MAX_AGE_MS

        assertTrue(ReusableImageSpeedTestPolicy.canReuse(fingerprint, fingerprint, network, network, completed, now))
        assertFalse(ReusableImageSpeedTestPolicy.canReuse(fingerprint, "changed", network, network, completed, now))
        assertFalse(ReusableImageSpeedTestPolicy.canReuse(fingerprint, fingerprint, "old-network", network, completed, now))
        assertFalse(
            ReusableImageSpeedTestPolicy.canReuse(
                fingerprint,
                fingerprint,
                network,
                network,
                completed - 1,
                now,
            ),
        )
        assertFalse(ReusableImageSpeedTestPolicy.canReuse(fingerprint, fingerprint, network, network, now + 1, now))
        assertFalse(ReusableImageSpeedTestPolicy.isFresh(null, now))
    }

    @Test
    fun `forced full test and changed network identity never reuse prior image results`() {
        val current = SpeedTestRunKey(
            platformRevision = 1,
            manwaConfigRevision = 2,
            jmConfigRevision = 3,
            networkIdentity = "wifi-network-b",
        )
        val previousNetwork = current.copy(networkIdentity = "wifi-network-a")

        assertFalse(SpeedTestRunPolicy.canReuseImages(true, true, current, current, current))
        assertFalse(SpeedTestRunPolicy.canReuseImages(true, false, previousNetwork, current, previousNetwork))
        assertTrue(SpeedTestRunPolicy.canReuseImages(true, false, current, current, null))
    }

    @Test
    fun `resolved image pool must match exactly including IP addresses`() {
        val cached = setOf(DomainIp("img.example", "192.0.2.1"), DomainIp("img.example", "192.0.2.2"))

        assertTrue(ReusableImageSpeedTestPolicy.matchesResolvedEndpoints(cached, cached))
        assertFalse(
            ReusableImageSpeedTestPolicy.matchesResolvedEndpoints(
                cached,
                setOf(DomainIp("img.example", "192.0.2.1"), DomainIp("img.example", "192.0.2.3")),
            ),
        )
        assertFalse(
            ReusableImageSpeedTestPolicy.matchesResolvedEndpoints(
                cached,
                cached + DomainIp("img-new.example", "198.51.100.1"),
            ),
        )
    }
}
