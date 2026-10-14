package com.zycomic.app.net

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedTestLogJsonTest {

    @Test
    fun `encodes complete endpoint diagnostics and excludes request secrets`() {
        val line = DomainIp("line.example", "203.0.113.10")
        val imageOk = DomainIp("image.example", "198.51.100.10")
        val imageFailed = DomainIp("image.example", "198.51.100.11")
        val snapshot = RouteManager.SpeedTestSnapshot(
            lineDelays = mapOf(line to 42L),
            imgDelays = mapOf(imageOk to 91L, imageFailed to Long.MAX_VALUE),
            imgVerdicts = mapOf(
                imageOk to SpeedTester.ImgVerdict.OK,
                imageFailed to SpeedTester.ImgVerdict.NO_IMAGE,
            ),
            remoteVersion = 17,
            lineDiagnostics = mapOf(
                line to SpeedTestEndpointDiagnostic(
                    success = true,
                    latencyMs = 42L,
                    elapsedMs = 42L,
                    failureCategory = null,
                    attempts = listOf(
                        SpeedTestProbeAttempt(
                            "line_api",
                            true,
                            42L,
                            200,
                            "application/json",
                            null,
                            networkEvents = listOf(
                                SpeedTestNetworkEvent(
                                    event = "connect_start",
                                    phase = "tcp_connect",
                                    proxy = "DIRECT",
                                    socketAddress = "203.0.113.10:443",
                                ),
                            ),
                            sniDecisions = listOf(
                                SpeedTestSniDecision(
                                    host = "line.example",
                                    peerIp = "203.0.113.10",
                                    socketClass = "test.SSLSocket",
                                    configuredDomainMatch = true,
                                    cloudflareExempt = false,
                                    decision = "parameters_empty_after_set",
                                    parameterReadbackEmpty = true,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            imageDiagnostics = mapOf(
                imageOk to SpeedTestEndpointDiagnostic(
                    success = true,
                    latencyMs = 91L,
                    elapsedMs = 130L,
                    failureCategory = null,
                    imageVerdict = "ok",
                    fallbackAttempted = true,
                    attempts = listOf(
                        SpeedTestProbeAttempt("fixed_cover", false, 39L, 404, "text/html", SpeedTestFailureCategory.HTTP_STATUS),
                        SpeedTestProbeAttempt("fallback_sample", true, 91L, 206, "image/jpeg", null),
                    ),
                ),
                imageFailed to SpeedTestEndpointDiagnostic(
                    success = false,
                    latencyMs = null,
                    elapsedMs = 57L,
                    failureCategory = SpeedTestFailureCategory.INVALID_IMAGE,
                    imageVerdict = "no_image",
                    fallbackAttempted = true,
                    attempts = listOf(
                        SpeedTestProbeAttempt("fixed_cover", false, 27L, 200, "text/html", SpeedTestFailureCategory.INVALID_IMAGE),
                        SpeedTestProbeAttempt("fallback_sample", false, 30L, 200, "text/html", SpeedTestFailureCategory.INVALID_IMAGE),
                    ),
                ),
            ),
            startedAtEpochMs = 1_800_000_000_000L,
            completedAtEpochMs = 1_800_000_001_000L,
            networkPath = SpeedTestNetworkPath(
                followSystemVpnEnabled = true,
                systemVpnActive = false,
                directStrategy = true,
                androidApiLevel = 35,
                androidRelease = "15",
            ),
            isComplete = true,
        )

        val json = SpeedTestLogJson.encode(snapshot, "1.0.5")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        val endpoints = root.getValue("endpoints").jsonArray
        val imageRecord = endpoints.first { it.jsonObject.getValue("domain").jsonPrimitive.content == "image.example" }.jsonObject
        val summary = root.getValue("summary").jsonObject

        assertEquals(2, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("1.0.5", root.getValue("appVersion").jsonPrimitive.content)
        assertEquals(17, root.getValue("remoteConfigVersion").jsonPrimitive.content.toInt())
        assertEquals("direct", root.getValue("networkPath").jsonObject.getValue("mode").jsonPrimitive.content)
        assertFalse(root.getValue("networkPath").jsonObject.getValue("sniWireObserved").jsonPrimitive.content.toBoolean())
        assertEquals(35, root.getValue("networkPath").jsonObject.getValue("androidApiLevel").jsonPrimitive.content.toInt())
        assertEquals(3, endpoints.size)
        assertEquals(1, summary.getValue("line").jsonObject.getValue("successfulEndpoints").jsonPrimitive.content.toInt())
        assertEquals(1, summary.getValue("image").jsonObject.getValue("successfulDomains").jsonPrimitive.content.toInt())
        assertTrue(imageRecord.getValue("fallbackAttempted").jsonPrimitive.content.toBoolean())
        assertEquals(2, imageRecord.getValue("attempts").jsonArray.size)
        assertEquals("image/jpeg", imageRecord.getValue("contentType").jsonPrimitive.content)
        val lineRecord = endpoints.first { it.jsonObject.getValue("type").jsonPrimitive.content == "line" }.jsonObject
        val lineAttempt = lineRecord.getValue("attempts").jsonArray.first().jsonObject
        assertEquals("203.0.113.10:443", lineAttempt.getValue("networkEvents").jsonArray.first().jsonObject.getValue("socketAddress").jsonPrimitive.content)
        assertFalse(lineAttempt.getValue("sniDecisions").jsonArray.first().jsonObject.getValue("wireObserved").jsonPrimitive.content.toBoolean())
        assertFalse(json.contains("X-Token", ignoreCase = true))
        assertFalse(json.contains("cookie", ignoreCase = true))
        assertFalse(json.contains("deviceid", ignoreCase = true))
        assertFalse(json.contains("responseBody", ignoreCase = true))
        assertFalse(json.contains("headers", ignoreCase = true))
    }

    @Test
    fun `rejects snapshots without complete diagnostic metadata`() {
        assertFalse(RouteManager.SpeedTestSnapshot().canExport)
        val partial = RouteManager.SpeedTestSnapshot(
            lineDelays = mapOf(DomainIp("partial.example", "203.0.113.1") to 15L),
            startedAtEpochMs = 1_800_000_000_000L,
            networkPath = SpeedTestNetworkPath(true, false, true),
            isComplete = false,
        )
        assertFalse(partial.canExport)
    }
}
