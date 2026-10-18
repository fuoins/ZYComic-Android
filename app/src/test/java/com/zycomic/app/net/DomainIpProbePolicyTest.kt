package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DomainIpProbePolicyTest {
    @Test
    fun `maps all distinct concrete addresses to original hostname`() {
        val endpoints = DomainIpProbePolicy.endpoints(
            "Images.Example",
            listOf(" 203.0.113.7 ", "203.0.113.8", "203.0.113.7", ""),
        )

        assertEquals(
            listOf(
                DomainIp("Images.Example", "203.0.113.7"),
                DomainIp("Images.Example", "203.0.113.8"),
            ),
            endpoints,
        )
    }

    @Test
    fun `blank hostname yields no probe candidates`() {
        assertEquals(emptyList<DomainIp>(), DomainIpProbePolicy.endpoints("  ", listOf("203.0.113.7")))
    }
}
