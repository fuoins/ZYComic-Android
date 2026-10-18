package com.zycomic.app.reader

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
}
