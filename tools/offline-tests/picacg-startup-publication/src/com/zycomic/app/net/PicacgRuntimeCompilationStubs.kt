package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response

internal fun isPublicIpv4(address: String): Boolean =
    address.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull()?.let { number -> number in 0..255 } == true } }

internal object PicacgApiRuntimeRecovery {
    fun isProbeAllowed(endpoint: DomainIp): Boolean = true
    fun recordProbeResponse(endpoint: DomainIp, code: Int, retryAfter: String?) = Unit
}

internal object RuntimeNetworkGate {
    fun isOnline(): Boolean = true
    fun failureMessage(): String = "offline test"
}

internal class RuntimeImageProbeGuard(platform: RuntimeRecoveryPolicy.Platform, endpoint: DomainIp) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
