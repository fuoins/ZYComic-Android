package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred

/** All automatic callers wait for the startup config check, including cached/offline startup. */
internal class StartupSpeedTestGate {
    private val checked = CompletableDeferred<Boolean>()

    fun complete(configurationUpdated: Boolean) {
        checked.complete(configurationUpdated)
    }

    suspend fun awaitConfigurationUpdated(): Boolean = checked.await()
}
