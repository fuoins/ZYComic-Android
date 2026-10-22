package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI

class PicacgSharedDnsPoolOfflineTest {
    @Test
    fun `approved SmartDNS candidates are shared across every built-in host and baselines stay first`() {
        val candidates = listOf("1.2.3.4", "8.8.8.8", "10.0.0.1", "203.0.113.4", "1.2.3.4")
        val rules = PicacgBuiltinNetwork.rules(candidates)
        val configuredHosts = PicacgBuiltinNetwork.apiUrls.map { URI(it).host } +
            PicacgBuiltinNetwork.imageHosts + PicacgBuiltinNetwork.coverHosts
        val sharedPool = listOf("1.2.3.4", "8.8.8.8")

        configuredHosts.forEach { host ->
            val baseline = PicacgBuiltinNetwork.builtInLookup(host)
            val addresses = rules[host]
            assertNotNull(addresses, "missing host rule for $host")
            assertEquals(baseline, addresses!!.take(baseline.size), "baseline order changed for $host")
            assertEquals(sharedPool, addresses.drop(baseline.size), "SmartDNS candidates not shared for $host")
        }
    }

    @Test
    fun `actual speed-test expansion creates each API and independent cover and body identity per host IP`() {
        val rules = PicacgBuiltinNetwork.rules(listOf("1.2.3.4", "8.8.8.8"))
        val apiTargets = PicacgSpeedTestTargetPolicy.apiTargets(PicacgBuiltinNetwork.apiUrls) { rules[it].orEmpty() }
        val expectedApiPairs = PicacgBuiltinNetwork.apiUrls.flatMap { url ->
            val host = URI(url).host
            rules.getValue(host).map { "$host|$it" }
        }.toSet()
        assertEquals(expectedApiPairs, apiTargets.map { "${URI(it.url).host}|${it.endpoint.ip}" }.toSet())
        assertEquals(expectedApiPairs.size, apiTargets.size, "duplicate or missing API host/IP target")
        assertTrue(apiTargets.all { URI(it.url).host.equals(it.endpoint.domain, true) })

        val imageTargets = PicacgSpeedTestTargetPolicy.imageTargets(PicacgBuiltinNetwork.imageHosts) { rules[it].orEmpty() }
        val expectedImagePairs = PicacgBuiltinNetwork.imageHosts.flatMap { host ->
            rules.getValue(host).map { "$host|$it" }
        }.toSet()
        assertEquals(expectedImagePairs.size * 2, imageTargets.size, "each storage-b host/IP needs cover and body targets")
        expectedImagePairs.forEach { pair ->
            val (host, ip) = pair.split('|')
            val targets = imageTargets.filter { it.endpoint.domain == host && it.endpoint.ip == ip }
            assertEquals(setOf(false, true), targets.map { it.content }.toSet(), pair)
            assertEquals(1, targets.count { !it.content && URI(it.url).host == PicacgBuiltinNetwork.coverHostFor(host) })
            assertEquals(1, targets.count { it.content && URI(it.url).host == host })
            assertEquals(2, targets.map { "${URI(it.url).host}|${it.content}" }.toSet().size, "service identity collision for $pair")
        }
    }

    @Test
    fun `cover and body successful outcomes rank independently`() {
        val rootA = PicacgBuiltinNetwork.imageHosts[0]
        val rootB = PicacgBuiltinNetwork.imageHosts[1]
        val outcomes = listOf(
            PicacgSpeedTestTargetPolicy.ImageOutcome(DomainIp(rootA, "192.0.2.1"), content = false, success = true, elapsedMs = 80),
            PicacgSpeedTestTargetPolicy.ImageOutcome(DomainIp(rootB, "192.0.2.2"), content = false, success = true, elapsedMs = 20),
            PicacgSpeedTestTargetPolicy.ImageOutcome(DomainIp(rootA, "192.0.2.3"), content = true, success = true, elapsedMs = 10),
            PicacgSpeedTestTargetPolicy.ImageOutcome(DomainIp(rootB, "192.0.2.4"), content = true, success = true, elapsedMs = 90),
            PicacgSpeedTestTargetPolicy.ImageOutcome(DomainIp(rootA, "192.0.2.5"), content = false, success = false, elapsedMs = 1),
        )
        assertEquals(listOf(DomainIp(rootB, "192.0.2.2"), DomainIp(rootA, "192.0.2.1")), PicacgSpeedTestTargetPolicy.ranking(outcomes, content = false))
        assertEquals(listOf(DomainIp(rootA, "192.0.2.3"), DomainIp(rootB, "192.0.2.4")), PicacgSpeedTestTargetPolicy.ranking(outcomes, content = true))
    }

    @Test
    fun `runtime image success requires image content type and recognized nonempty magic`() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)
        val html = "<html>origin error".toByteArray()
        assertTrue(PicacgImagePayloadPolicy.isUsableImage(200, "image/jpeg", jpeg))
        assertFalse(PicacgImagePayloadPolicy.isUsableImage(200, "image/jpeg", html))
        assertFalse(PicacgImagePayloadPolicy.isUsableImage(200, "image/jpeg", byteArrayOf()))
        assertFalse(PicacgImagePayloadPolicy.isUsableImage(200, null, jpeg))
        assertFalse(PicacgImagePayloadPolicy.isUsableImage(503, "image/jpeg", jpeg))
    }

    @Test
    fun `fallback source retry is limited to classified transport and never cancellation`() {
        assertTrue(PicacgImagePayloadPolicy.mayTryNextSource(java.net.SocketTimeoutException(), cancelled = false))
        assertFalse(PicacgImagePayloadPolicy.mayTryNextSource(IOException("bad image payload"), cancelled = false))
        assertFalse(PicacgImagePayloadPolicy.mayTryNextSource(java.net.SocketTimeoutException(), cancelled = true))
    }
}
