package com.zycomic.app.net

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.create
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

/**
 * 网络模块：构建 OkHttpClient 与 Retrofit，整合所有拦截器。
 *
 * 方案B：DNS 覆盖通过 [ManwaDns]（自定义 Dns 接口）实现，SNI 绕过通过 [SniBypassSSLSocketFactory] 实现。
 *
 * 拦截器链顺序（application interceptors）：
 * 1. [ManwaInterceptor]       —— 追加通用 query + 鉴权头 + 响应 AES 解密
 * 2. [ImageInterceptor]        —— 图片请求加头 + CipherSource 流式解密
 *
 * 切换线路后需调用 [rebuild] 重建 Retrofit（baseUrl 变化）。
 */
object NetworkModule {

    /** 全局 CookieJar 实例（所有线路共享） */
    val cookieJar: GlobalCookieJar = GlobalCookieJar()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    @Volatile private var _client: OkHttpClient? = null
    @Volatile private var _retrofit: Retrofit? = null
    @Volatile private var _api: ApiService? = null

    /** 当前 OkHttpClient（单例，切换线路无需重建 client，仅重建 Retrofit） */
    val client: OkHttpClient
        get() = _client ?: buildClient().also { _client = it }

    /** 当前 ApiService（baseUrl 取 [RouteManager.baseUrl]） */
    val api: ApiService
        get() = _api ?: buildRetrofit().let { _api = it.create(ApiService::class.java); _api!! }

    private fun buildClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .dns(ManwaDns)
            .enableTrustAll()
            .addInterceptor(ManwaInterceptor())
            .addInterceptor(ImageInterceptor())
            .build()
    }

    private fun buildRetrofit(): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(RouteManager.baseUrl + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .also { _retrofit = it }
    }

    /**
     * 切换线路后重建 Retrofit + 重写 cookie 到新域名。
     * OkHttpClient 本身无需重建（拦截器无状态）。
     */
    fun rebuild() {
        cookieJar.rewriteForHost(RouteManager.baseUrl)
        _retrofit = null
        _api = null
        buildRetrofit()
    }

    // ---- trust-all SSL（IP 直连时绕过证书校验与主机名校验） ----
    private fun OkHttpClient.Builder.enableTrustAll(): OkHttpClient.Builder {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        sslSocketFactory(SniBypassSSLSocketFactory(sslContext.socketFactory), trustAll)
        hostnameVerifier(HostnameVerifier { _, _ -> true })
        return this
    }
}
