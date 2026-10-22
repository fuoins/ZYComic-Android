package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Observes only API transport outcomes; never retries or replays the original request. */
class LineFailoverInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isSafeApiRead(request)) return chain.proceed(request)
        val revision = RouteManager.configRevision
        val networkRevision = RuntimeNetworkGate.revision
        val endpoint = DomainIp(
            request.url.host,
            RouteManager.lineIpFor(request.url.host) ?: RouteManager.resolveIp(request.url.host).firstOrNull().orEmpty(),
        )
        fun mayReport() = !chain.call().isCanceled() && RouteManager.configRevision == revision &&
            RuntimeNetworkGate.isOnline() && RuntimeNetworkGate.revision == networkRevision
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            if (mayReport() && RuntimeRecoveryPolicy.isTransportFailure(error)) {
                RouteManager.recordApiConnectionFailure(request.method, error, endpoint, revision, networkRevision)
            }
            throw error
        }
        if (!mayReport()) return response
        if (response.code == 429) {
            RouteManager.recordApiResponse(endpoint, revision, networkRevision, response.code, response.header("Retry-After"))
            return response
        }
        if (response.body == null || request.method.equals("HEAD", true) || response.body?.contentLength() == 0L) {
            RouteManager.recordApiResponse(endpoint, revision, networkRevision, response.code, response.header("Retry-After"))
            return response
        }

        val body = response.body ?: return response
        val reported = AtomicBoolean(false)
        val guardedSource = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                super.read(sink, byteCount).also { count ->
                    if (count == -1L && mayReport() && reported.compareAndSet(false, true)) {
                        // Completion is connectivity evidence; content validation remains the API layer's job.
                        RouteManager.recordApiResponse(endpoint, revision, networkRevision, response.code, response.header("Retry-After"))
                    }
                }
            } catch (error: IOException) {
                if (mayReport() && RuntimeRecoveryPolicy.isTransportFailure(error) && reported.compareAndSet(false, true)) {
                    RouteManager.recordApiConnectionFailure(request.method, error, endpoint, revision, networkRevision)
                }
                throw error
            }
        }.buffer()
        val guardedBody = object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = guardedSource
        }
        return response.newBuilder().body(guardedBody).build()
    }
}
