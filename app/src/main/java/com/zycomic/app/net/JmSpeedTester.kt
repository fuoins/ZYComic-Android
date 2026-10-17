package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** JM 独立测速器，探测 /setting 与报告中确认的默认图床测速资源。 */
object JmSpeedTester {

    private val lock = Mutex()
    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    suspend fun run(): JmRouteManager.SpeedTestSnapshot = lock.withLock {
        check(running.compareAndSet(false, true)) { "JM 测速已在运行" }
        try {
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                val lines = linkedMapOf<String, Long>()
                val images = linkedMapOf<String, Long>()
                val probeClient = JmNetworkModule.newProbeClient()
                try {
                    JmRouteManager.lineHosts.forEach { line ->
                        val host = runCatching { java.net.URI(line).host }.getOrNull().orEmpty()
                        if (host.isNotBlank()) {
                            val delay = measure(probeClient, "${line.trimEnd('/')}/setting?lang=TW", image = false)
                            lines[host] = delay
                            JmRouteManager.record("speed_line", host, if (delay < Long.MAX_VALUE) "${delay}ms" else "JM 线路不可达")
                        }
                    }
                    JmRouteManager.imageDomains.forEach { host ->
                        val delay = measure(probeClient, "https://$host/media/users/nopic-.gif?v=0", image = true)
                        images[host] = delay
                        JmRouteManager.record("speed_image", host, if (delay < Long.MAX_VALUE) "${delay}ms" else "JM 图床不可达/无图片响应")
                    }
                } finally {
                    probeClient.connectionPool.evictAll()
                }
                JmRouteManager.SpeedTestSnapshot(
                    lineDelaysMs = lines,
                    imageDelaysMs = images,
                    startedAtEpochMs = started,
                    completedAtEpochMs = System.currentTimeMillis(),
                    remoteVersion = JmRouteManager.state.value.remoteVersion,
                ).also(JmRouteManager::recordSpeedTest)
            }
        } finally {
            running.set(false)
        }
    }

    private fun measure(client: okhttp3.OkHttpClient, url: String, image: Boolean): Long {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", JmApiInterceptor.USER_AGENT)
            .header("Origin", JmApiInterceptor.ORIGIN)
            .header("Referer", JmApiInterceptor.REFERER)
            .header("X-Requested-With", JmApiInterceptor.PACKAGE_NAME)
            .apply { if (image) header("Accept", "image/*,*/*;q=0.8") else header("Accept", "application/json") }
            .build()
        val started = System.nanoTime()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                val valid = response.isSuccessful && if (image) {
                    response.header("Content-Type")?.startsWith("image/", ignoreCase = true) == true && body.isNotEmpty()
                } else {
                    val json = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                    json?.optInt("code", -1) == 200
                }
                if (valid) (System.nanoTime() - started) / 1_000_000L else Long.MAX_VALUE
            }
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }
}
