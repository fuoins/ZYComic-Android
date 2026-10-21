package com.zycomic.app.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PicacgNetworkGenerationTest {
    @Before
    fun initialize() = PicacgNetworkGeneration.restore(null)

    @After
    fun clear() = PicacgRouteManager.clearConfiguration()

    @Test
    fun firstFailedCheckStillFetchesOnceAndUnchangedChecksDoNotRepeat() = runBlocking {
        var calls = 0
        repeat(3) {
            PicacgNetworkGeneration.onConfigCheck {
                calls++
                listOf("104.16.146.184")
            }
        }
        assertEquals(1, calls)
        assertTrue(PicacgRouteManager.resolveIps("storage-b.picacomic.com").contains("104.16.146.184"))
        assertEquals(PicacgBuiltinNetwork.apiUrls, PicacgRouteManager.apiUrls)
        assertEquals(PicacgBuiltinNetwork.imageHosts, PicacgRouteManager.imageHosts)
    }

    @Test
    fun successfulNewVersionRebuildsPoolWithoutChangingFixedBaseline() = runBlocking {
        var calls = 0
        PicacgNetworkGeneration.onConfigCheck(5) {
            calls++
            listOf("104.16.146.184")
        }
        PicacgNetworkGeneration.onConfigCheck(5) {
            calls++
            error("same version must not fetch")
        }
        PicacgNetworkGeneration.onConfigCheck(4) {
            calls++
            error("older version must not fetch")
        }
        PicacgNetworkGeneration.onConfigCheck(6) {
            calls++
            listOf("104.17.102.145")
        }
        assertEquals(2, calls)
        assertEquals(6, PicacgRouteManager.remoteVersion)
        assertEquals(listOf("104.20.42.9", "172.66.173.71", "104.17.102.145"), PicacgRouteManager.resolveIps("picaapi.picacomic.com"))
        assertEquals(listOf("104.21.42.75", "172.67.159.59"), PicacgRouteManager.resolveIps("storage-b.go2778.com"))
    }

    @Test
    fun failedSmartDnsFallsBackAndDoesNotRetryAtSameVersion() = runBlocking {
        var calls = 0
        PicacgNetworkGeneration.onConfigCheck(5) {
            calls++
            throw IllegalStateException("offline")
        }
        PicacgNetworkGeneration.onConfigCheck(5) {
            calls++
            emptyList()
        }
        PicacgNetworkGeneration.onConfigCheck {
            calls++
            emptyList()
        }
        assertEquals(1, calls)
        assertEquals(listOf("104.20.42.9", "172.66.173.71"), PicacgRouteManager.resolveIps("storage-b.picacomic.com"))
    }

    @Test
    fun concurrentChecksFetchOnlyOnce() = runBlocking {
        var calls = 0
        coroutineScope {
            List(4) {
                async {
                    PicacgNetworkGeneration.onConfigCheck(5) {
                        calls++
                        delay(5)
                        listOf("104.16.146.184")
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, calls)
    }

    @Test
    fun onlyStorageRolesCanBeSelectedAndCoversRouteToImgproxyMirror() {
        assertFalse(PicacgRouteManager.selectImageHost("img.safedataplj.com"))
        assertTrue(PicacgRouteManager.selectImageHost("storage-b.safedataplj.com"))
        assertTrue(PicacgRouteManager.selectImageHost("storage-b.go2778.com"))
        // Cover thumbnails bypass storage-b's cross-domain 301 and go straight to the img mirror, prefix removed.
        assertEquals(
            "https://img.go2778.com/a%2Fb.jpg?x=%2F",
            PicacgRouteManager.rewriteImageUrl("https://storage-b.picacomic.com/static/tobeimg/a%2Fb.jpg?x=%2F"),
        )
        // Content pages keep the selected storage bucket and their raw path.
        assertEquals(
            "https://storage-b.go2778.com/static/tobs/sub_storage_1/x.jpg",
            PicacgRouteManager.rewriteImageUrl("https://storage-b.picacomic.com/static/tobs/sub_storage_1/x.jpg"),
        )
        // Already an imgproxy URL and out-of-pool avatars are left untouched.
        val redirect = "https://img.safedataplj.com/signature/rs:fill:300:400/image.jpg"
        assertEquals(redirect, PicacgRouteManager.rewriteImageUrl(redirect))
        val avatar = "https://storage1.picacomic.com/static/avatar.jpg"
        assertEquals(avatar, PicacgRouteManager.rewriteImageUrl(avatar))
    }

    @Test
    fun singleSelectedPinKeepsDefaultTlsAndRedirectDnsIsAvailable() {
        val endpoint = DomainIp("picaapi.picacomic.com", "104.20.42.9")
        assertTrue(PicacgRouteManager.applyAutomaticApiEndpoint(endpoint))
        assertEquals(listOf(endpoint.ip), PicacgRouteManager.dns().lookup(endpoint.domain).map { it.hostAddress })
        val base = OkHttpClient()
        val pinned = PicacgRouteManager.newPinnedClient(base, DomainIp("storage-b.go2778.com", "104.21.42.75"))
        assertEquals(listOf("104.21.75.42", "172.67.213.2"), pinned.dns.lookup("img.safedataplj.com").map { it.hostAddress })
        assertEquals(base.hostnameVerifier, pinned.hostnameVerifier)
        assertEquals(base.sslSocketFactory, pinned.sslSocketFactory)
        assertNotNull(PicacgRouteManager.selectedApiEndpoint)
        assertTrue(PicacgRouteManager.resolveIps("picacomic.com.evil.example").isEmpty())
        assertTrue(PicacgRouteManager.resolveIps("jm.example").isEmpty())
    }

    @Test
    fun fetchDecisionRequiresFirstCheckOrStrictlyHigherAppliedVersion() {
        assertTrue(PicacgNetworkGeneration.shouldFetch(false, null, null))
        assertFalse(PicacgNetworkGeneration.shouldFetch(true, 5, null))
        assertFalse(PicacgNetworkGeneration.shouldFetch(true, 5, 5))
        assertFalse(PicacgNetworkGeneration.shouldFetch(true, 5, 4))
        assertTrue(PicacgNetworkGeneration.shouldFetch(true, 5, 6))
    }
}
