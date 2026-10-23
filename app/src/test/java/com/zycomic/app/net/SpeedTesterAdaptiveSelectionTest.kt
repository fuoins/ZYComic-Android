package com.zycomic.app.net

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class SpeedTesterAdaptiveSelectionTest {
    private fun endpoint(domain: String, ip: String) = DomainIp(domain, ip)

    @Test
    fun fastestSelectionUsesOnlyTheCurrentRoundMapAndRejectsFailedSamples() {
        val freshFastButInvalidImage = endpoint("cdn-a.example", "192.0.2.1")
        val freshValidImage = endpoint("cdn-b.example", "192.0.2.2")
        val currentRound = linkedMapOf(freshFastButInvalidImage to 2L, freshValidImage to 240L)

        assertEquals(
            freshValidImage,
            SpeedTester.fastestCurrentEndpoint(currentRound) { it == freshValidImage },
        )
    }

    @Test
    fun noSuccessfulCurrentRoundEndpointMeansNoAutomaticRouteCandidate() {
        assertNull(
            SpeedTester.fastestCurrentEndpoint(
                mapOf(endpoint("line.example", "192.0.2.3") to Long.MAX_VALUE),
            ),
        )
    }

    @Test
    fun manualLineOverrideRejectsEarlyAutomaticRoutePublication() {
        val oldLines = RouteManager.lineHosts
        val oldImages = RouteManager.imgDomains
        val oldVersion = RouteManager.activeRemoteConfigVersion
        val oldAutoSelect = RouteManager.autoSelectEnabled
        val oldDomain = RouteManager.lineHost
        val oldIp = RouteManager.selectedLineIp
        try {
            RouteManager.applyBaseLists(
                lineUrls = listOf("https://line-a.example", "https://line-b.example"),
                sourceDomains = listOf("cdn-a.example", "cdn-b.example"),
            )
            RouteManager.setAutoSelectEnabled(true)
            RouteManager.setLineEndpoint("line-a.example", "192.0.2.10")
            val expectedRevision = RouteManager.configRevision
            val expectedPlatformRevision = com.zycomic.app.platform.StartupPlatformPolicy.state.value.revision

            assertFalse(
                RouteManager.applyFastestLineEndpoint(
                    endpoint("line-b.example", "192.0.2.11"),
                    expectedConfigRevision = expectedRevision,
                    expectedPlatformRevision = expectedPlatformRevision,
                ),
            )
            assertEquals("line-a.example", RouteManager.baseUrl.substringAfter("://").substringBefore('/'))
            assertEquals("192.0.2.10", RouteManager.selectedLineIp)
        } finally {
            RouteManager.setAutoSelectEnabled(true)
            RouteManager.applyBaseLists(oldLines, oldImages, oldVersion)
            RouteManager.setAutoSelectEnabled(oldAutoSelect)
            if (!oldAutoSelect) {
                val oldIndex = oldLines.indexOfFirst { it.contains(oldDomain, ignoreCase = true) }
                if (oldIndex >= 0) {
                    if (oldIp.isNullOrBlank()) {
                        RouteManager.setLine(oldIndex)
                    } else {
                        RouteManager.setLineEndpoint(oldDomain, oldIp)
                    }
                }
            }
        }
    }

    @Test
    fun partialQuickRunSelectsFreshSuccessInsteadOfHistoricalOnlyFastEndpoint() = runBlocking {
        val historicalOnly = endpoint("cdn-old.example", "192.0.2.10")
        val freshSlow = endpoint("cdn-a.example", "192.0.2.11")
        val freshFast = endpoint("cdn-b.example", "192.0.2.12")
        val startedAt = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli()
        val now = startedAt
        val scope = "image"
        val bucket = AdaptiveSpeedProbe.timeBucket(startedAt, ZoneId.of("UTC"))
        fun historyKey(candidate: DomainIp) = listOf(bucket, "wifi", scope, "${candidate.domain}|${candidate.ip}")
            .joinToString("") { "${it.length}:$it" }
        val values = mutableMapOf(
            historyKey(historicalOnly) to AdaptiveSpeedProbe.History(
                successes = 4,
                latencyMs = 1L,
                lastMeasuredAt = now,
                cooldownUntil = now + 60_000L,
            ),
        )
        val store = object : AdaptiveSpeedProbe.Store {
            override fun read(key: String): AdaptiveSpeedProbe.History? = values[key]
            override fun write(key: String, value: AdaptiveSpeedProbe.History) {
                values[key] = value
            }
        }
        val session = AdaptiveSpeedProbe.Session(
            networkIdentity = "wifi",
            startedAtEpochMs = startedAt,
            store = store,
            budgetMs = 2_000L,
            concurrency = 1,
            now = { now },
            zone = ZoneId.of("UTC"),
        )

        val measured = session.run(
            scope = scope,
            candidates = listOf(freshSlow, freshFast, historicalOnly),
            key = { "${it.domain}|${it.ip}" },
            quick = true,
            success = { true },
            elapsedMs = { it },
        ) { candidate -> if (candidate == freshSlow) 180L else 20L }

        val currentRound = measured.toMap()
        assertEquals(setOf(freshSlow, freshFast), currentRound.keys)
        assertEquals(freshFast, SpeedTester.fastestCurrentEndpoint(currentRound))
    }
}
