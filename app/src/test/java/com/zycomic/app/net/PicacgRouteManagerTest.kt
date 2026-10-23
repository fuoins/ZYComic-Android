package com.zycomic.app.net

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun excludesPublicWebsiteWithoutChangingRemoteInputOrDocumentedApiBinding() {
        val urls = listOf("https://picacomic.com/", "https://picaapi.picacomic.com/")
        val rules = mapOf(
            "picacomic.com" to listOf("198.51.100.20"),
            "picaapi.picacomic.com" to listOf("198.51.100.21"),
            "cdn.pica.example" to listOf("198.51.100.22"),
        )
        PicacgRouteManager.applyRemoteConfig(urls, listOf("cdn.pica.example"), rules, 3)

        assertEquals(listOf("https://picaapi.picacomic.com/"), PicacgRouteManager.apiUrls)
        assertEquals("https://picaapi.picacomic.com/", PicacgRouteManager.apiBaseUrl)
        assertFalse(PicacgRouteManager.selectApiUrl("https://picacomic.com/"))
        assertTrue(PicacgRouteManager.apiRouteRules()["picacomic.com"].isNullOrEmpty())
        assertEquals(listOf("198.51.100.21"), PicacgRouteManager.resolveIps("picaapi.picacomic.com"))
        val endpoint = DomainIp("picaapi.picacomic.com", "198.51.100.21")
        assertTrue(PicacgRouteManager.pinApiEndpoint(endpoint))
        val client = PicacgRouteManager.newPinnedClient(OkHttpClient(), endpoint)
        assertEquals(listOf("198.51.100.21"), client.dns.lookup(endpoint.domain).map { it.hostAddress })
        assertEquals(2, urls.size)
        assertTrue(rules.containsKey("picacomic.com"))
    }

    @Test
    fun emptyImageRankingsClearOldHealthAndCoverSuccessDoesNotPinReaderPages() {
        val host = "storage-b.go2778.com"
        val defaultIp = "192.0.2.30"
        val coverIp = "192.0.2.31"
        PicacgRouteManager.applyRemoteConfig(
            apiUrls = listOf("https://api.pica.example/"),
            imageHosts = listOf(host),
            rules = mapOf(host to listOf(defaultIp, coverIp)),
            version = 4,
        )
        assertTrue(PicacgRouteManager.selectImageHost(host, manual = false))

        assertTrue(PicacgRouteManager.applyImageRankings(listOf(DomainIp(host, coverIp)), emptyList()))
        assertEquals(listOf(DomainIp(host, coverIp)), PicacgRouteManager.state.value.coverRanking)
        assertTrue(PicacgRouteManager.state.value.contentRanking.isEmpty())
        assertNull(PicacgRouteManager.selectedImageEndpoint)
        assertEquals(
            listOf(defaultIp, coverIp),
            PicacgRouteManager.dns().lookup(host).map { it.hostAddress },
            "a cover-only measurement cannot become the storage-b reader-page pin",
        )

        assertTrue(PicacgRouteManager.applyImageRankings(emptyList(), emptyList()))
        assertTrue(PicacgRouteManager.state.value.coverRanking.isEmpty())
        assertTrue(PicacgRouteManager.state.value.contentRanking.isEmpty())
        assertNull(PicacgRouteManager.selectedImageEndpoint)
    }

    @Test
    fun roundStartClearsAutomaticPinsButRetainsManualPreferencesAndPins() {
        installRoutes()
        assertTrue(PicacgRouteManager.selectApiUrl("https://api.pica.example/v2/root", manual = false))
        assertTrue(PicacgRouteManager.selectImageHost("cdn.pica.example", manual = false))
        val automaticApi = DomainIp("api.pica.example", "198.51.100.10")
        val automaticImage = DomainIp("cdn.pica.example", "198.51.100.11")
        assertTrue(PicacgRouteManager.applyAutomaticApiEndpoint(automaticApi))
        assertTrue(PicacgRouteManager.applyImageRankings(listOf(automaticImage), listOf(automaticImage)))

        PicacgRouteManager.clearAutomaticHealthSelections()

        assertNull(PicacgRouteManager.selectedApiEndpoint)
        assertNull(PicacgRouteManager.selectedImageEndpoint)
        assertTrue(PicacgRouteManager.state.value.coverRanking.isEmpty())
        assertTrue(PicacgRouteManager.state.value.contentRanking.isEmpty())

        assertTrue(PicacgRouteManager.pinApiEndpoint(automaticApi))
        assertTrue(PicacgRouteManager.selectImageHost("cdn.pica.example", manual = true))
        assertTrue(PicacgRouteManager.pinImageEndpoint(automaticImage))
        PicacgRouteManager.clearAutomaticHealthSelections()

        assertTrue(PicacgRouteManager.isApiManuallyPinned)
        assertTrue(PicacgRouteManager.isImageManuallyPinned)
        assertEquals(automaticApi, PicacgRouteManager.selectedApiEndpoint)
        assertEquals(automaticImage, PicacgRouteManager.selectedImageEndpoint)

        PicacgRouteManager.selectApiUrl("https://api.pica.example/v2/root", manual = false)
        PicacgRouteManager.selectImageHost("cdn.pica.example", manual = false)
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
