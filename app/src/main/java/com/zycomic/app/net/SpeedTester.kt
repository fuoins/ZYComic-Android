package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Request
import java.net.InetAddress

object SpeedTester {

    private suspend fun measure(url: String, domain: String, ip: String?): Long = withContext(Dispatchers.IO) {
        val client = if (ip == null) {
            NetworkModule.newSpeedTestClient()
        } else {
            NetworkModule.newSpeedTestClient().newBuilder()
                .dns { hostname ->
                    if (hostname.equals(domain, ignoreCase = true)) listOf(InetAddress.getByName(ip))
                    else Dns.SYSTEM.lookup(hostname)
                }
                .build()
        }
        val req = Request.Builder().url(url).get().build()
        val start = System.nanoTime()
        try {
            client.newCall(req).execute().use { it.body?.bytes() }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private suspend fun measureLine(lineUrl: String, domain: String): Long = coroutineScope {
        val url = "$lineUrl/api/index/index?facility=android&deviceid=speedtest&timestamp=${System.currentTimeMillis()}"
        if (DevConfig.isProxyEnabled()) {
            val ips = RouteManager.resolveIp(domain).ifEmpty { listOf(domain) }
            val results = ips.map { ip -> async { measure(url, domain, ip) } }.awaitAll()
            val best = results.minOrNull() ?: Long.MAX_VALUE
            if (best < Long.MAX_VALUE) RouteManager.setFastestIp(domain, ips[results.indexOf(best)])
            best
        } else {
            measure(url, domain, null)
        }
    }

    private suspend fun measureImg(domain: String): Long = coroutineScope {
        val url = "https://$domain/"
        if (DevConfig.isProxyEnabled()) {
            val ips = RouteManager.resolveIp(domain).ifEmpty { listOf(domain) }
            val results = ips.map { ip -> async { measure(url, domain, ip) } }.awaitAll()
            val best = results.minOrNull() ?: Long.MAX_VALUE
            if (best < Long.MAX_VALUE) RouteManager.setFastestIp(domain, ips[results.indexOf(best)])
            best
        } else {
            measure(url, domain, null)
        }
    }

    suspend fun testAllLines(): Map<Int, Long> = coroutineScope {
        RouteManager.lineHosts.mapIndexed { index, lineUrl ->
            async {
                val domain = lineUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
                index to measureLine(lineUrl, domain)
            }
        }.awaitAll().toMap()
    }

    suspend fun testAllImgHosts(): Map<Int, Long> = coroutineScope {
        RouteManager.imgDomains.mapIndexed { index, domain ->
            async { index to measureImg(domain) }
        }.awaitAll().toMap()
    }

    fun selectFastestLine(delays: Map<Int, Long>): Int =
        delays.filterValues { it < Long.MAX_VALUE }.minByOrNull { it.value }?.key ?: 0
}
