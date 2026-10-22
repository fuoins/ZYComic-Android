package com.zycomic.app.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger

/** The runner compiles the production coordinator against explicitly isolated route/probe fixtures. */
class PicacgApiRuntimeRecoveryTest {
    @Test
    fun `offline and manual routes do not probe then three safe reads validate and switch without replay`() {
        val original = DomainIp("picacg-api.test", "198.51.100.70")
        val alternate = DomainIp("picacg-next.test", "198.51.100.71")
        PicacgRouteManager.apiEndpoint = original
        PicacgRouteManager.apiCandidates = listOf(PicacgRouteManager.ApiRecoveryCandidate("https://${alternate.domain}/", alternate))
        PicacgSpeedTester.apiProbeResults[alternate] = true
        val calls = AtomicInteger()
        var status: Int? = null
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .addInterceptor(PicacgApiRuntimeRecoveryInterceptor())
            .addInterceptor { chain ->
                calls.incrementAndGet()
                val code = status ?: throw ConnectException("offline fixture")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").body("{}".toResponseBody()).build()
            }.build()
        fun read(method: String) {
            val request = Request.Builder().url("https://${original.domain}/comic/pages").method(method, null).build()
            runCatching { client.newCall(request).execute().use { it.body?.string() } }
        }

        RuntimeNetworkGate.setOnline(false)
        repeat(3) { read("GET") }
        assertEquals(0, PicacgSpeedTester.apiProbeCalls.get())
        RuntimeNetworkGate.setOnline(true)
        PicacgRouteManager.manualApiPinned = true
        repeat(3) { read("HEAD") }
        assertEquals(0, PicacgSpeedTester.apiProbeCalls.get())
        PicacgRouteManager.manualApiPinned = false
        status = 401
        read("GET") // An HTTP/auth response resets the connection streak and never causes recovery.
        status = null
        repeat(2) { read("GET") }
        assertEquals(0, PicacgSpeedTester.apiProbeCalls.get())
        read("HEAD")
        val deadline = System.nanoTime() + 2_000_000_000L
        while (PicacgRouteManager.apiSwitches.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(1, PicacgSpeedTester.apiProbeCalls.get())
        assertEquals(1, PicacgRouteManager.apiSwitches.get())
        assertEquals(alternate, PicacgRouteManager.apiEndpoint)
        assertEquals(10, calls.get(), "Each caller request executes exactly once; recovery never replays it")
        assertTrue(PicacgApiRuntimeRecovery.isProbeAllowed(alternate))
    }
}
