package com.zycomic.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

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

    suspend fun awaitConfigurationUpdated(): Boolean =
        // 配置检查是权威前置条件，但不能成为死等：远端不可达/检查流程异常时
        // 开屏页会永远停在“测速中”，用户点“重新完整测速”也没有任何放行路径。
        // 超时后按“未更新”继续，已有本地可用配置仍然生效。
        withTimeoutOrNull(CONFIG_WAIT_TIMEOUT_MS) { checked.await() } ?: false

    private companion object {
        /** 远端正向/反向代理异常时，超过这个时间就不再阻塞测速与开屏放行。 */
        const val CONFIG_WAIT_TIMEOUT_MS = 20_000L
    }
}
