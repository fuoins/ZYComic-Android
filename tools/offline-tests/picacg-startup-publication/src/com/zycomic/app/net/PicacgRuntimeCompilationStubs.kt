package com.zycomic.app.net

import okhttp3.Interceptor
import okhttp3.Response

internal fun isPublicIpv4(address: String): Boolean =
    address.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull()?.let { number -> number in 0..255 } == true } }

internal object PicacgApiRuntimeRecovery {
    fun isProbeAllowed(endpoint: DomainIp): Boolean = true
    fun recordProbeResponse(endpoint: DomainIp, code: Int, retryAfter: String?) = Unit
}

internal object RuntimeNetworkGate {
    fun isOnline(): Boolean = true
    fun failureMessage(): String = "offline test"
}

enum class NetworkScope(val storageKey: String) {
    DIRECT("direct"),
    VPN("vpn"),
    ;

    val displayName: String get() = if (this == VPN) "VPN" else "直连"
}

enum class SpeedTestTimeSlot(val storageKey: String, val displayName: String) {
    DAY("day", "06:00-18:00"),
    NIGHT("night", "18:00-06:00"),
}

internal object SpeedTestStorage {
    const val TIME_SLOT_MIGRATED_KEY = "speed_test_time_slot_storage_v1"
    fun bucketKey(scope: NetworkScope, slot: SpeedTestTimeSlot = SpeedTestTimeSlot.DAY): String =
        if (scope == NetworkScope.VPN) scope.storageKey else "${scope.storageKey}_${slot.storageKey}"
    fun displayName(scope: NetworkScope, slot: SpeedTestTimeSlot): String =
        if (scope == NetworkScope.VPN) scope.displayName else "${scope.displayName} · ${slot.displayName}"
}

object BackgroundSpeedTestSweep {
    const val CHUNK_SIZE = 12
}

internal class RuntimeImageProbeGuard(platform: RuntimeRecoveryPolicy.Platform, endpoint: DomainIp) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
