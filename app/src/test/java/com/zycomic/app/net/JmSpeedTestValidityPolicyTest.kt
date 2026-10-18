package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmSpeedTestValidityPolicyTest {
    private val lines = setOf("api.example")
    private val images = setOf("img.example")
    private val lineDelays = mapOf("api.example" to 42L)
    private val imageDelays = mapOf("img.example" to 80L)

    private fun valid(
        snapshotVersion: Int? = 7,
        fingerprint: String? = "cfg-a",
        lineCandidates: Set<String> = lines,
        imageCandidates: Set<String> = images,
        lineValues: Map<String, Long> = lineDelays,
        imageValues: Map<String, Long> = imageDelays,
        completed: Long? = 200L,
    ) = JmSpeedTestValidityPolicy.isValid(
        snapshotVersion = snapshotVersion,
        currentVersion = 7,
        snapshotFingerprint = fingerprint,
        currentFingerprint = "cfg-a",
        snapshotLineCandidates = lineCandidates,
        currentLineCandidates = lines,
        snapshotImageCandidates = imageCandidates,
        currentImageCandidates = images,
        lineDelays = lineValues,
        imageDelays = imageValues,
        startedAtEpochMs = 100L,
        completedAtEpochMs = completed,
    )

    @Test
    fun `accepts exact version candidate set rule fingerprint and complete results`() {
        assertTrue(valid())
    }

    @Test
    fun `rejects version candidates and rules fingerprint changes`() {
        assertFalse(valid(snapshotVersion = 6))
        assertFalse(valid(lineCandidates = setOf("other.example")))
        assertFalse(valid(imageCandidates = setOf("other.example")))
        assertFalse(valid(fingerprint = "cfg-rules-changed"))
    }

    @Test
    fun `rejects partial future malformed or negative-delay snapshots`() {
        assertFalse(valid(lineValues = emptyMap()))
        assertFalse(valid(imageValues = mapOf("img.example" to -1L)))
        assertFalse(valid(completed = 99L))
        assertFalse(valid(completed = null))
    }

    private fun reusableImages(
        fingerprint: String? = "images-and-ips",
        network: String? = "direct",
        delays: Map<String, Long> = imageDelays,
        completed: Long? = 200L,
    ) = JmSpeedTestValidityPolicy.canReuseImages(
        fingerprint,
        "images-and-ips",
        network,
        "direct",
        delays,
        images,
        100L,
        completed,
    )

    @Test
    fun `image reuse does not depend on API lines or remote version`() {
        assertTrue(reusableImages())
        assertTrue(reusableImages(delays = mapOf("img.example" to Long.MAX_VALUE)))
    }

    @Test
    fun `image cache requires matching hosts IP rules transport and complete results`() {
        assertFalse(reusableImages(fingerprint = null))
        assertFalse(reusableImages(fingerprint = "changed-image-ip"))
        assertFalse(reusableImages(network = "vpn"))
        assertFalse(reusableImages(delays = emptyMap()))
        assertFalse(reusableImages(delays = mapOf("other.example" to 80L)))
        assertFalse(reusableImages(delays = mapOf("img.example" to -1L)))
        assertFalse(reusableImages(completed = null))
        assertFalse(reusableImages(completed = 99L))
    }
}
