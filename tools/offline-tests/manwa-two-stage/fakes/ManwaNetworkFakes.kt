package com.zycomic.app.net

import android.content.Context
import com.zycomic.app.data.dto.Manga
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object DevConfig {
    @JvmStatic var appContext: Context? = null
    fun getConfigJson(): String = "{}"
    fun getSniDomains(): Set<String> = emptySet()
    fun isFollowSystemVpnEnabled(): Boolean = false
}

object RuntimeRecoveryPolicy {
    enum class Platform { MANWA, PICACG, JM }
    fun mayRecoverApi(method: String, error: java.io.IOException): Boolean = method.equals("GET", true)
}

object RuntimeNetworkGate {
    val revision: Long = 0L
    fun isOnline(): Boolean = true
}

class RuntimeRetryAfterPolicy {
    fun isBlocked(endpoint: DomainIp): Boolean = false
    fun record(endpoint: DomainIp, retryAfter: String?) = Unit
}

class RuntimeImageProbeGuard(
    platform: RuntimeRecoveryPolicy.Platform,
    endpoint: DomainIp,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

object PlatformSpeedTestCoordinator {
    fun currentNetworkIdentity(): String = "manwa-offline-tests"
}

object ManwaProbeFixture {
    @Volatile var delaysByDomain: Map<String, Long> = emptyMap()
    @Volatile var invalidFixedImages = false
    @Volatile var samplePath: String? = null
    @Volatile var onSampleIndex: (() -> Unit)? = null
    @Volatile var onRequestStart: ((String, String) -> Unit)? = null
    @Volatile var onRequestEnd: ((String, String) -> Unit)? = null
    val started = CopyOnWriteArrayList<Pair<String, String>>()
    val completed = CopyOnWriteArrayList<Pair<String, String>>()
    val active = AtomicInteger()
    val perDomainRequests = ConcurrentHashMap<String, AtomicInteger>()

    fun reset() {
        delaysByDomain = emptyMap()
        invalidFixedImages = false
        samplePath = null
        onSampleIndex = null
        onRequestStart = null
        onRequestEnd = null
        started.clear()
        completed.clear()
        perDomainRequests.clear()
        active.set(0)
    }
}

object NetworkModule {
    private val probeClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val domain = request.url.host
        val path = request.url.encodedPath
        ManwaProbeFixture.started += domain to path
        ManwaProbeFixture.perDomainRequests.computeIfAbsent(domain) { AtomicInteger() }.incrementAndGet()
        ManwaProbeFixture.active.incrementAndGet()
        ManwaProbeFixture.onRequestStart?.invoke(domain, path)
        try {
            Thread.sleep(ManwaProbeFixture.delaysByDomain[domain] ?: 0L)
            val isLine = path == "/api/users/info"
            val invalidFixed = ManwaProbeFixture.invalidFixedImages && path.startsWith("/static/upload2/")
            val body = if (invalidFixed) "<html>no cover</html>".toByteArray()
            else if (isLine) "{\"code\":0,\"data\":{}}".toByteArray()
            else byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00, 0x01)
            val contentType = if (invalidFixed) "text/html" else if (isLine) "application/json" else "image/jpeg"
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (invalidFixed) 404 else 200)
                .message(if (invalidFixed) "Not Found" else "OK")
                .header("Content-Type", contentType)
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        } finally {
            ManwaProbeFixture.active.decrementAndGet()
            ManwaProbeFixture.completed += domain to path
            ManwaProbeFixture.onRequestEnd?.invoke(domain, path)
        }
    }.build()

    val cookieJar = CookieJar()
    val api = ProbeApi()
    fun newLineProbeClient(): OkHttpClient = probeClient
    fun newSpeedTestClient(): OkHttpClient = probeClient
    fun newProbeClientForIp(client: OkHttpClient, domain: String, ip: String): OkHttpClient = client
    fun newDiagnosticProbeClientForIp(client: OkHttpClient, domain: String, ip: String): OkHttpClient = client
    fun resolveProbeIps(domain: String): List<String> = RouteManager.customRule.entries
        .firstOrNull { it.key.equals(domain, true) }?.value.orEmpty()
    fun rebuildApi(reason: String) = Unit
    fun rebindImageRoute(reason: String) = Unit
    fun rebuild(reason: String) = Unit
    fun isSystemVpnActive(): Boolean = false
    fun validateApiEndpoint(endpoint: DomainIp): Boolean = true
    class CookieJar { fun syncToAllLines() = Unit }
    class ProbeApi {
        suspend fun index(): ProbeIndex? {
            ManwaProbeFixture.onSampleIndex?.invoke()
            return ManwaProbeFixture.samplePath?.let { ProbeIndex(Manga(pic = it)) }
        }
    }
    data class ProbeIndex(val data: Manga?)
}
