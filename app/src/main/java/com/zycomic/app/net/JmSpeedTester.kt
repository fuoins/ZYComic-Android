package com.zycomic.app.net

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** JM 独立测速器；只探测 JM API 线路和 sources2 图源池。 */
object JmSpeedTester {

    enum class ImgVerdict { OK, UNREACHABLE, NO_IMAGE }

    private data class ProbeResult(val delayMs: Long, val verdict: ImgVerdict? = null)
    private data class LineTarget(val endpoint: DomainIp, val url: String)

    private val lock = Mutex()
    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    /**
     * 测试始终重新探测 API 线路。仅当调用方明确要求且 JM 图源缓存仍匹配当前图源配置/网络策略时，
     * 才复用图源域名与逐 IP 结果。
     */
    suspend fun run(reuseImages: Boolean = false): JmRouteManager.SpeedTestSnapshot = lock.withLock {
        check(running.compareAndSet(false, true)) { "JM 测速已在运行" }
        try {
            withContext(Dispatchers.IO) {
                val platformRevision = StartupPlatformPolicy.state.value.revision
                if (!StartupPlatformPolicy.isEnabled(ContentPlatform.JM)) {
                    return@withContext JmRouteManager.SpeedTestSnapshot()
                }
                val configRevision = JmRouteManager.currentSpeedTestConfigRevision()
                val configFingerprint = JmRouteManager.currentSpeedTestConfigFingerprint()
                val imageConfigFingerprint = JmRouteManager.currentImageConfigFingerprint()
                val imageNetworkPolicy = JmRouteManager.currentImageNetworkPolicy()
                val linesToTest = JmRouteManager.lineHosts.toList()
                // Deliberately read only the active JM sources2 pool, never Manwa image domains.
                val imagesToTest = JmRouteManager.imageDomains.toList()
                fun stillCurrent(): Boolean =
                    StartupPlatformPolicy.isEnabled(ContentPlatform.JM) &&
                        StartupPlatformPolicy.state.value.revision == platformRevision &&
                        JmRouteManager.currentSpeedTestConfigRevision() == configRevision &&
                        JmRouteManager.currentSpeedTestConfigFingerprint() == configFingerprint &&
                        JmRouteManager.currentImageConfigFingerprint() == imageConfigFingerprint &&
                        JmRouteManager.currentImageNetworkPolicy() == imageNetworkPolicy &&
                        JmRouteManager.lineHosts == linesToTest &&
                        JmRouteManager.imageDomains == imagesToTest

                val reusableImages = if (reuseImages) {
                    JmRouteManager.reusableImageSnapshotOrNull()?.takeIf { snapshot ->
                        stillCurrent() &&
                            snapshot.imageIpDelaysMs.keys == imagesToTest.flatMap(::imageEndpoints).toSet() &&
                            snapshot.imageConfigFingerprint == imageConfigFingerprint &&
                            snapshot.imageNetworkPolicy == imageNetworkPolicy &&
                            snapshot.imageDelaysMs.keys.map { it.lowercase(Locale.ROOT) }.toSet() ==
                            imagesToTest.map { it.lowercase(Locale.ROOT) }.toSet()
                    }
                } else {
                    null
                }
                val started = System.currentTimeMillis()
                val lineDelays = linkedMapOf<String, Long>()
                val lineIpDelays = linkedMapOf<DomainIp, Long>()
                val imageDelays: Map<String, Long>
                val imageIpDelays: Map<DomainIp, Long>
                val imageIpVerdicts: Map<DomainIp, ImgVerdict>
                val probeClient = JmNetworkModule.newProbeClient()
                try {
                    val lineTargets = lineTargets(linesToTest)
                    val groupedLineTargets = lineTargets.groupBy { it.endpoint.domain.lowercase(Locale.ROOT) }
                    for (url in linesToTest) {
                        if (!stillCurrent()) break
                        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
                        if (host.isBlank()) continue
                        val targets = groupedLineTargets[host.lowercase(Locale.ROOT)].orEmpty()
                        if (targets.isEmpty()) {
                            JmRouteManager.record("speed_line_ip", host, "JM API 未解析到可测 IP")
                            lineDelays[host.lowercase(Locale.ROOT)] = Long.MAX_VALUE
                            continue
                        }
                        val results = targets.map { target ->
                            if (stillCurrent()) {
                                val routed = newProbeClientForIp(probeClient, target.endpoint)
                                try {
                                    target.endpoint to measure(routed, target.url, image = false)
                                } finally {
                                    routed.connectionPool.evictAll()
                                }
                            } else {
                                target.endpoint to ProbeResult(Long.MAX_VALUE)
                            }
                        }
                        if (!stillCurrent()) break
                        results.forEach { (endpoint, result) ->
                            lineIpDelays[endpoint] = result.delayMs
                            val value = result.delayMs.takeIf { it < Long.MAX_VALUE }?.let { "${it}ms" } ?: "不可达"
                            JmRouteManager.record("speed_line_ip", "${endpoint.domain} [${endpoint.ip}]", "JM API $value")
                        }
                        val delay = results.map { it.second.delayMs }.filter { it < Long.MAX_VALUE }.minOrNull() ?: Long.MAX_VALUE
                        lineDelays[host.lowercase(Locale.ROOT)] = delay
                        JmRouteManager.record("speed_line", host, if (delay < Long.MAX_VALUE) "${delay}ms" else "JM 线路不可达")
                    }

                    if (reusableImages != null && stillCurrent()) {
                        imageDelays = reusableImages.imageDelaysMs
                        imageIpDelays = reusableImages.imageIpDelaysMs
                        imageIpVerdicts = reusableImages.imageIpVerdicts
                        imageIpDelays.forEach { (endpoint, delay) ->
                            JmRouteManager.record(
                                "speed_image_ip",
                                "${endpoint.domain} [${endpoint.ip}]",
                                "JM 图源缓存复用：${delay.takeIf { it < Long.MAX_VALUE }?.let { "${it}ms" } ?: "不可达"}，" +
                                    "verdict=${imageIpVerdicts[endpoint]?.name?.lowercase(Locale.ROOT) ?: "unknown"}",
                            )
                        }
                        imagesToTest.forEach { domain ->
                            val delay = imageDelays.entries.firstOrNull { it.key.equals(domain, true) }?.value ?: Long.MAX_VALUE
                            JmRouteManager.record(
                                "speed_image",
                                domain,
                                if (delay < Long.MAX_VALUE) "JM 图源缓存复用：${delay}ms" else "JM 图源缓存复用：不可达/无图片响应",
                            )
                        }
                    } else {
                        val delays = linkedMapOf<String, Long>()
                        val ipDelays = linkedMapOf<DomainIp, Long>()
                        val ipVerdicts = linkedMapOf<DomainIp, ImgVerdict>()
                        for (domain in imagesToTest) {
                            if (!stillCurrent()) break
                            val endpoints = imageEndpoints(domain)
                            if (endpoints.isEmpty()) {
                                JmRouteManager.record("speed_image_ip", domain, "JM 图源未解析到可测 IP")
                                delays[domain.lowercase(Locale.ROOT)] = Long.MAX_VALUE
                                continue
                            }
                            val results = endpoints.map { endpoint ->
                                if (stillCurrent()) {
                                    val routed = newProbeClientForIp(probeClient, endpoint)
                                    try {
                                        endpoint to measure(routed, "https://${endpoint.domain}/media/users/nopic-.gif?v=0", image = true)
                                    } finally {
                                        routed.connectionPool.evictAll()
                                    }
                                } else {
                                    endpoint to ProbeResult(Long.MAX_VALUE, ImgVerdict.UNREACHABLE)
                                }
                            }
                            if (!stillCurrent()) break
                            results.forEach { (endpoint, result) ->
                                val verdict = result.verdict ?: ImgVerdict.UNREACHABLE
                                ipDelays[endpoint] = result.delayMs
                                ipVerdicts[endpoint] = verdict
                                JmRouteManager.record(
                                    "speed_image_ip",
                                    "${endpoint.domain} [${endpoint.ip}]",
                                    "JM 图源 ${verdict.name.lowercase(Locale.ROOT)}; " +
                                        "${result.delayMs.takeIf { it < Long.MAX_VALUE }?.let { "${it}ms" } ?: "不可用"}",
                                )
                            }
                            val delay = results.map { it.second.delayMs }.filter { it < Long.MAX_VALUE }.minOrNull() ?: Long.MAX_VALUE
                            delays[domain.lowercase(Locale.ROOT)] = delay
                            val verdict = when {
                                results.any { it.second.verdict == ImgVerdict.OK } -> ImgVerdict.OK
                                results.any { it.second.verdict == ImgVerdict.NO_IMAGE } -> ImgVerdict.NO_IMAGE
                                else -> ImgVerdict.UNREACHABLE
                            }
                            JmRouteManager.record(
                                "speed_image",
                                domain,
                                when (verdict) {
                                    ImgVerdict.OK -> "${delay}ms"
                                    ImgVerdict.NO_IMAGE -> "JM 图床可连接但无图片响应"
                                    ImgVerdict.UNREACHABLE -> "JM 图床不可达"
                                },
                            )
                        }
                        imageDelays = delays
                        imageIpDelays = ipDelays
                        imageIpVerdicts = ipVerdicts
                    }
                } finally {
                    probeClient.connectionPool.evictAll()
                }
                val snapshot = JmRouteManager.SpeedTestSnapshot(
                    lineDelaysMs = lineDelays,
                    imageDelaysMs = imageDelays,
                    lineIpDelaysMs = lineIpDelays,
                    imageIpDelaysMs = imageIpDelays,
                    imageIpVerdicts = imageIpVerdicts,
                    startedAtEpochMs = started,
                    completedAtEpochMs = System.currentTimeMillis(),
                    remoteVersion = JmRouteManager.state.value.remoteVersion,
                    configFingerprint = configFingerprint,
                    imageConfigFingerprint = imageConfigFingerprint,
                    imageNetworkPolicy = imageNetworkPolicy,
                    imageResultsReused = reusableImages != null,
                )
                if (stillCurrent() && lineDelays.keys == linesToTest.mapNotNull { runCatching { URI(it).host }.getOrNull()?.lowercase(Locale.ROOT) }.toSet() &&
                    imageDelays.keys.map { it.lowercase(Locale.ROOT) }.toSet() == imagesToTest.map { it.lowercase(Locale.ROOT) }.toSet()
                ) {
                    JmRouteManager.recordSpeedTest(snapshot, platformRevision)
                }
                snapshot
            }
        } finally {
            running.set(false)
        }
    }

