package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred

/** Cached probes may warm up, but no selection is committed before this check completes. */
internal class StartupSpeedTestGate {
    private val checked = CompletableDeferred<Boolean>()

    @Volatile var configurationUpdated: Boolean? = null
        private set

    fun complete(configurationUpdated: Boolean) {
        synchronized(this) {
            if (this.configurationUpdated != null) return
            this.configurationUpdated = configurationUpdated
            checked.complete(configurationUpdated)
        }
    }

    suspend fun awaitConfigurationUpdated(): Boolean = checked.await()
}
