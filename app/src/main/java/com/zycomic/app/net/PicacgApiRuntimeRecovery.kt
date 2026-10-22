package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Tracks online read-only API transport failures and runs a separate, non-replaying route check. */
internal object PicacgApiRuntimeRecovery {
    private val failures = PicacgApiFailureWindow()
    private val retryAfter = RuntimeRetryAfterPolicy()
    private val gate = RuntimeRecoveryPolicy.Gate(COOLDOWN_MS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isProbeAllowed(endpoint: DomainIp): Boolean = RuntimeNetworkGate.isOnline() && !retryAfter.isBlocked(endpoint)

    fun recordProbeResponse(endpoint: DomainIp, code: Int, header: String?) {
        if (code == 429) retryAfter.record(endpoint, header)
    }

    fun recordResponse(endpoint: DomainIp, code: Int, retryAfterHeader: String?, requestNetworkRevision: Long) {
        if (!RuntimeNetworkGate.isOnline() || RuntimeNetworkGate.revision != requestNetworkRevision) return
        failures.recordResponse(endpoint, requestNetworkRevision, online = true)
        if (code == 429) retryAfter.record(endpoint, retryAfterHeader)
    }

    fun recordConnectionFailure(endpoint: DomainIp, requestNetworkRevision: Long) {
        if (!RuntimeNetworkGate.isOnline() || RuntimeNetworkGate.revision != requestNetworkRevision) return
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.PICACG)) return
        if (failures.recordFailure(endpoint, requestNetworkRevision, online = true)) {
            scheduleRecovery(endpoint, requestNetworkRevision)
        }
    }

    private fun scheduleRecovery(failedEndpoint: DomainIp, networkRevision: Long) {
        if (retryAfter.isBlocked(failedEndpoint) || !RuntimeNetworkGate.isOnline() ||
            RuntimeNetworkGate.revision != networkRevision || !StartupPlatformPolicy.isEnabled(ContentPlatform.PICACG)
        ) {
            return
        }
        val configRevision = PicacgRouteManager.configRevision
        val platformRevision = StartupPlatformPolicy.state.value.revision
        val failedHost = PicacgRouteManager.selectedApiHost ?: return
        if (!failedHost.equals(failedEndpoint.domain, true) ||
            PicacgRouteManager.currentApiEndpointFor(failedHost)?.normalizedForComparison() != failedEndpoint.normalizedForComparison() ||
            PicacgRouteManager.isApiManuallyPinned
        ) {
            return
        }
        if (!gate.tryAcquire(monotonicMillis())) return
        scope.launch {
            try {
                if (!isCurrent(failedEndpoint, failedHost, configRevision, platformRevision, networkRevision)) return@launch
                val candidates = PicacgRouteManager.apiRecoveryCandidates(failedHost)
                for (candidate in candidates) {
                    if (retryAfter.isBlocked(candidate.endpoint) ||
                        !isCurrent(failedEndpoint, failedHost, configRevision, platformRevision, networkRevision)
                    ) {
                        continue
                    }
                    val valid = PicacgSpeedTester.validateRuntimeApiEndpoint(candidate.endpoint, candidate.url)
                    if (!valid) continue
                    if (!isCurrent(failedEndpoint, failedHost, configRevision, platformRevision, networkRevision)) return@launch
                    if (PicacgRouteManager.advanceRuntimeApiAfterRecovery(
                            expectedHost = failedHost,
                            expectedEndpoint = failedEndpoint,
                            expectedConfigRevision = configRevision,
                            expectedNetworkRevision = networkRevision,
                            expectedPlatformRevision = platformRevision,
                            validatedCandidate = candidate,
                        )
                    ) {
                        return@launch
                    }
                }
            } finally {
                gate.release(monotonicMillis())
            }
        }
    }

    private fun isCurrent(
        failedEndpoint: DomainIp,
        failedHost: String,
        configRevision: Long,
        platformRevision: Long,
        networkRevision: Long,
    ): Boolean = RuntimeNetworkGate.isOnline() && RuntimeNetworkGate.revision == networkRevision &&
        StartupPlatformPolicy.isEnabled(ContentPlatform.PICACG) &&
        StartupPlatformPolicy.state.value.revision == platformRevision &&
        PicacgRouteManager.configRevision == configRevision &&
        PicacgRouteManager.selectedApiHost.equals(failedHost, true) &&
        PicacgRouteManager.currentApiEndpointFor(failedHost)?.normalizedForComparison() == failedEndpoint.normalizedForComparison() &&
        !PicacgRouteManager.isApiManuallyPinned && !retryAfter.isBlocked(failedEndpoint)

    private fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

    private const val FAILURE_THRESHOLD = 3
    private const val COOLDOWN_MS = 30_000L
    private const val NANOS_PER_MILLI = 1_000_000L
}

/** Observes the real Picacg OkHttp path; it never retries the failed call or writes. */
internal class PicacgApiRuntimeRecoveryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isSafeApiRead(request)) return chain.proceed(request)
        val endpoint = PicacgRouteManager.currentApiEndpointFor(request.url.host) ?: return chain.proceed(request)
        if (!RuntimeNetworkGate.isOnline()) return chain.proceed(request)
        val networkRevision = RuntimeNetworkGate.revision
        val configRevision = PicacgRouteManager.configRevision
        fun mayReport() = !chain.call().isCanceled() && PicacgRouteManager.configRevision == configRevision &&
            PicacgRouteManager.currentApiEndpointFor(request.url.host)?.normalizedForComparison() == endpoint.normalizedForComparison()
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            if (mayReport() && RuntimeRecoveryPolicy.isTransportFailure(error)) {
                PicacgApiRuntimeRecovery.recordConnectionFailure(endpoint, networkRevision)
            }
            throw error
        }
        val body = response.body
        if (!response.isSuccessful || body == null || body.contentLength() == 0L || request.method == "HEAD") {
            if (mayReport()) PicacgApiRuntimeRecovery.recordResponse(endpoint, response.code, response.header("Retry-After"), networkRevision)
            return response
        }
        val reported = java.util.concurrent.atomic.AtomicBoolean()
        val guardedSource = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long = try {
                super.read(sink, byteCount).also { count ->
                    if (count == -1L && mayReport() && reported.compareAndSet(false, true)) {
                        PicacgApiRuntimeRecovery.recordResponse(endpoint, response.code, null, networkRevision)
                    }
                }
            } catch (error: IOException) {
                if (mayReport() && RuntimeRecoveryPolicy.isTransportFailure(error) && reported.compareAndSet(false, true)) {
                    PicacgApiRuntimeRecovery.recordConnectionFailure(endpoint, networkRevision)
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
