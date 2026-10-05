package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SpeedTester {

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val baseClient: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun pinnedClient(domain: String, ip: String): OkHttpClient =
        baseClient.newBuilder()
            .dns { hostname ->
                if (hostname.equals(domain, ignoreCase = true)) listOf(InetAddress.getByName(ip))
                else Dns.SYSTEM.lookup(hostname)
            }
            .build()

    private suspend fun measureUrl(url: String, domain: String, ip: String): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        val start = System.nanoTime()
        try {
            pinnedClient(domain, ip).newCall(req).execute().use { it.body?.bytes() }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private suspend fun measureLine(lineUrl: String, domain: String): Long = coroutineScope {
        val ips = RouteManager.resolveIp(domain).ifEmpty { listOf(domain) }
        val results = ips.map { ip ->
            async { measureUrl("$lineUrl/api/index/index?facility=android&deviceid=speedtest&timestamp=${System.currentTimeMillis()}", domain, ip) }
        }.awaitAll()
        val best = results.minOrNull() ?: Long.MAX_VALUE
        if (best < Long.MAX_VALUE) RouteManager.setFastestIp(domain, ips[results.indexOf(best)])
        best
    }

    private suspend fun measureImg(domain: String): Long = coroutineScope {
        val ips = RouteManager.resolveIp(domain).ifEmpty { listOf(domain) }
        val results = ips.map { ip ->
            async { measureUrl("https://$domain/", domain, ip) }
        }.awaitAll()
        val best = results.minOrNull() ?: Long.MAX_VALUE
        if (best < Long.MAX_VALUE) RouteManager.setFastestIp(domain, ips[results.indexOf(best)])
        best
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