    /** Apply a persisted exact-match snapshot without issuing any requests. */
    suspend fun reuseValidResults(): Boolean = lock.withLock {
        val revision = StartupPlatformPolicy.state.value.revision
        if (!StartupPlatformPolicy.isEnabled(ContentPlatform.JM)) return@withLock false
        withContext(Dispatchers.IO) {
            StartupPlatformPolicy.state.value.revision == revision &&
                StartupPlatformPolicy.isEnabled(ContentPlatform.JM) &&
                JmRouteManager.reuseValidResults(revision) &&
                StartupPlatformPolicy.state.value.revision == revision &&
                StartupPlatformPolicy.isEnabled(ContentPlatform.JM)
        }
    }

    private fun lineTargets(lines: List<String>): List<LineTarget> = lines.flatMap { url ->
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        if (host.isBlank()) return@flatMap emptyList()
        DomainIpProbePolicy.endpoints(host, resolveProbeIps(host)).map { LineTarget(it, url.trimEnd('/') + "/setting?lang=TW") }
    }.distinctBy { it.endpoint }

    private fun imageEndpoints(domain: String): List<DomainIp> =
        DomainIpProbePolicy.endpoints(domain, resolveProbeIps(domain))

    private fun resolveProbeIps(host: String): List<String> {
        val configured = JmRouteManager.resolveIp(host).map(String::trim).filter(String::isNotEmpty).distinct()
        if (configured.isNotEmpty()) {
            return configured.mapNotNull { ip -> runCatching { InetAddress.getByName(ip).hostAddress.substringBefore('%') }.getOrNull() }.distinct()
        }
        return runCatching { InetAddress.getAllByName(host).map { it.hostAddress.substringBefore('%') }.distinct() }
            .getOrDefault(emptyList())
    }

    /** Child DNS pins one address while each request URL retains the domain for Host and TLS SNI. */
    private fun newProbeClientForIp(client: OkHttpClient, endpoint: DomainIp): OkHttpClient =
        client.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname.equals(endpoint.domain, ignoreCase = true)) {
                        listOf(InetAddress.getByName(endpoint.ip))
                    } else {
                        client.dns.lookup(hostname)
                    }
            })
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()

    private fun measure(client: OkHttpClient, url: String, image: Boolean): ProbeResult {
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
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                if (valid) {
                    ProbeResult(elapsed, if (image) ImgVerdict.OK else null)
                } else if (image && response.isSuccessful) {
                    ProbeResult(Long.MAX_VALUE, ImgVerdict.NO_IMAGE)
                } else {
                    ProbeResult(Long.MAX_VALUE, if (image) ImgVerdict.UNREACHABLE else null)
                }
            }
        } catch (_: Exception) {
            ProbeResult(Long.MAX_VALUE, if (image) ImgVerdict.UNREACHABLE else null)
        }
    }
}
