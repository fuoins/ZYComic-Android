package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgApiCandidatePolicyTest {
    private val urls = listOf("https://api-a.example/v2", "https://api-b.example/v2", "https://api-c.example/v2")
    private val current = DomainIp("api-a.example", "198.51.100.10")
    private val alternate = DomainIp("api-b.example", "198.51.100.21")

    @Test
    fun `only successful current snapshot configured alternates are eligible`() {
        assertEquals(listOf(PicacgApiRouteCandidate(urls[1], alternate)), candidates())
    }

    @Test
    fun `manual offline stale or uncommitted routes have no candidates`() {
        assertTrue(candidates(manual = true).isEmpty())
        assertTrue(candidates(online = false).isEmpty())
        assertTrue(candidates(snapshotRevision = 8).isEmpty())
        assertTrue(candidates(committed = null).isEmpty())
    }

    private fun candidates(
        manual: Boolean = false,
        online: Boolean = true,
        snapshotRevision: Long = 9,
        committed: DomainIp? = current,
    ) = PicacgApiCandidatePolicy.candidates(
        apiUrls = urls,
        currentIndex = 0,
        expectedHost = current.domain,
        committedEndpoint = committed,
        probes = listOf(
            PicacgApiProbeEndpoint(current, true, 30),
            PicacgApiProbeEndpoint(DomainIp("api-b.example", "198.51.100.20"), true, 40),
            PicacgApiProbeEndpoint(alternate, true, 20),
            PicacgApiProbeEndpoint(DomainIp("api-c.example", "198.51.100.30"), false, 5),
            PicacgApiProbeEndpoint(DomainIp("unconfigured.example", "198.51.100.40"), true, 1),
        ),
        snapshotRevision = snapshotRevision,
        configRevision = 9,
        apiRouteRules = mapOf(
            current.domain to listOf(current.ip),
            alternate.domain to listOf("198.51.100.20", alternate.ip),
            "api-c.example" to listOf("198.51.100.30"),
        ),
        manual = manual,
        online = online,
    )
}
