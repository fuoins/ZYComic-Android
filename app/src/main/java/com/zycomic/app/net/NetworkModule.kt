package com.zycomic.app.net

import java.net.InetAddress
import java.net.Proxy
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

    private fun OkHttpClient.Builder.applyNetworkConfig(withFailover: Boolean = false): OkHttpClient.Builder {
        cookieJar(cookieJar)
        if (withFailover) addInterceptor(LineFailoverInterceptor())
        addInterceptor(manwaInterceptor)
        addInterceptor(imageInterceptor)
        val baseSslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
        dns(RuleDns)
        sslSocketFactory(SniRemovingSocketFactory(baseSslContext.socketFactory), trustAllManager)
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
     * GlobalCookieJar 全局共享 cookie，不按 host 分开，无需重写 domain。
     * OkHttpClient 本身无需重建（拦截器无状态）。
     */
    fun rebuild() {
        _retrofit = null
        _api = null
        buildRetrofit()
    }

    /** 测速专用 client：NO_PROXY + trust-all + RuleDns + SNI 移除 + 3s 超时。 */
    fun newSpeedTestClient(): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .enableTrustAll()
            .dns(RuleDns)
            .sslSocketFactory(SniRemovingSocketFactory(sslContext.socketFactory), trustAllManager)
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
