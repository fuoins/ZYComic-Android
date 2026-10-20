package com.zycomic.app.net

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicacgRouteManagerTest {
    @After
    fun resetRoutes() {
        PicacgRouteManager.clearConfiguration()
    }

    @Test
    fun preservesFullApiBaseUrlAndKeepsApiAndImageRulesSeparate() {
        installRoutes()

        assertEquals("https://api.pica.example/v2/root/", PicacgRouteManager.apiBaseUrl)
        assertEquals(listOf("198.51.100.10"), PicacgRouteManager.resolveIps("api.pica.example"))
        assertEquals(listOf("198.51.100.11"), PicacgRouteManager.resolveIps("cdn.pica.example"))
        assertTrue(PicacgRouteManager.resolveIps("manwa.example").isEmpty())
    }

    @Test
    fun pinsOnlyConfiguredPicacgHostIpPairs() {
        installRoutes()

        assertTrue(PicacgRouteManager.pinApiEndpoint(DomainIp("api.pica.example", "198.51.100.10")))
        assertTrue(PicacgRouteManager.pinImageEndpoint(DomainIp("cdn.pica.example", "198.51.100.11")))
        assertFalse(PicacgRouteManager.pinApiEndpoint(DomainIp("manwa.example", "198.51.100.10")))
        assertFalse(PicacgRouteManager.pinImageEndpoint(DomainIp("cdn.pica.example", "198.51.100.12")))
        assertEquals("198.51.100.10", PicacgRouteManager.selectedApiEndpoint?.ip)
        assertEquals("198.51.100.11", PicacgRouteManager.selectedImageEndpoint?.ip)
    }

    @Test
    fun pinnedClientIsRestrictedToItsSelectedPicacgEndpoint() {
        installRoutes()
        val client = PicacgRouteManager.newPinnedClient(OkHttpClient(), DomainIp("api.pica.example", "198.51.100.10"))
        assertEquals(listOf("198.51.100.10"), client.dns.lookup("api.pica.example").map { it.hostAddress })
        assertFalse(client.retryOnConnectionFailure)
    }

    private fun installRoutes() = PicacgRouteManager.applyRemoteConfig(
        apiUrls = listOf("https://api.pica.example/v2/root"),
        imageHosts = listOf("cdn.pica.example"),
        rules = mapOf(
            "api.pica.example" to listOf("198.51.100.10"),
            "cdn.pica.example" to listOf("198.51.100.11"),
            "manwa.example" to listOf("203.0.113.1"),
        ),
        version = 2,
    )
}
