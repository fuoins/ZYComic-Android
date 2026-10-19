package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

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
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val isImage = request.url.encodedPath.startsWith("/media/")
        val endpoint = if (isImage) imageEndpoint(host) else apiEndpoint(host)
        val hasMeasurements = if (isImage) imageHasMeasurements(host) else apiHasMeasurements(host)
        val previousPin = JmEndpointRequestContext.install(
            JmEndpointRequestContext.Pin(host, isImage, endpoint, bootstrapAllowed = !hasMeasurements),
        )
        try {
            if (isImage) prepareImageRoute(host) else prepareApiRoute(host)
            val response = chain.proceed(request)
            val requestPin = JmEndpointRequestContext.current(host, isImage)
            val actualEndpoint = requestPin?.endpoint ?: endpoint
            if (response.isSuccessful && requestPin?.bootstrapAllowed == true && actualEndpoint != null) {
                runCatching { if (isImage) imageSuccess(actualEndpoint) else apiSuccess(actualEndpoint) }
            }
            val transientHttpFailure = response.code == 408 || response.code == 429 || response.code >= 500
            if (transientHttpFailure && actualEndpoint != null) {
                if (isImage) {
                    imageFailure(actualEndpoint, "JM 图源 HTTP ${response.code}")
                } else if (request.url.encodedPath != "/confirm") {
                    apiFailure(actualEndpoint, "JM API HTTP ${response.code}")
                }
            }
            return response
        } catch (error: IOException) {
            val actualEndpoint = JmEndpointRequestContext.current(host, isImage)?.endpoint ?: endpoint
            if (actualEndpoint != null) {
                if (isImage) {
                    imageFailure(actualEndpoint, error.javaClass.simpleName)
                } else if (request.url.encodedPath != "/confirm") {
                    apiFailure(actualEndpoint, error.javaClass.simpleName)
                }
            }
            throw error
        } finally {
            JmEndpointRequestContext.restore(previousPin)
        }
    }
}
