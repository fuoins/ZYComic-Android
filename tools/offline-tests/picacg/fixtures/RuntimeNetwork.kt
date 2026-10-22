package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal class ApiWriteSafetyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

internal class PicacgApiRuntimeRecoveryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

internal class UnsafeWriteRedirectNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

internal object RuntimeNetworkGate {
    fun ambiguousWriteMessage(): String = "Write may have succeeded; refresh to confirm"
}

internal fun isSafeApiRead(request: Request): Boolean = request.method == "GET" || request.method == "HEAD"

class PageAttemptScope(val isCurrent: Boolean = true)

class PicacgRuntimeImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

class PicacgImageFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
