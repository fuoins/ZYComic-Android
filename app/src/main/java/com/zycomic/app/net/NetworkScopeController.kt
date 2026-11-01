package com.zycomic.app.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point for keeping every platform's persisted results aligned with the transport
 * that is currently usable (direct per-IP strategy vs. system VPN).
 *
 * Switching scope only restores the persisted bucket of the target scope; it never probes.
 */
object NetworkScopeController {

    @Volatile
    var current: NetworkScope = NetworkScope.DIRECT
        private set

    @Volatile
    var activeTimeSlot: SpeedTestTimeSlot = SpeedTestTimeSlot.current()
        private set

    private val _scope = MutableStateFlow(current)
    /** Observable active scope for UI that shows per-scope results. */
    val scope: StateFlow<NetworkScope> = _scope.asStateFlow()

    private val _timeSlot = MutableStateFlow(activeTimeSlot)
    /** Observable day/night bucket for direct results. VPN ignores this value. */
    val timeSlot: StateFlow<SpeedTestTimeSlot> = _timeSlot.asStateFlow()

    /** User-facing bucket name, e.g. "直连 · 06:00-18:00" or "VPN". */
    val bucketDisplayName: String
        get() = SpeedTestStorage.displayName(current, activeTimeSlot)

    @Volatile
    private var initialized = false

    /** Idempotent; call once after [NetworkModule.init] so the active transport is detectable. */
    @Synchronized
    fun init() {
        if (initialized) return
        val target = NetworkScope.current()
        val slot = SpeedTestTimeSlot.current()
        current = target
        activeTimeSlot = slot
        _scope.value = target
        _timeSlot.value = slot
        RouteManager.setNetworkScope(target, slot)
        JmRouteManager.setNetworkScope(target, slot)
        PicacgRouteManager.setNetworkScope(target, slot)
        initialized = true
    }

    /** True when the transport or the direct day/night bucket no longer matches the loaded state. */
    internal fun needsSync(): Boolean {
        val target = NetworkScope.current()
        val slot = SpeedTestTimeSlot.current()
        return !initialized || current != target || (target == NetworkScope.DIRECT && activeTimeSlot != slot)
    }

    /**
     * Align all managers with the transport that is active right now and with the current
     * 06:00-18:00 / 18:00-06:00 direct bucket.
     *
     * @return true when the active bucket changed (or was never initialised), i.e. the HTTP stacks
     *         must be rebuilt. No speed test is triggered here.
     */
    @Synchronized
    fun sync(): Boolean {
        val target = NetworkScope.current()
        val slot = SpeedTestTimeSlot.current()
        val changed = !initialized || current != target || (target == NetworkScope.DIRECT && activeTimeSlot != slot)
        current = target
        activeTimeSlot = slot
        _scope.value = target
        _timeSlot.value = slot
        val routeChanged = RouteManager.setNetworkScope(target, slot)
        val jmChanged = JmRouteManager.setNetworkScope(target, slot)
        val picacgChanged = PicacgRouteManager.setNetworkScope(target, slot)
        initialized = true
        return changed || routeChanged || jmChanged || picacgChanged
    }
}
