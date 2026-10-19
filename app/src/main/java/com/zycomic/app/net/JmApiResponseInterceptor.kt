package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response

/** Runs inside BridgeInterceptor, before response cookies reach even a session-snapshot jar. */
internal class JmApiResponseInterceptor(
    private val record: (String, String) -> Unit = { host, message -> JmRouteManager.record("api_diagnostic", host, message) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (JmAuthPolicy.isImage(request.url.encodedPath)) return response
        val selected = JmEndpointRequestContext.current(request.url.host, image = false)?.endpoint
        val actual = chain.connection()?.route()?.socketAddress?.address?.hostAddress?.let { DomainIp(request.url.host, it) }
        // Diagnostics must never turn a valid response into a transport failure or consume its body.
        runCatching { record(request.url.host, JmApiDiagnostics.message(request, response, selected, actual)) }
        if (response.isSuccessful) return response
        val cookies = response.headers.values("Set-Cookie")
        if (cookies.none(JmApiDiagnostics::isAvsCookie)) return response
        return response.newBuilder().headers(
            response.headers.newBuilder().removeAll("Set-Cookie").apply {
                cookies.filterNot(JmApiDiagnostics::isAvsCookie).forEach { add("Set-Cookie", it) }
            }.build(),
        ).build()
    }
}
