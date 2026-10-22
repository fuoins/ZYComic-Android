package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class LineFailoverInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val revision = RouteManager.configRevision
        val networkRevision = RuntimeNetworkGate.revision
        val endpoint = DomainIp(
            request.url.host,
            RouteManager.lineIpFor(request.url.host) ?: RouteManager.resolveIp(request.url.host).firstOrNull().orEmpty(),
        )
        return try {
            val response = chain.proceed(request)
            RouteManager.recordApiSuccess(endpoint, revision)
            response
        } catch (error: IOException) {
            if (!chain.call().isCanceled()) {
                RouteManager.recordApiConnectionFailure(request.method, error, endpoint, revision, networkRevision)
            }
            throw error
        }
    }
}
