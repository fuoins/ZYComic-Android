package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Binds JM calls to the current exact endpoint and advances that endpoint on transient failure. */
internal class JmEndpointTransportInterceptor(
    private val prepareApiRoute: (String) -> Unit = JmNetworkModule::prepareApiRoute,
    private val prepareImageRoute: (String) -> Unit = JmNetworkModule::prepareImageRoute,
    private val apiEndpoint: (String) -> DomainIp? = JmRouteManager::currentLineEndpoint,
    private val imageEndpoint: (String) -> DomainIp? = JmRouteManager::currentImageEndpoint,
    private val apiFailure: (DomainIp, String) -> Unit = JmRouteManager::recordApiEndpointFailure,
    private val imageFailure: (DomainIp, String) -> Unit = JmRouteManager::recordImageEndpointFailure,
    private val apiHasMeasurements: (String) -> Boolean = { JmRouteManager.hasEndpointMeasurements(it, image = false) },
    private val imageHasMeasurements: (String) -> Boolean = { JmRouteManager.hasEndpointMeasurements(it, image = true) },
    private val apiSuccess: (DomainIp) -> Unit = JmRouteManager::rememberLineEndpoint,
    private val imageSuccess: (DomainIp) -> Unit = JmRouteManager::rememberImageEndpoint,
    private val apiResponse: (DomainIp) -> Unit = JmRouteManager::recordApiTransportResponse,
    private val apiHttpResponse: (DomainIp, Int, String?) -> Unit = JmRouteManager::recordApiTransportResponse,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val scope = request.tag(PageAttemptScope::class.java)
        fun isCancelled() = chain.call().isCanceled() || scope?.isCurrent == false
        fun requireActive() {
            if (isCancelled()) throw IOException("JM request cancelled")
        }
        requireActive()
        val host = request.url.host
        val isImage = request.url.encodedPath.startsWith("/media/")
        val endpoint = if (isImage) imageEndpoint(host) else apiEndpoint(host)
        val hasMeasurements = if (isImage) imageHasMeasurements(host) else apiHasMeasurements(host)
        val configRevision = JmRouteManager.runtimeConfigRevision
        val platformRevision = com.zycomic.app.platform.StartupPlatformPolicy.state.value.revision
        val networkRevision = RuntimeNetworkGate.revision
        fun requestStillCurrent() = !isCancelled() && RuntimeNetworkGate.isOnline() &&
            RuntimeNetworkGate.revision == networkRevision && JmRouteManager.runtimeConfigRevision == configRevision &&
            com.zycomic.app.platform.StartupPlatformPolicy.state.value.revision == platformRevision
        val previousPin = JmEndpointRequestContext.install(
            JmEndpointRequestContext.Pin(host, isImage, endpoint, bootstrapAllowed = !hasMeasurements),
        )
        try {
            if (isImage) prepareImageRoute(host) else prepareApiRoute(host)
            requireActive()
            val response = chain.proceed(request)
            if (isCancelled()) {
                response.close()
                requireActive()
            }
            val requestPin = JmEndpointRequestContext.current(host, isImage)
            val actualEndpoint = requestPin?.endpoint ?: endpoint
            fun recordResponse() {
                if (actualEndpoint != null && requestStillCurrent()) {
                    runCatching { apiHttpResponse(actualEndpoint, response.code, response.header("Retry-After")) }
                    runCatching { apiResponse(actualEndpoint) }
                }
            }
            if (response.isSuccessful && requestPin?.bootstrapAllowed == true && actualEndpoint != null) {
                runCatching { if (isImage) imageSuccess(actualEndpoint) else apiSuccess(actualEndpoint) }
            }
            val body = response.body
            if (isImage) return response
            if (!response.isSuccessful || body == null || body.contentLength() == 0L || request.method.equals("HEAD", true)) {
                recordResponse()
                return response
            }
            val reported = AtomicBoolean()
            val observed = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount).also { count ->
                        if (count == -1L && reported.compareAndSet(false, true)) recordResponse()
                    }
                } catch (error: IOException) {
                    if (requestStillCurrent() && actualEndpoint != null && request.url.encodedPath != "/confirm" &&
                        isSafeApiRead(request) && RuntimeRecoveryPolicy.isTransportFailure(error) && reported.compareAndSet(false, true)
                    ) {
                        apiFailure(actualEndpoint, error.javaClass.simpleName)
                    }
                    throw error
                }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = observed
            }).build()
        } catch (error: IOException) {
            // Cancellation is not evidence that the pinned IP is unhealthy (including stale pages).
            if (isCancelled()) throw error
            val actualEndpoint = JmEndpointRequestContext.current(host, isImage)?.endpoint ?: endpoint
            if (requestStillCurrent() && actualEndpoint != null && RuntimeRecoveryPolicy.isTransportFailure(error)) {
                if (isImage) {
                    imageFailure(actualEndpoint, error.javaClass.simpleName)
                } else if (request.url.encodedPath != "/confirm" && isSafeApiRead(request) &&
                    RuntimeRecoveryPolicy.mayRecoverApi(request.method, error)
                ) {
                    apiFailure(actualEndpoint, error.javaClass.simpleName)
                }
            }
            throw error
        } finally {
            JmEndpointRequestContext.restore(previousPin)
        }
    }
}
