package com.zycomic.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

object PlatformSpeedTestCoordinator {
    @Volatile var networkIdentity: String = "jm-test-network"
    fun currentNetworkIdentity(): String = networkIdentity
}

object DevConfig {
    @JvmStatic var appContext: android.content.Context? = null
    @JvmStatic var followSystemVpn: Boolean = false
    fun isFollowSystemVpnEnabled(): Boolean = followSystemVpn
}

object NetworkModule {
    @JvmStatic var systemVpnActive: Boolean = false
    fun isSystemVpnActive(): Boolean = systemVpnActive
    fun shouldUseDirectStrategy(): Boolean = !systemVpnActive
}

enum class NetworkScope(val storageKey: String) {
    DIRECT("direct"),
    VPN("vpn"),
    ;

    val displayName: String get() = if (this == VPN) "VPN" else "直连"

    companion object {
        fun current(): NetworkScope = if (NetworkModule.shouldUseDirectStrategy()) DIRECT else VPN
    }
}

enum class SpeedTestTimeSlot(val storageKey: String, val displayName: String) {
    DAY("day", "06:00-18:00"),
    NIGHT("night", "18:00-06:00"),
    ;

    companion object {
        fun current(): SpeedTestTimeSlot = DAY
        fun millisUntilNextBoundary(): Long = 1_000L
    }
}

internal object SpeedTestStorage {
    const val TIME_SLOT_MIGRATED_KEY = "speed_test_time_slot_storage_v1"
    fun bucketKey(scope: NetworkScope, slot: SpeedTestTimeSlot = SpeedTestTimeSlot.current()): String =
        if (scope == NetworkScope.VPN) scope.storageKey else "${scope.storageKey}_${slot.storageKey}"
    fun displayName(scope: NetworkScope, slot: SpeedTestTimeSlot): String =
        if (scope == NetworkScope.VPN) scope.displayName else "${scope.displayName} · ${slot.displayName}"
}

object BackgroundSpeedTestSweep {
    const val CHUNK_SIZE = 12
}

object JmNetworkModule {
    val paths = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
    var failedHosts: Set<String> = emptySet()
    var responseDelayMs: ((String, String, Int) -> Long)? = null
    private val responseCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    fun resetProbeCounts() = responseCounts.clear()
    var validationHook: ((DomainIp) -> Boolean)? = null
    var validations = mutableListOf<DomainIp>()
    fun rebindApiEndpoint() {}
    fun rebindImageEndpoint() {}
    fun evictIdleEndpointPools() {}
    fun validateApiEndpoint(endpoint: DomainIp): Boolean {
        validations += endpoint
        return validationHook?.invoke(endpoint) ?: true
    }
    fun newProbeClient() = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        paths += request.url.host to request.url.encodedPath
        urls += request.url.toString()
        val key = request.url.host + request.url.encodedPath
        val sequence = responseCounts.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        responseDelayMs?.invoke(request.url.host, request.url.encodedPath, sequence)?.let { delay ->
            if (delay > 0) Thread.sleep(delay)
        }
        val image = request.url.encodedPath.startsWith("/media/")
        val failed = request.url.host in failedHosts
        val type = if (image) "image/gif" else "application/json"
        val bytes = if (image) "GIF89a" else "{\"code\":200}"
        okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(if (failed) 503 else 200).message(if (failed) "Unavailable" else "OK")
            .header("Content-Type", type)
            .body(bytes.toResponseBody(type.toMediaType())).build()
    }.build()
}

class JmApiInterceptor(val recordRouteHealth: Boolean = true) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response = chain.proceed(chain.request())
    companion object {
        const val USER_AGENT = "test"
        const val ORIGIN = "https://example.test"
        const val REFERER = "https://example.test"
        const val PACKAGE_NAME = "test"
    }
}

object RuntimeNetworkGate {
    @Volatile var revision: Long = 0L
    fun isOnline(): Boolean = true
}

internal class RuntimeImageProbeGuard(platform: RuntimeRecoveryPolicy.Platform, endpoint: DomainIp) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response = chain.proceed(chain.request())
}
