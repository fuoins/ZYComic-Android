package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.IOException
import java.net.URI
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A scoped owner for the JM browse home/catalog GET only. Capturing the selected host and exact IP
 * prevents the one retry from silently becoming a line/IP failover.
 */
internal class JmHomeRequestScope private constructor(
    private val ownerIsCurrent: () -> Boolean,
    private val expectedBaseUrl: String,
    private val expectedHost: String,
    private val expectedEndpoint: DomainIp?,
    private val configRevision: Long,
    private val platformRevision: Long,
    private val networkRevision: Long,
    private val accountRevision: Long,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<JmHomeRequestScope> {
        fun capture(ownerIsCurrent: () -> Boolean): JmHomeRequestScope {
            val baseUrl = JmRouteManager.baseUrl
            val host = runCatching { URI(baseUrl).host.orEmpty() }.getOrDefault("")
            return JmHomeRequestScope(
                ownerIsCurrent = ownerIsCurrent,
                expectedBaseUrl = baseUrl,
                expectedHost = host,
                expectedEndpoint = host.takeIf(String::isNotBlank)?.let(JmRouteManager::currentLineEndpoint),
                configRevision = JmRouteManager.runtimeConfigRevision,
                platformRevision = StartupPlatformPolicy.state.value.revision,
                networkRevision = RuntimeNetworkGate.revision,
                accountRevision = JmAuthStore.sessionRevision,
            )
        }
    }

    fun isCurrent(): Boolean = ownerIsCurrent() && expectedBaseUrl == JmRouteManager.baseUrl &&
        JmRouteManager.runtimeConfigRevision == configRevision &&
        StartupPlatformPolicy.state.value.revision == platformRevision &&
        StartupPlatformPolicy.isEnabled(ContentPlatform.JM) &&
        RuntimeNetworkGate.isOnline() && RuntimeNetworkGate.revision == networkRevision &&
        JmAuthStore.sessionRevision == accountRevision &&
        expectedHost.isNotBlank() && (
            expectedEndpoint == null ||
                sameEndpoint(expectedEndpoint, JmRouteManager.currentLineEndpoint(expectedHost))
            )

    /** A retry needs a concrete, stable domain/IP pin; DNS/bootstrap routes are intentionally excluded. */
    fun hasPinnedEndpoint(): Boolean = expectedEndpoint != null

    fun matches(host: String, endpoint: DomainIp?): Boolean = isCurrent() &&
        host.equals(expectedHost, ignoreCase = true) &&
        (expectedEndpoint == null || sameEndpoint(expectedEndpoint, endpoint))

    fun requireCurrent() {
        if (!isCurrent()) throw CancellationException("JM browse request is no longer current")
    }

    fun tag(request: Request): Request = request.newBuilder().tag(JmHomeRequestScope::class.java, this).build()

    private fun sameEndpoint(first: DomainIp?, second: DomainIp?): Boolean =
        first?.normalizedForComparison() == second?.normalizedForComparison()
}

/** The retry is intentionally specific to safe JM home/catalog GETs; it never replays writes or other APIs. */
internal object JmHomeGetRetryPolicy {
    const val BACKOFF_MS = 250L
    private val eligiblePaths = setOf("/promote", "/promote_list")

    fun shouldRetry(
        path: String,
        method: String,
        attempt: Int,
        error: IOException,
        endpointPinned: Boolean,
        isCurrent: Boolean,
    ): Boolean = path in eligiblePaths && method.equals("GET", ignoreCase = true) && attempt == 0 &&
        endpointPinned && isCurrent && RuntimeRecoveryPolicy.isTransportFailure(error)

    suspend fun <T> run(
        path: String,
        method: String,
        scope: JmHomeRequestScope?,
        request: suspend () -> T,
    ): T {
        val activeScope = scope ?: return request()
        activeScope.requireCurrent()
        val firstResult = try {
            request()
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (!shouldRetry(path, method, 0, error, activeScope.hasPinnedEndpoint(), activeScope.isCurrent())) throw error
            delay(BACKOFF_MS)
            currentCoroutineContext().ensureActive()
            activeScope.requireCurrent()
            // The callback constructs a new OkHttp Call, which passes through the normal fresh-signing path.
            val retryResult = request()
            currentCoroutineContext().ensureActive()
            activeScope.requireCurrent()
            return retryResult
        }
        currentCoroutineContext().ensureActive()
        activeScope.requireCurrent()
        return firstResult
    }
}
