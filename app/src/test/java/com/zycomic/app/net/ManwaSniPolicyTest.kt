package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManwaSniPolicyTest {
    @Test
    fun `direct transport omits SNI only for configured non Cloudflare hosts`() {
        val configured = setOf("configured.example")

        assertTrue(ManwaSniPolicy.shouldOmitSni("CONFIGURED.EXAMPLE", configured, peerIsCloudflare = false))
        assertTrue(ManwaSniPolicy.shouldOmitSni(" configured.example. ", configured, peerIsCloudflare = false))
        assertFalse(ManwaSniPolicy.shouldOmitSni("other.example", configured, peerIsCloudflare = false))
        assertFalse(ManwaSniPolicy.shouldOmitSni("sub.configured.example", configured, peerIsCloudflare = false))
        assertFalse(ManwaSniPolicy.shouldOmitSni("configured.example", configured, peerIsCloudflare = true))
        assertFalse(ManwaSniPolicy.shouldOmitSni("", configured, peerIsCloudflare = false))
    }
}
