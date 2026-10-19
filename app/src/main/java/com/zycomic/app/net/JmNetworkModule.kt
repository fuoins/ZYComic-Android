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
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Pins one JM request's exact endpoint across the synchronous OkHttp chain and DNS lookup.
 * Call.execute and the dispatcher worker for Call.enqueue both run DNS on the same thread as
 * application interceptors; restoring in the interceptor's finally prevents worker-thread leaks.
 */
internal object JmEndpointRequestContext {
    internal data class Pin(
        val domain: String,
        val image: Boolean,
        var endpoint: DomainIp?,
        val bootstrapAllowed: Boolean,
    )

    private val active = ThreadLocal<Pin?>()

    fun current(hostname: String, image: Boolean): Pin? = active.get()?.takeIf {
        it.image == image && it.domain.equals(hostname, ignoreCase = true)
    }

    fun install(pin: Pin): Pin? = active.get().also { active.set(pin) }

    fun restore(previous: Pin?) {
        if (previous == null) active.remove() else active.set(previous)
    }
}

/** JM 专用网络栈：独立 CookieJar、Dispatcher、连接池、协议拦截器和 host 路由。 */
object JmNetworkModule {

    private val cookieJar = JmCookieJar()
    private val confirmMutex = Mutex()
    private val confirmedHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val selectedImageIp: String?
        get() = currentImageEndpoint()?.ip

    fun currentImageEndpoint(domain: String = JmRouteManager.activeImageDomain): DomainIp? =
        JmRouteManager.currentImageEndpoint(domain)

    private val jmApiDns = singleAddressDns(
        JmRouteManager::currentLineEndpoint,
        JmRouteManager::resolveLineIp,
        { host -> JmRouteManager.hasEndpointMeasurements(host, image = false) },
        image = false,
    )
    private val jmImageDns = singleAddressDns(
        JmRouteManager::currentImageEndpoint,
        JmRouteManager::resolveImageIp,
        { host -> JmRouteManager.hasEndpointMeasurements(host, image = true) },
        image = true,
    )

    internal fun singleAddressDns(
        endpoint: (String) -> DomainIp?,
        configuredIps: (String) -> List<String>,
        measuredResults: (String) -> Boolean,
        image: Boolean,
    ) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val requestPin = JmEndpointRequestContext.current(hostname, image)
            val selected = if (requestPin != null) {
                requestPin.endpoint?.takeIf { it.domain.equals(hostname, true) }
            } else {
                endpoint(hostname)?.takeIf { it.domain.equals(hostname, true) }
            }
            val bootstrapAllowed = requestPin?.bootstrapAllowed ?: !measuredResults(hostname)
            if (selected == null && !bootstrapAllowed) {
                throw java.net.UnknownHostException("JM has no successful measured address for $hostname")
            }
            val ip = selected?.ip ?: configuredIps(hostname).firstOrNull(::isLiteralIp)
                ?: Dns.SYSTEM.lookup(hostname).firstOrNull()?.hostAddress
                ?: throw java.net.UnknownHostException("JM has no bootstrap address for $hostname")
            val address = runCatching { InetAddress.getByName(ip) }.getOrElse {
                throw java.net.UnknownHostException("JM selected address is invalid for $hostname: $ip")
            }
            requestPin?.endpoint = DomainIp(hostname, address.hostAddress.substringBefore('%'))
            return listOf(address)
        }
    }

    private fun isLiteralIp(value: String): Boolean =
        value.isNotBlank() && (value.all { it.isDigit() || it == '.' } || ':' in value) &&
            runCatching { InetAddress.getByName(value) }.isSuccess

    private fun createBaseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        // HTTP/1.1 + zero idle sockets prevents cross-host HTTP/2 coalescing and stale route reuse.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .proxy(Proxy.NO_PROXY)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            },
        )
        .cookieJar(cookieJar)
        .dns(jmApiDns)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val baseClient: OkHttpClient by lazy(::createBaseClient)
    val client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor(JmApiInterceptor())
            .addInterceptor(JmImageInterceptor())
            .addInterceptor(JmEndpointTransportInterceptor())
            .addNetworkInterceptor(JmApiResponseInterceptor())
            .build()
    }

    /** Image-only transport: no cookies, no redirects, no retries, and the API interceptor strips JWT. */
    val imageClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .dns(jmImageDns)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor(JmApiInterceptor(allowAuthentication = false))
            .addInterceptor(JmImageInterceptor())
            .addInterceptor(JmEndpointTransportInterceptor())
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

    /** Persist one successful sources2 endpoint through the route manager. */
    fun selectImageEndpoint(domain: String, ip: String): Boolean = JmRouteManager.selectImageEndpoint(domain, ip)

    fun clearSelectedImageEndpoint() {
        JmRouteManager.selectImageDomain(null)
    }

    /** Cancel outstanding requests and discard idle sockets on any endpoint transition. */
    fun rebindImageEndpoint() = rebindEndpointPools()

    fun rebindApiEndpoint() = rebindEndpointPools()

    private fun rebindEndpointPools() {
        runCatching { baseClient.dispatcher.cancelAll() }
        runCatching { imageClient.dispatcher.cancelAll() }
        evictIdleEndpointPools()
    }

    internal fun evictIdleEndpointPools() {
        runCatching { baseClient.connectionPool.evictAll() }
    }

    private val boundImageRoutes = mutableMapOf<String, String>()
    private val boundApiRoutes = mutableMapOf<String, String>()

    @Synchronized
    internal fun prepareImageRoute(host: String) {
        if (JmRouteManager.imageDomains.none { it.equals(host, ignoreCase = true) }) return
        val identity = "${host.lowercase(java.util.Locale.ROOT)}|${currentImageEndpoint(host)?.ip.orEmpty()}"
        if (boundImageRoutes[host.lowercase(java.util.Locale.ROOT)] != identity) {
            evictIdleEndpointPools()
            boundImageRoutes[host.lowercase(java.util.Locale.ROOT)] = identity
        }
    }

    @Synchronized
    internal fun prepareApiRoute(host: String) {
        if (JmRouteManager.lineHosts.none { runCatching { java.net.URI(it).host.equals(host, true) }.getOrDefault(false) }) return
        val identity = "${host.lowercase(java.util.Locale.ROOT)}|${JmRouteManager.currentLineEndpoint(host)?.ip.orEmpty()}"
        if (boundApiRoutes[host.lowercase(java.util.Locale.ROOT)] != identity) {
            evictIdleEndpointPools()
            boundApiRoutes[host.lowercase(java.util.Locale.ROOT)] = identity
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
