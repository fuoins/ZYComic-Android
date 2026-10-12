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
     * 切换线路后重建 Retrofit。
     * 同时清理 apiClient/imageClient 连接池 + 取消在途请求，让手动切换立即生效。
     */
    fun rebuild() {
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
     * 并重建 Retrofit、清空章节缓存、重绑封面 Coil 工厂、清 Coil 内存缓存。
     * 「自动跟随系统 VPN」开关变化 / VPN 或网络变化导致策略翻转时调用。
     */
    fun rebuildClient(reason: String = "") {
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
        runCatching { com.zycomic.app.data.repository.ReaderRepository.chapterCache.clear() }
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
    fun onVpnOrNetworkChanged(reason: String = "network changed") {
        val now = shouldUseDirectStrategy()
        // 无条件清理在途/空闲连接，根治 VPN 拆除后的 Connection reset
        _apiClient?.dispatcher?.cancelAll()
        _apiClient?.connectionPool?.evictAll()
        _imageClient?.dispatcher?.cancelAll()
        _imageClient?.connectionPool?.evictAll()
        if (lastDirectStrategy != now) {
            lastDirectStrategy = now
            rebuildClient(reason)
        } else {
            // 策略不变：封面委托与 imageClient 共享连接池（已 evict），仅清内存缓存
            DevConfig.clearImageMemoryCache()
        }
    }

    /**
     * 测速专用 client：传输策略（cookieJar/trust-all/DNS/SNI）与当前网络下的运行时 client 完全一致，
     * 仅超时收紧到 3s、不挂业务拦截器（线路探活自带签名头，图域探活自带与图片运行时一致的头）。
     * 不再写死 NO_PROXY/RuleDns/去 SNI——VPN 开时测速即走 VPN 路径，保证测速结果可用于运行时。
     */
    fun newSpeedTestClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 1, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .enableTrustAll()
            .applyTransportStrategy()
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
