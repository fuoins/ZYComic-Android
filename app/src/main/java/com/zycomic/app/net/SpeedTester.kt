package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request

object SpeedTester {

    private suspend fun measure(url: String): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        val start = System.nanoTime()
        try {
            NetworkModule.newSpeedTestClient().newCall(req).execute().use { it.body?.bytes() }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    suspend fun testAllLines(): Map<Int, Long> = coroutineScope {
        RouteManager.lineHosts.mapIndexed { index, lineUrl ->
            async { index to measure("$lineUrl/api/index/index?facility=android&deviceid=speedtest&timestamp=${System.currentTimeMillis()}") }
        }.awaitAll().toMap()
    }

    suspend fun testAllImgHosts(): Map<Int, Long> = coroutineScope {
        RouteManager.imgDomains.mapIndexed { index, domain ->
            async { index to measure("https://$domain/") }
        }.awaitAll().toMap()
    }

    suspend fun testSingleImgHost(domain: String): Long = measure("https://$domain/")

    fun selectFastestLine(delays: Map<Int, Long>): Int =
        delays.filterValues { it < Long.MAX_VALUE }.minByOrNull { it.value }?.key ?: 0
}
