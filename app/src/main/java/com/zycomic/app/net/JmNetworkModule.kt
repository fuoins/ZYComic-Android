package com.zycomic.app.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** JM 专用网络栈：独立 CookieJar、Dispatcher、连接池、协议拦截器和 host 路由。 */
object JmNetworkModule {

    private val cookieJar = JmCookieJar()
    private val confirmMutex = Mutex()
    private val confirmedHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var selectedImageEndpoint: DomainIp? = null

    val selectedImageIp: String?
        get() = selectedImageEndpoint?.takeIf { endpoint ->
            endpoint.domain.equals(JmRouteManager.activeImageDomain, true) &&
                endpoint in JmRouteManager.speedTestSnapshot.value.imageIpDelaysMs
        }?.ip

    private val jmDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            selectedImageEndpoint?.takeIf { endpoint ->
                endpoint.domain.equals(hostname, ignoreCase = true) &&
                    JmRouteManager.imageDomains.any { it.equals(endpoint.domain, true) } &&
                    endpoint in JmRouteManager.speedTestSnapshot.value.imageIpDelaysMs
            }?.let { endpoint ->
                return listOf(InetAddress.getByName(endpoint.ip))
            }
            val configured = JmRouteManager.resolveIp(hostname)
            if (configured.isNotEmpty()) {
                return configured.mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
                    .takeIf { it.isNotEmpty() }
                    ?: throw java.net.UnknownHostException("JM configured DNS has no valid address: $hostname")
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun createBaseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            },
        )
        .cookieJar(cookieJar)
        .dns(jmDns)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()

    private val baseClient: OkHttpClient by lazy(::createBaseClient)
    val client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .addInterceptor(JmApiInterceptor())
            .addInterceptor(JmImageInterceptor())
            .build()
    }

    /** A probe client with its own pool and no API/image retry interceptors. */
    fun newProbeClient(): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Pin only an active JM sources2 hostname to its measured address; URL host/SNI remain unchanged. */
    fun selectImageEndpoint(domain: String, ip: String): Boolean {
        if (JmRouteManager.imageDomains.none { it.equals(domain, ignoreCase = true) }) return false
        if (ip.isBlank()) return false
        if (JmRouteManager.speedTestSnapshot.value.imageIpDelaysMs.keys.none {
                it.domain.equals(domain, true) && it.ip.equals(ip.trim(), true)
            }
        ) {
            return false
        }
        selectedImageEndpoint = DomainIp(domain, ip.trim())
        return true
    }

    /** Forget the manual per-IP override and resume the configured JM DNS result. */
    fun clearSelectedImageEndpoint() {
        selectedImageEndpoint = null
    }

    /** The JM client has a dedicated pool; evict old sockets after changing a hostname's pinned IP. */
    fun rebindImageEndpoint() {
        runCatching { baseClient.connectionPool.evictAll() }
    }

    /** /confirm is the only setup request; its cookies remain confined to [cookieJar]. */
    suspend fun ensureAgeCookie() {
        val base = JmRouteManager.baseUrl.trimEnd('/')
        val host = runCatching { java.net.URI(base).host.orEmpty() }.getOrDefault("")
        if (base.isBlank() || host.isBlank() || host in confirmedHosts) return
        confirmMutex.withLock {
            if (host in confirmedHosts) return
            val request = Request.Builder()
                .url("$base/confirm")
                .get()
                .header("User-Agent", JmApiInterceptor.USER_AGENT)
                .header("Origin", JmApiInterceptor.ORIGIN)
                .header("Referer", JmApiInterceptor.REFERER)
                .header("X-Requested-With", JmApiInterceptor.PACKAGE_NAME)
                .build()
            val result = runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        response.isSuccessful && cookieJar.hasCookie(response.request.url, "AVS")
                    }
                }
            }.getOrDefault(false)
            if (result) {
                confirmedHosts += host
                JmRouteManager.record("age_gate", host, "已尝试初始化 JM 年龄验证 Cookie")
            } else {
                JmRouteManager.record("age_gate", host, "JM 年龄验证 Cookie 初始化未确认")
            }
        }
    }

    /** Clear only JM's in-memory cookies; no Manwa credentials are reachable from this API. */
    fun clearCookies() {
        cookieJar.clear()
        confirmedHosts.clear()
    }
}
