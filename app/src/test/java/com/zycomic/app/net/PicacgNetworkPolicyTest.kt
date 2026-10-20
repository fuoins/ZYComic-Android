package com.zycomic.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicacgNetworkPolicyTest {
    @Test
    fun poolsMustBeProvidedAsAPair() {
        assertTrue(PicacgNetworkPolicy.poolsArePaired(false, false))
        assertTrue(PicacgNetworkPolicy.poolsArePaired(true, true))
        assertFalse(PicacgNetworkPolicy.poolsArePaired(true, false))
        assertFalse(PicacgNetworkPolicy.poolsArePaired(false, true))
    }

    @Test
    fun apiRootAcceptsHttp200Through499ExceptRedirectsIncluding401() {
        assertTrue(PicacgNetworkPolicy.apiRootReachable(200))
        assertTrue(PicacgNetworkPolicy.apiRootReachable(401))
        assertTrue(PicacgNetworkPolicy.apiRootReachable(499))
        assertFalse(PicacgNetworkPolicy.apiRootReachable(199))
        assertFalse(PicacgNetworkPolicy.apiRootReachable(100))
        assertFalse(PicacgNetworkPolicy.apiRootReachable(500))
        assertFalse(PicacgNetworkPolicy.apiRootReachable(503))
    }

    @Test
    fun apiProbeNeverTreatsAnyRedirectAsSuccess() {
        (300..399).forEach { assertFalse("HTTP $it", PicacgNetworkPolicy.apiRootReachable(it)) }
    }

    @Test
    fun websiteRootIsExcludedButDocumentedApiAndConfiguredAlternateHostsRemain() {
        assertFalse(PicacgNetworkPolicy.apiBaseAllowed("https://picacomic.com/"))
        assertFalse(PicacgNetworkPolicy.apiBaseAllowed("https://PICACOMIC.COM"))
        assertFalse(PicacgNetworkPolicy.apiBaseAllowed("http://www.picacomic.com/"))
        assertTrue(PicacgNetworkPolicy.apiBaseAllowed("https://picaapi.picacomic.com/"))
        assertTrue(PicacgNetworkPolicy.apiBaseAllowed("https://configured-api.example/api/"))
        assertFalse(PicacgNetworkPolicy.apiBaseAllowed("not a URL"))
        assertFalse(PicacgNetworkPolicy.apiBaseAllowed("https://user:secret@picaapi.picacomic.com/"))
    }

    @Test
    fun imageRequires200ImageContentTypeAndSuccessfulDecodeTogether() {
        assertTrue(PicacgNetworkPolicy.imageSampleVerified(200, "image/jpeg", true))
        assertTrue(PicacgNetworkPolicy.imageSampleVerified(200, "IMAGE/WEBP; charset=binary", true))
        assertFalse(PicacgNetworkPolicy.imageSampleVerified(200, "text/html", true))
        assertFalse(PicacgNetworkPolicy.imageSampleVerified(404, "image/jpeg", true))
        assertFalse(PicacgNetworkPolicy.imageSampleVerified(200, "image/png", false))
        assertFalse(PicacgNetworkPolicy.imageSampleVerified(200, null, true))
    }
}
