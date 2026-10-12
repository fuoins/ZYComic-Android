package com.zycomic.app.net

import java.net.InetAddress
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.create
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

/**
 * 网络模块：构建 OkHttpClient 与 Retrofit，整合所有拦截器。
 * 直连模式：RuleDns 做 rule 域名 IP 直连，SniRemovingSocketFactory 移除 SNI，trust-all 信任证书。
 *
 * 拦截器链顺序（application interceptors）：
 * 0. [LineFailoverInterceptor] —— API 请求失败计数，连续失败自动切线路
 * 1. [ManwaInterceptor]       —— 追加通用 query + 鉴权头 + 响应 AES 解密
 * 2. [ImageInterceptor]        —— 图片请求加头 + CipherSource 流式解密
 *
 * 切换线路后需调用 [rebuild] 重建 Retrofit（baseUrl 变化）。
 */
object NetworkModule {

    /** Application context，需在 App 启动时调用 [init] 设置 */
    @Volatile private var appContext: android.content.Context? = null

    /** 初始化网络模块（必须在首次使用前调用，传入 Application context） */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /** 全局 CookieJar 实例（所有线路共享，持久化到 SharedPreferences） */
    val cookieJar: GlobalCookieJar by lazy {
        GlobalCookieJar(appContext ?: throw IllegalStateException("NetworkModule.init() 未调用"))
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
        // 开启后 @JsonNames 别名（book_id/title/serialize/desc/chapters 等）才会被识别
        useAlternativeNames = true
    }

    @Volatile private var _apiClient: OkHttpClient? = null
    @Volatile private var _imageClient: OkHttpClient? = null
    @Volatile private var _retrofit: Retrofit? = null
    @Volatile private var _api: ApiService? = null

    private val manwaInterceptor by lazy { ManwaInterceptor() }
    private val imageInterceptor by lazy { ImageInterceptor() }

    /** 向后兼容别名，等同 [apiClient]。 */
    val client: OkHttpClient get() = apiClient

    /** API 请求 client（接口/列表/详情）。 */
    val apiClient: OkHttpClient
        get() = _apiClient ?: buildApiClient().also { _apiClient = it }

    /** 图片下载 client（长超时 + 更高并发）。 */
    val imageClient: OkHttpClient
        get() = _imageClient ?: buildImageClient().also { _imageClient = it }

    /** 当前 ApiService（baseUrl 取 [RouteManager.baseUrl]） */
    val api: ApiService
        get() = _api ?: buildRetrofit().let { _api = it.create(ApiService::class.java); _api!! }

