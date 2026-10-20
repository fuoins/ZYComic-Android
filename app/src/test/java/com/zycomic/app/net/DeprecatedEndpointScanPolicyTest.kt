package com.zycomic.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeprecatedEndpointScanPolicyTest {
    private val line = DeprecatedEndpointScanPolicy.Kind.LINE
    private val image = DeprecatedEndpointScanPolicy.Kind.IMAGE

    @Test
    fun `all configured IPs are probed and all explicit failures classify as fully failed`() = runBlocking {
        val configured = listOf("192.0.2.1", "192.0.2.2", "192.0.2.3")
        val probed = mutableListOf<String>()
        val result = scan(target(line, "line.example", configured)) { _, _, ip ->
            probed += ip
            DeprecatedEndpointScanPolicy.ProbeResult.EXPLICIT_FAILURE
        }.single()

        assertEquals(configured, probed)
        assertEquals(DeprecatedEndpointScanPolicy.State.FULLY_FAILED, result.state)
        assertEquals(configured.size, result.configuredIpCount)
        assertEquals(configured.size, result.probedIpCount)
        assertEquals(configured.size, result.failureCount)
        assertEquals(DeprecatedEndpointScanPolicy.Reason.ALL_FAILED, result.reason)
    }

    @Test
    fun `a success and other explicit failures never classify the host as deprecated`() = runBlocking {
        val result = scan(target(line, "line.example", listOf("192.0.2.1", "192.0.2.2"))) { _, _, ip ->
            if (ip == "192.0.2.1") DeprecatedEndpointScanPolicy.ProbeResult.SUCCESS else DeprecatedEndpointScanPolicy.ProbeResult.EXPLICIT_FAILURE
        }.single()

        assertEquals(DeprecatedEndpointScanPolicy.State.HAS_SUCCESS, result.state)
        assertEquals(2, result.probedIpCount)
        assertEquals(1, result.successCount)
        assertEquals(1, result.failureCount)
    }

    @Test
    fun `an incomplete probe remains undetermined even when another IP succeeded`() = runBlocking {
        val result = scan(target(line, "line.example", listOf("192.0.2.1", "192.0.2.2", "192.0.2.3"))) { _, _, ip ->
            when (ip) {
                "192.0.2.1" -> DeprecatedEndpointScanPolicy.ProbeResult.INCOMPLETE
                "192.0.2.2" -> DeprecatedEndpointScanPolicy.ProbeResult.SUCCESS
                else -> DeprecatedEndpointScanPolicy.ProbeResult.EXPLICIT_FAILURE
            }
        }.single()

        assertEquals(DeprecatedEndpointScanPolicy.State.UNDETERMINED, result.state)
        assertEquals(3, result.probedIpCount)
        assertEquals(1, result.successCount)
        assertEquals(1, result.failureCount)
        assertEquals(DeprecatedEndpointScanPolicy.Reason.INCOMPLETE_PROBE, result.reason)
    }

    @Test
    fun `missing IP and missing real image sample remain undetermined without probing`() = runBlocking {
        val noIp = target(line, "line.example", emptyList())
        val noSample = target(image, "image.example", listOf("192.0.2.4"), DeprecatedEndpointScanPolicy.Reason.MISSING_IMAGE_SAMPLE)
        var calls = 0

        val results = DeprecatedEndpointScanPolicy.scan(
            targets = listOf(noIp, noSample),
            isConfigurationCurrent = { true },
            probe = { _, _, _ ->
                calls++
                DeprecatedEndpointScanPolicy.ProbeResult.EXPLICIT_FAILURE
            },
        )

        assertEquals(0, calls)
        assertEquals(DeprecatedEndpointScanPolicy.Reason.MISSING_IP, results[0].reason)
        assertEquals(DeprecatedEndpointScanPolicy.Reason.MISSING_IMAGE_SAMPLE, results[1].reason)
        assertTrue(results.all { it.state == DeprecatedEndpointScanPolicy.State.UNDETERMINED })
        assertEquals(0, results[1].probedIpCount)
    }

    @Test
    fun `incomplete results are counted per configured IP and exceptions are incomplete`() = runBlocking {
        val result = scan(target(line, "line.example", listOf("192.0.2.1", "192.0.2.2", "192.0.2.3", "192.0.2.4"))) { _, _, ip ->
            when (ip) {
                "192.0.2.1", "192.0.2.2" -> DeprecatedEndpointScanPolicy.ProbeResult.INCOMPLETE
                "192.0.2.3" -> throw IllegalStateException("probe failed unexpectedly")
                else -> DeprecatedEndpointScanPolicy.ProbeResult.EXPLICIT_FAILURE
            }
        }.single()

        assertEquals(DeprecatedEndpointScanPolicy.State.UNDETERMINED, result.state)
        assertEquals(4, result.configuredIpCount)
        assertEquals(4, result.probedIpCount)
        assertEquals(0, result.successCount)
        assertEquals(1, result.failureCount)
        assertEquals(DeprecatedEndpointScanPolicy.Reason.INCOMPLETE_PROBE, result.reason)
    }

    @Test
    fun `cancellation propagates even when probe catches cancellation`() {
        val cancellation = CancellationException("cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                scan(target(line, "line.example", listOf("192.0.2.1"))) { _, _, _ ->
                    try {
                        currentCoroutineContext().cancel(cancellation)
                        currentCoroutineContext().ensureActive()
                    } catch (_: CancellationException) {
                        // Simulate a probe implementation that swallows cancellation.
                    }
                    DeprecatedEndpointScanPolicy.ProbeResult.INCOMPLETE
                }
            }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `configuration change invalidates all prior results as one unknown scan`() = runBlocking {
        var checks = 0
        val results = DeprecatedEndpointScanPolicy.scan(
            targets = listOf(
                target(line, "line.example", listOf("192.0.2.1")),
                target(image, "image.example", listOf("192.0.2.2")),
            ),
            isConfigurationCurrent = { ++checks < 4 },
            probe = { _, _, _ -> DeprecatedEndpointScanPolicy.ProbeResult.EXPLICIT_FAILURE },
        )

        assertEquals(2, results.size)
        assertTrue(results.all { it.state == DeprecatedEndpointScanPolicy.State.UNDETERMINED })
        assertTrue(results.all { it.reason == DeprecatedEndpointScanPolicy.Reason.CONFIGURATION_CHANGED })
        assertTrue(results.all { it.probedIpCount == 0 })
    }

    @Test
    fun `separate scans stay platform scoped and do not mutate selected route state`() = runBlocking {
        val routeSource = mutableListOf("192.0.2.10")
        val originalRouteSource = routeSource.toList()
        val platformTargets = mapOf(
            "alpha" to listOf(target(line, "alpha.example", routeSource.toList())),
            "beta" to listOf(target(image, "beta.example", listOf("192.0.2.20"))),
        )
        val probedByPlatform = mutableMapOf<String, MutableList<String>>()

        platformTargets.forEach { (platform, targets) ->
            DeprecatedEndpointScanPolicy.scan(
                targets = targets,
                isConfigurationCurrent = { true },
                probe = { _, _, ip ->
                    probedByPlatform.getOrPut(platform) { mutableListOf() } += ip
                    DeprecatedEndpointScanPolicy.ProbeResult.SUCCESS
                },
            )
        }

        assertEquals(mapOf("alpha" to listOf("192.0.2.10"), "beta" to listOf("192.0.2.20")), probedByPlatform)
        assertEquals(originalRouteSource, routeSource)
        assertFalse(routeSource.isEmpty())
    }

    private suspend fun scan(
        target: DeprecatedEndpointScanPolicy.Target,
        probe: suspend (DeprecatedEndpointScanPolicy.Kind, String, String) -> DeprecatedEndpointScanPolicy.ProbeResult,
    ) = DeprecatedEndpointScanPolicy.scan(listOf(target), { true }, probe)

    private fun target(
        kind: DeprecatedEndpointScanPolicy.Kind,
        host: String,
        ips: List<String>,
        blockedReason: DeprecatedEndpointScanPolicy.Reason? = null,
    ) = DeprecatedEndpointScanPolicy.Target(kind, host, ips, blockedReason)
}
