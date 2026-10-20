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
    fun apiRootAcceptsHttp200Through499Including401() {
        assertTrue(PicacgNetworkPolicy.apiRootReachable(200))
        assertTrue(PicacgNetworkPolicy.apiRootReachable(401))
        assertTrue(PicacgNetworkPolicy.apiRootReachable(499))
        assertFalse(PicacgNetworkPolicy.apiRootReachable(199))
        assertFalse(PicacgNetworkPolicy.apiRootReachable(100))
        assertFalse(PicacgNetworkPolicy.apiRootReachable(500))
        assertFalse(PicacgNetworkPolicy.apiRootReachable(503))
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
