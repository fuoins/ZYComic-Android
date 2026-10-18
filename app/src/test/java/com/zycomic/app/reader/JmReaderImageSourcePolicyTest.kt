package com.zycomic.app.reader

import com.zycomic.app.net.DomainIp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JmReaderImageSourcePolicyTest {
    @Test
    fun `successful measured domains rank by delay before unmeasured domains`() {
        val domains = listOf("slow.example", "not-in-pool.example", "fast.example", "failed.example")
        val ranked = JmReaderImageSourcePolicy.rankedDomains(
            domains,
            mapOf("SLOW.EXAMPLE" to 120L, "fast.example" to 15L, "failed.example" to Long.MAX_VALUE),
        )

        assertEquals(listOf("fast.example", "slow.example", "not-in-pool.example", "failed.example"), ranked)
        assertEquals(domains.toSet(), ranked.toSet())
    }

    @Test
    fun `delay lookup is case insensitive`() {
        assertEquals(25L, JmReaderImageSourcePolicy.delayFor("Images.Example", mapOf("images.example" to 25L)))
    }

    @Test
    fun `successful per IP endpoints rank individually and failed addresses remain visible`() {
        val slowIp = DomainIp("images.example", "203.0.113.8")
        val fastIp = DomainIp("images.example", "203.0.113.7")
        val failedIp = DomainIp("backup.example", "198.51.100.9")
        val delays = mapOf(slowIp to 55L, fastIp to 12L, failedIp to Long.MAX_VALUE)
        val successfulEndpoints = setOf(slowIp, fastIp)

        assertEquals(
            listOf(fastIp, slowIp, failedIp),
            JmReaderImageSourcePolicy.rankedEndpoints(delays, successfulEndpoints, listOf("backup.example", "images.example")),
        )
        assertEquals(12L, JmReaderImageSourcePolicy.delayFor(DomainIp("IMAGES.EXAMPLE", "203.0.113.7"), delays))
    }

    @Test
    fun `foreign platform endpoints never appear in the JM image pool`() {
        val jm = DomainIp("jm.example", "192.0.2.1")
        val manwa = DomainIp("manwa.example", "192.0.2.2")
        assertEquals(
            listOf(jm),
            JmReaderImageSourcePolicy.rankedEndpoints(mapOf(jm to 20L, manwa to 1L), setOf(jm, manwa), listOf("jm.example")),
        )
    }
}
