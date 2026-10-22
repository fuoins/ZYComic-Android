package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Recovery probes share the platform's actual image Retry-After deadline. */
internal class RuntimeImageProbeGuard(
    private val platform: RuntimeRecoveryPolicy.Platform,
    private val endpoint: DomainIp,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!RuntimeNetworkGate.isOnline()) throw IOException(RuntimeNetworkGate.failureMessage())
        // Use the probed endpoint's host even if a sample path contains a different origin.
        val request = chain.request()
        check(request.url.host.equals(endpoint.domain, true))
        RuntimeImageRateLimit.blockedResponse(platform, request)?.let { return it }
        return chain.proceed(request).also { response -> RuntimeImageRateLimit.record(platform, response) }
    }
}
