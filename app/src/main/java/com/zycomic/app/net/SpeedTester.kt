package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

object SpeedTester {

    private suspend fun measure(req: Request): Long = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            withTimeoutOrNull(5000) {
                NetworkModule.newSpeedTestClient().newCall(req).execute().use { it.body?.bytes() }
            } ?: return@withContext Long.MAX_VALUE
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun lineRequest(lineUrl: String): Request {
        val ts = System.currentTimeMillis().toString()
        val url = "$lineUrl/api/index/index?facility=android&deviceid=${ManwaInterceptor.DEVICE_ID}&timestamp=$ts"
        return Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", ManwaInterceptor.UA)
            .header("devid", ts)
            .header("X-Token", Crypto.md5Hex(ts + Crypto.XTOKEN_SALT))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", ManwaInterceptor.ACCEPT_LANGUAGE)
            .header("Origin", ManwaInterceptor.ORIGIN)
            .header("Referer", ManwaInterceptor.REFERER)
            .header("Connection", "keep-alive")
            .build()
    }

    private fun imgRequest(url: String): Request =
        Request.Builder().url(url).get().header("User-Agent", ManwaInterceptor.UA).build()

    suspend fun testAllLines(): Map<Int, Long> = coroutineScope {
        RouteManager.lineHosts.mapIndexed { index, lineUrl ->
            async { index to measure(lineRequest(lineUrl)) }
        }.awaitAll().toMap()
    }

    suspend fun testAllImgHosts(): Map<Int, Long> = coroutineScope {
        RouteManager.imgDomains.mapIndexed { index, domain ->
            async { index to measure(imgRequest("https://$domain/")) }
        }.awaitAll().toMap()
    }

    suspend fun testSingleImgHost(domain: String): Long = measure(imgRequest("https://$domain/"))

    suspend fun testSingleLine(lineUrl: String): Long = measure(lineRequest(lineUrl))

    fun selectFastestLine(delays: Map<Int, Long>): Int =
        delays.filterValues { it < Long.MAX_VALUE }.minByOrNull { it.value }?.key ?: 0
}
