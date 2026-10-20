package com.zycomic.app.net

import com.zycomic.app.net.DeprecatedEndpointScanPolicy.Kind
import com.zycomic.app.net.DeprecatedEndpointScanPolicy.ProbeResult
import com.zycomic.app.net.DeprecatedEndpointScanPolicy.Reason
import com.zycomic.app.net.DeprecatedEndpointScanPolicy.State
import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeprecatedEndpointScannerTest {
    private fun configuration(
        lineIps: List<String> = listOf("192.0.2.1", "192.0.2.2"),
        imageIps: List<String> = listOf("192.0.2.3"),
        sample: String? = "/real.jpg",
    ) = DeprecatedEndpointScanner.Snapshot(
        revision = 1L,
        version = 1,
        lines = listOf("shared.example" to "https://shared.example"),
        images = listOf("shared.example"),
        lineRules = mapOf("shared.example" to lineIps),
        imageRules = mapOf("shared.example" to imageIps),
        imageSample = sample,
    )

    @Test
    fun `same host retains separate line and image mappings and probes every configured IP`() = runBlocking {
        val config = configuration()
        val calls = mutableListOf<Pair<Kind, String>>()
        val result = DeprecatedEndpointScanner.scanReadOnly(
            platform = ContentPlatform.JM,
            readSnapshot = { config },
            probe = { _, kind, _, ip ->
                calls += kind to ip
                ProbeResult.EXPLICIT_FAILURE
            },
        )
        assertEquals(listOf(Kind.LINE to "192.0.2.1", Kind.LINE to "192.0.2.2", Kind.IMAGE to "192.0.2.3"), calls)
        assertTrue(result.entries.all { it.state == State.FULLY_FAILED })
    }

    @Test
    fun `missing IP and sample never invoke a probe and are undetermined`() = runBlocking {
        var calls = 0
        val config = configuration(lineIps = emptyList(), sample = null)
        val result = DeprecatedEndpointScanner.scanReadOnly(
            ContentPlatform.MANWA,
            { config },
            probe = { _, _, _, _ ->
                calls++
                ProbeResult.EXPLICIT_FAILURE
            },
        )
        assertEquals(0, calls)
        assertEquals(listOf(Reason.MISSING_IP, Reason.MISSING_IMAGE_SAMPLE), result.entries.map { it.reason })
        assertTrue(result.entries.all { it.state == State.UNDETERMINED })
    }

    @Test
    fun `non literal mapping is not resolved or classified as failure`() = runBlocking {
        val config = configuration(lineIps = listOf("extra.example"), imageIps = listOf("bad:ipv6:host"))
        var calls = 0
        val result = DeprecatedEndpointScanner.scanReadOnly(
            ContentPlatform.PICACG,
            { config },
            probe = { _, _, _, _ ->
                calls++
                ProbeResult.EXPLICIT_FAILURE
            },
        )
        assertEquals(0, calls)
        assertTrue(result.entries.all { it.reason == Reason.INVALID_IP_MAPPING && it.state == State.UNDETERMINED })
    }

    @Test
    fun `configuration changes invalidate already failed entries and stop probing`() = runBlocking {
        var config = configuration()
        var calls = 0
        val result = DeprecatedEndpointScanner.scanReadOnly(
            ContentPlatform.JM,
            { config },
            probe = { _, _, _, _ ->
                calls++
                config = config.copy(revision = 2)
                ProbeResult.EXPLICIT_FAILURE
            },
        )
        assertEquals(1, calls)
        assertTrue(result.configurationChanged)
        assertTrue(result.entries.all { it.state == State.UNDETERMINED && it.reason == Reason.CONFIGURATION_CHANGED })
    }

    @Test
    fun `each platform receives only its own configured IPs even when hostnames match`() = runBlocking {
        ContentPlatform.entries.forEachIndexed { index, platform ->
            val ip = "192.0.2.${index + 10}"
            val config = configuration(lineIps = listOf(ip), imageIps = listOf(ip))
            val calls = mutableListOf<String>()
            val result = DeprecatedEndpointScanner.scanReadOnly(
                platform,
                { config },
                probe = { _, _, _, address ->
                    calls += address
                    ProbeResult.SUCCESS
                },
            )
            assertEquals(platform, result.platform)
            assertEquals(listOf(ip, ip), calls)
            assertTrue(result.entries.all { it.state == State.HAS_SUCCESS })
        }
    }

    @Test
    fun `query leaves route source and configuration untouched`() = runBlocking {
        val config = configuration()
        val selectedRoute = DomainIp("selected.example", "192.0.2.100")
        val selectedSource = DomainIp("image.example", "192.0.2.101")
        val before = Triple(config, selectedRoute, selectedSource)
        DeprecatedEndpointScanner.scanReadOnly(
            ContentPlatform.MANWA,
            { config },
            probe = { _, _, _, _ -> ProbeResult.EXPLICIT_FAILURE },
        )
        assertEquals(before, Triple(config, selectedRoute, selectedSource))
    }

    @Test
    fun `a network strategy change makes this run undetermined`() = runBlocking {
        var config = configuration()
        val result = DeprecatedEndpointScanner.scanReadOnly(
            ContentPlatform.PICACG,
            { config },
            probe = { _, _, _, _ ->
                config = config.copy(strategy = "network changed")
                ProbeResult.SUCCESS
            },
        )
        assertTrue(result.configurationChanged)
        assertTrue(result.entries.all { it.state == State.UNDETERMINED })
    }
}
