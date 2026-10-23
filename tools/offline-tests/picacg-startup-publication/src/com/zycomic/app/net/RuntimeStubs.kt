package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.Closeable

object SpeedTestTimings {
    enum class Phase { PICACG_LINE_PREPARATION, PICACG_LINES, PICACG_IMAGE_PREPARATION, PICACG_IMAGES }
}
object SpeedTestTimingLog {
    enum class Scope { PICACG }
    class Timings : Closeable {
        fun start(phase: SpeedTestTimings.Phase): Closeable = Closeable {}
        override fun close() = Unit
    }
    fun start(scope: Scope): Timings = Timings()
    fun recordQueueWait(scope: Scope, duration: Long) = Unit
    fun json(scope: Scope): JSONObject = JSONObject()
}
object SpeedTestProbeBudget {
    val scheduler = Scheduler
    object Scheduler {
        suspend fun <T> withPermit(platform: ContentPlatform, onWait: (Long) -> Unit = {}, block: suspend () -> T): T = block()
    }
}

object PlatformSpeedTestCoordinator {
    fun currentNetworkIdentity(): String = "offline-test-network"
}
