package com.zycomic.app.platform

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

internal object PlatformNet {
    const val UA =
        "Mozilla/5.0 (Linux; Android 16; PLC110 Build/BP2A.250605.015) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.7871.181 Mobile Safari/537.36"

    private val uaInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", UA)
            .build()
        chain.proceed(req)
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(uaInterceptor)
            .build()
    }
}
