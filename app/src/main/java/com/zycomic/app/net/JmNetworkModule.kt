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
        get() = currentImageEndpoint()?.ip

    fun currentImageEndpoint(domain: String = JmRouteManager.activeImageDomain): DomainIp? {
        val snapshot = JmRouteManager.speedTestSnapshot.value
        return JmImageEndpointSelectionPolicy.currentEndpoint(
            domain = domain,
            configuredDomains = JmRouteManager.imageDomains,
            delays = snapshot.imageIpDelaysMs,
            verdicts = snapshot.imageIpVerdicts,
            manualEndpoint = selectedImageEndpoint,
        )
    }

    private val jmDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            currentImageEndpoint(hostname)?.let { endpoint ->
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
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor(JmApiInterceptor())
            .addInterceptor(JmImageInterceptor())
            .build()
    }

    /** Image-only transport: no cookies, no redirects, no retries, and the API interceptor strips JWT. */
    val imageClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor(JmApiInterceptor(allowAuthentication = false))
            .addInterceptor(JmImageInterceptor())
            .build()
    }

    /** A probe client with its own pool and no API/image retry interceptors. */
    fun newProbeClient(): OkHttpClient = baseClient.newBuilder()
        // Match Manwa's endpoint budget, without sharing its transport or routing state.
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 24
                maxRequestsPerHost = 24
            },
        )
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Pin only an active JM sources2 hostname to its measured address; URL host/SNI remain unchanged. */
    fun selectImageEndpoint(domain: String, ip: String): Boolean {
        if (JmRouteManager.imageDomains.none { it.equals(domain, ignoreCase = true) }) return false
        if (ip.isBlank()) return false
        val endpoint = JmRouteManager.speedTestSnapshot.value.imageIpDelaysMs.keys.firstOrNull {
            it.domain.equals(domain, true) && it.ip.equals(ip.trim(), true)
        } ?: return false
        selectedImageEndpoint = endpoint
        return true
    }

    /** Forget the manual override and resume the active domain's fastest valid measured IP. */
    fun clearSelectedImageEndpoint() {
        selectedImageEndpoint = null
    }

    /** The JM client has a dedicated pool; evict old sockets after changing a hostname's pinned IP. */
    fun rebindImageEndpoint() {
        runCatching { baseClient.connectionPool.evictAll() }
    }

    private val boundImageRoutes = mutableMapOf<String, List<String>>()

    /** Called only by the image interceptor on its request thread, never by main-thread state updates. */
    @Synchronized
    internal fun prepareImageRoute(host: String) {
        if (JmRouteManager.imageDomains.none { it.equals(host, ignoreCase = true) }) return
        val key = host.lowercase(java.util.Locale.ROOT)
        val addresses = currentImageEndpoint(host)?.let { listOf(it.ip) } ?: JmRouteManager.resolveIp(host)
        if (boundImageRoutes[key] != addresses) {
            rebindImageEndpoint()
            boundImageRoutes[key] = addresses
        }
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