    private fun buildApiClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 8
            })
            .enableTrustAll()
            .applyNetworkConfig(withFailover = true)
            .build()
    }

    private fun buildImageClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 8
            })
            .enableTrustAll()
            .applyNetworkConfig()
            .build()
    }

    /**
     * 传输层策略（与拦截器无关）：cookieJar + trust-all，以及按当前网络决定的 DNS/SNI。
     * 运行时 client 与测速 client 必须共用本方法，保证"测速走的路径 = 运行时图片走的路径"。
     * - 直连策略：RuleDns 硬编码 IP 直连 + SniRemoving 去 SNI；
     * - 跟随 VPN（系统 VPN 开且开关 ON）：系统 DNS + 保留 SNI，流量走 VPN 隧道。
     */
    private fun OkHttpClient.Builder.applyTransportStrategy(): OkHttpClient.Builder {
        cookieJar(cookieJar)
        if (shouldUseDirectStrategy()) {
            // 国内直连 / 强制直连：RuleDns 硬编码 IP 直连 + SniRemoving 去 SNI
            val baseSslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
            }
            dns(RuleDns)
            sslSocketFactory(SniRemovingSocketFactory(baseSslContext.socketFactory), trustAllManager)
        } else {
            // 跟随系统 VPN：系统 DNS + 保留 SNI（trust-all 已在 enableTrustAll 中设置默认 SSL）
            dns(Dns.SYSTEM)
        }
        return this
    }

    private fun OkHttpClient.Builder.applyNetworkConfig(withFailover: Boolean = false): OkHttpClient.Builder {
        applyTransportStrategy()
        if (withFailover) addInterceptor(LineFailoverInterceptor())
        addInterceptor(manwaInterceptor)
        addInterceptor(imageInterceptor)
        return this
    }

    private fun buildRetrofit(): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(RouteManager.baseUrl + "/")
            .client(apiClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .also { _retrofit = it }
    }

    /**
     * 网络重建专用上下文：强制在 IO 线程执行，且并行度 = 1（串行）。
     *
     * 为什么必须在 IO：[OkHttpClient.connectionPool] 的 `evictAll()` 与 `dispatcher.cancelAll()`
     * 会真正关闭 socket，属于网络操作；在主线程调用会抛 `NetworkOnMainThreadException`
     * （冷启动测速选完线路后调 [rebuild] 必崩，表现为"所有图源测速超时"）。
     * 为什么并行度 1：重建会整体替换 client/retrofit 单例，并发重建互相覆盖会产生
     * "半个新半个旧"的网络栈，串行化从根上杜绝该竞态。
     *
     * 注意：内部实现一律调用 `*Locked()` 私有版本，**不要在已经处于 [netContext] 的协程里
     * 再 `withContext(netContext)` 调公开版本**——单并行度上下文自我等待会死锁。
     */
    private val netContext: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * 切换线路后重建 Retrofit（必须挂起：内部网络操作在 IO 串行执行）。
     * 同时清理 apiClient/imageClient 连接池 + 取消在途请求，让手动切换立即生效。
     */
    suspend fun rebuild() {
        withContext(netContext) { rebuildLocked() }
    }

    private fun rebuildLocked() {
        _apiClient?.dispatcher?.cancelAll()
        _apiClient?.connectionPool?.evictAll()
        _imageClient?.dispatcher?.cancelAll()
        _imageClient?.connectionPool?.evictAll()
        _retrofit = null
        _api = null
        buildRetrofit()
    }

    // ==================== VPN 加速 / 国内直连策略 ====================

    /** 上次评估的"是否直连策略"，用于判断 VPN/网络变化后是否需要重建 client。 */
    @Volatile
    private var lastDirectStrategy: Boolean? = null

    /** 检测系统 VPN 是否激活（NetworkCapabilities.TRANSPORT_VPN）。 */
    fun isSystemVpnActive(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 是否走"直连策略"（RuleDns 硬编码 IP + SniRemoving 去 SNI）。
     * - 「自动跟随系统 VPN」开关关闭（强制直连/抓包模式）→ 始终直连，即使系统 VPN 开着；
     * - 开关开启 + 检测到系统 VPN → 不走直连（系统 DNS + 保留 SNI + VPN 出口）；
     * - 开关开启 + 无 VPN → 直连。
     */
    fun shouldUseDirectStrategy(): Boolean {
        if (!DevConfig.isFollowSystemVpnEnabled()) return true
        return !isSystemVpnActive()
    }

    /**
     * 重建网络客户端：apiClient/imageClient 都先 cancelAll + evictAll，再按当前生效策略重建，
     * 并重建 Retrofit、重绑封面 Coil 工厂、清 Coil 内存缓存。
     * 「自动跟随系统 VPN」开关变化 / VPN 或网络变化导致策略翻转时调用。
     *
     * [clearChapterCache] 默认 **false**：章节缓存里存的是已解密的图片文件，键是图片 URL，
     * 而策略翻转（VPN 开/关）并不改变线路与图域，URL 不变 → 缓存仍然有效，没必要清。
     * 无条件清理反而会让正在阅读/正在分享页面的人读到被清掉的文件，是崩溃 B
     * （`ImageSaver` 拿到空流/非图片字节）的诱因之一。仅在确实更换线路/图域时显式传 true。
     */
    suspend fun rebuildClient(reason: String = "", clearChapterCache: Boolean = false) {
        withContext(netContext) { rebuildClientLocked(reason, clearChapterCache) }
    }

    private fun rebuildClientLocked(reason: String, clearChapterCache: Boolean) {
        Log.i("NetworkModule", "rebuildClient: reason=$reason, direct=${shouldUseDirectStrategy()}")
        _apiClient?.dispatcher?.cancelAll()
        _apiClient?.connectionPool?.evictAll()
        _imageClient?.dispatcher?.cancelAll()
        _imageClient?.connectionPool?.evictAll()
        _apiClient = buildApiClient()
        _imageClient = buildImageClient()
        _retrofit = null
        _api = null
        buildRetrofit()
        if (clearChapterCache) {
            runCatching { com.zycomic.app.data.repository.ReaderRepository.chapterCache.clear() }
        }
        // 封面 Coil 单例改委托当前 imageClient：重建后必须重绑，否则封面仍用旧网络栈
        CoverCallFactory.rebind()
        DevConfig.clearImageMemoryCache()
        lastDirectStrategy = shouldUseDirectStrategy()
    }

    /**
     * 网络变化入口（App 默认网络回调防抖后调用、手动切换开关时也调用）。
     * 无论策略是否翻转，都先 cancelAll + evictAll 丢弃可能绑在旧网络（如已关闭的 VPN 接口）上的连接；
     * 仅当"是否直连策略"翻转时才整体重建 client；策略不变（含强制直连模式下切换 VPN）时只清连接池与封面内存缓存。
     */
    suspend fun onVpnOrNetworkChanged(reason: String = "network changed") {
        withContext(netContext) { onVpnOrNetworkChangedLocked(reason) }
    }

    private fun onVpnOrNetworkChangedLocked(reason: String) {
        val now = shouldUseDirectStrategy()
        // 无条件清理在途/空闲连接，根治 VPN 拆除后的 Connection reset
        _apiClient?.dispatcher?.cancelAll()
        _apiClient?.connectionPool?.evictAll()
        _imageClient?.dispatcher?.cancelAll()
        _imageClient?.connectionPool?.evictAll()
        if (lastDirectStrategy != now) {
            lastDirectStrategy = now
            // 已在 netContext 内：必须走 Locked 版本，不能再调 suspend 公开版本
            rebuildClientLocked(reason, clearChapterCache = false)
        } else {
            // 策略不变：封面委托与 imageClient 共享连接池（已 evict），仅清内存缓存
            DevConfig.clearImageMemoryCache()
        }
    }

    /**
     * 测速/探活超时：connect、read 保持 3s（快速判死不可达），callTimeout 放宽到 5s。
     * 原因：线路探活要打真实业务接口（约 12KB gzip + AES 解密），VPN 下还有额外 RTT，
     * 全 3s 会把"能通但稍慢"的线路误判成超时；5s 既不误杀也不会让测速拖太久。
     */
    private const val PROBE_CONNECT_READ_SECONDS = 3L
    private const val PROBE_CALL_SECONDS = 5L

    /**
     * 图域探活专用 client：传输策略（cookieJar/trust-all/DNS/SNI）与当前网络下的运行时
     * imageClient 完全一致，仅超时收紧、不挂业务拦截器（图域探活自带与图片运行时一致的头）。
     * 不再写死 NO_PROXY/RuleDns/去 SNI——VPN 开时测速即走 VPN 路径，保证测速结果可用于运行时。
     */
    fun newSpeedTestClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(PROBE_CONNECT_READ_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_CONNECT_READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(PROBE_CONNECT_READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_CALL_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 1, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .noRedirect()
            .enableTrustAll()
            .applyTransportStrategy()
            .build()
    }

    /**
     * 探活 client 一律**不跟随重定向**。
     *
     * 真机实测证据：已废弃的图域 `mwfimsvfast2.cc` 对封面路径返回
     * `302 → https://2026-10-08.urldance.com/7d/?mwfimsvfast2.cc`（域名被回收做了跳转站），
     * OkHttp 默认跟随重定向，于是"探这个图域"实际测到了跳转目标站的 `206 text/html`
     * ——延迟记在了一个根本不供图域名头上。3xx 本身就等于"该域已不做图片服务"，
     * 应直接判负并单独归类，不能让别人的服务器替它答到。
     */
    private fun OkHttpClient.Builder.noRedirect(): OkHttpClient.Builder =
        followRedirects(false).followSslRedirects(false)

    /**
     * 线路探活专用 client：传输策略与运行时 apiClient 一致（DNS/SNI/出口/cookie 同源），
     * 但只挂 [ManwaInterceptor] —— 探活必须打"真实业务接口"，因此要完整走
     * 追加 facility/deviceid/timestamp → 签 devid/X-Token → gzip → AES 解密 这条链路。
     *
     * 不挂 [LineFailoverInterceptor]（探活失败正是待采集的信号，不该触发自动换线）
     * 也不挂 [ImageInterceptor]（图片路径与线路 API 无关）。
     */
    fun newLineProbeClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(PROBE_CONNECT_READ_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PROBE_CONNECT_READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(PROBE_CONNECT_READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(PROBE_CALL_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 1, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .noRedirect()
            .enableTrustAll()
            .applyTransportStrategy()
            .addInterceptor(manwaInterceptor)
            .build()
    }

    // ---- trust-all SSL（信任代理自签名证书 + MITM 场景 + IP直连场景） ----
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun OkHttpClient.Builder.enableTrustAll(): OkHttpClient.Builder {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        sslSocketFactory(sslContext.socketFactory, trustAllManager)
        hostnameVerifier(HostnameVerifier { _, _ -> true })
        return this
    }

    // ==================== 方案B：自定义 DNS + SNI 移除（关闭本地代理时使用） ====================

    /**
     * Cloudflare 官方公布的 IPv4 网段（CDN anycast）。
     *
     * 用途：这些地址**强制要求 TLS SNI**（实测不带 SNI 直接握手失败），
     * 所以即便域名在"去 SNI"名单里，只要对端落在 CF 段就必须保留 SNI。
     * 内置 77 个图源里有两种完全不同的架构：CF 系（104.16-104.31 / 172.64-172.71）
     * 与自建源站系（104.238 / 172.93 / 172.96 等），过去用同一套"IP直连+去SNI"
     * 覆盖两者，对 CF 系是必然失败的。
     */
    private val cloudflareCidrs: List<Pair<Long, Int>> = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.15.192.0/18", "197.234.240.0/22",
        "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14",
        "172.64.0.0/13",
    ).map { spec ->
        val parts = spec.split('/')
        val bits = parts[1].toInt()
        val net = parts[0].split('.').fold(0L) { acc, o -> (acc shl 8) or (o.toLong() and 0xFF) }
        net and v4Mask(bits) to bits
    }

    private fun v4Mask(bits: Int): Long =
        if (bits == 0) 0L else ((-1L) shl (32 - bits)) and 0xFFFFFFFFL

    /** 判断对端地址是否落在 Cloudflare v4 网段（非 IPv4 一律 false）。 */
    private fun isInCloudflare(addr: java.net.InetAddress?): Boolean {
        val bytes = addr?.address ?: return false
        if (bytes.size != 4) return false
        val v = bytes.fold(0L) { acc, b -> (acc shl 8) or ((b.toInt() and 0xFF).toLong()) }
        return cloudflareCidrs.any { (net, bits) -> (v and v4Mask(bits)) == net }
    }

    /**
     * 自定义 DNS：rule 配置中的域名 → 全部 IP，其他域名走系统 DNS。
     * OkHttp 内部按顺序尝试，连接池自动复用。
     */
    private object RuleDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ips = DevConfig.getRule()[hostname]
            if (!ips.isNullOrEmpty()) {
                return ips.map { InetAddress.getByName(it) }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    /**
     * 自定义 SSLSocketFactory：对 sni 列表中的域名，在 createSocket 后移除 SNI。
     * 包装原始 SSLSocketFactory，委托所有方法，仅在 createSocket 后处理 SNI。
     */
    private class SniRemovingSocketFactory(
        private val delegate: SSLSocketFactory,
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean): java.net.Socket {
            val socket = delegate.createSocket(s, host, port, autoClose)
            removeSniIfNeeded(socket, host)
            return socket
        }

        override fun createSocket(host: String?, port: Int): java.net.Socket {
            val socket = delegate.createSocket(host, port)
            removeSniIfNeeded(socket, host)
            return socket
        }

        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket {
            val socket = delegate.createSocket(host, port, localHost, localPort)
            removeSniIfNeeded(socket, host)
            return socket
        }

        override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket {
            return delegate.createSocket(host, port)
        }

        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket {
            return delegate.createSocket(address, port, localAddress, localPort)
        }

        private fun removeSniIfNeeded(socket: java.net.Socket, host: String?) {
            if (socket !is SSLSocket) return
            if (host == null) return
            if (!DevConfig.getSniDomains().contains(host)) return
            // Cloudflare 网段强制要求 SNI：实测对 104.21.72.208 不带 SNI 直接
            // SSLV3_ALERT_HANDSHAKE_FAILURE，去了 SNI 只会把一个本来能通的 CDN 域打死。
            // （嵌套类不能非限定访问外层 object 成员，必须写 NetworkModule.）
            if (NetworkModule.isInCloudflare(socket.inetAddress)) return
            try {
                val params = socket.sslParameters
                params.serverNames = null
                socket.sslParameters = params
            } catch (_: Exception) {
                try {
                    val field = socket.javaClass.getDeclaredField("serverNames")
                    field.isAccessible = true
                    field.set(socket, null)
                } catch (_: Exception) {
                }
            }
        }
    }
}
