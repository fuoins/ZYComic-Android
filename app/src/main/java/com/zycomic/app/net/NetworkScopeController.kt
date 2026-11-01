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

    private val _scope = MutableStateFlow(current)
    /** Observable active scope for UI that shows per-scope results. */
    val scope: StateFlow<NetworkScope> = _scope.asStateFlow()

    @Volatile
    private var initialized = false

    /** Idempotent; call once after [NetworkModule.init] so the active transport is detectable. */
    @Synchronized
    fun init() {
        if (initialized) return
        val target = NetworkScope.current()
        current = target
        _scope.value = target
        RouteManager.setNetworkScope(target)
        JmRouteManager.setNetworkScope(target)
        PicacgRouteManager.setNetworkScope(target)
        initialized = true
    }

    /**
     * Align all managers with the transport that is active right now.
     *
     * @return true when the active scope changed (or was never initialised), i.e. the HTTP stacks
     *         must be rebuilt. No speed test is triggered here.
     */
    @Synchronized
    fun sync(): Boolean {
        val target = NetworkScope.current()
        val changed = !initialized || current != target
        current = target
        _scope.value = target
        val routeChanged = RouteManager.setNetworkScope(target)
        val jmChanged = JmRouteManager.setNetworkScope(target)
        val picacgChanged = PicacgRouteManager.setNetworkScope(target)
        initialized = true
        return changed || routeChanged || jmChanged || picacgChanged
    }
}
