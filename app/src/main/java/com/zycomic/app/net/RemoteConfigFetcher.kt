package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 远程 ruledns 更新器。
 *
 * - 拉取 [REMOTE_CONFIG_URL]，GitHub raw/静态站风格：支持 ETag/If-None-Match（304 省流量），
 *   服务器不支持时退化为体内 version 比较；
 * - 使用**独立的、正常校验证书的 HTTPS 客户端**（系统 DNS、默认 TLS），
 *   绝不复用 trust-all / IP 直连客户端（ruledns 本身会改写 DNS/SNI，必须保证传输可信）；
 * - 严格校验通过且 version 严格递增才写入 [DevConfig]；首装无缓存时，配置未就绪前不开放 API/图片网络。
 * - 已有有效远程缓存时允许离线启动；异常或版本未变均保留当前可用配置。
 */
object RemoteConfigFetcher {

    const val REMOTE_CONFIG_URL = "https://zygongyi.de5.net/doh.json"

    /** 仅允许这些主机下发配置（URL 白名单）。 */
    private val ALLOWED_HOSTS = setOf("zygongyi.de5.net", "raw.githubusercontent.com")

    private const val MAX_BODY_BYTES = 256 * 1024
    private const val MAX_HOSTS = 200
    private const val MAX_IPS_PER_HOST = 16
    private const val MAX_TOTAL_IPS = 1000

    enum class Outcome { APPLIED, NOT_CHANGED, FAILED }

    data class Result(val outcome: Outcome, val message: String, val version: Int) {
        /** True only when DevConfig successfully replaced the active remote snapshot. */
        val configurationUpdated: Boolean get() = outcome == Outcome.APPLIED
    }

    data class ConfigRetest(val version: Int, val duringStartup: Boolean)

    private val _configRetest = MutableStateFlow<ConfigRetest?>(null)
    val configRetest: StateFlow<ConfigRetest?> = _configRetest.asStateFlow()

    fun beginConfigRetest(version: Int, duringStartup: Boolean) {
        _configRetest.value = ConfigRetest(version, duringStartup)
    }

    fun finishConfigRetest(version: Int) {
        if (_configRetest.value?.version == version) _configRetest.value = null
    }

    // 独立客户端：系统 DNS + 默认证书校验 + 正常超时；不接 RuleDns / trust-all / 去 SNI。
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val HOST_REGEX = Regex("^(?=.{1,253}$)([a-z0-9](-?[a-z0-9])*\\.)+[a-z]{2,}$")
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** 执行一次远程检查；[manual] 为手动触发（仅用于日志/返回信息，行为一致）。 */
    suspend fun checkAndApply(manual: Boolean = false): Result = withContext(Dispatchers.IO) {
        DevConfig.markBootstrapLoading()
        if (!DevConfig.remoteEnabled() && DevConfig.hasUsableRouteConfig) {
            DevConfig.markBootstrapReady()
            return@withContext Result(Outcome.NOT_CHANGED, "远程更新已关闭", DevConfig.remoteVersion())
        }
        DevConfig.markRemoteCheck()
        try {
            val uri = java.net.URI(REMOTE_CONFIG_URL)
            require(uri.scheme == "https") { "仅允许 HTTPS" }
            require(uri.host in ALLOWED_HOSTS) { "主机不在允许列表: ${uri.host}" }

            val builder = Request.Builder().url(REMOTE_CONFIG_URL).get()
            DevConfig.remoteEtag()?.let { builder.header("If-None-Match", it) }

            client.newCall(builder.build()).execute().use { resp ->
                when (resp.code) {
                    304 -> {
                        if (DevConfig.hasUsableRouteConfig) {
                            DevConfig.markBootstrapReady()
                            Result(Outcome.NOT_CHANGED, "配置未更新（304）", DevConfig.remoteVersion())
                        } else {
                            fail("服务器返回 304，但本机没有有效配置缓存；请提高远程配置版本号后重试")
                        }
                    }
                    200 -> {
                        val body = resp.body?.string()
                            ?: return@use fail("响应体为空")
                        if (body.length > MAX_BODY_BYTES) return@use fail("配置超过 ${MAX_BODY_BYTES} 字节")
                        Validator.validate(body) // 校验失败抛异常
                        val etag = resp.header("ETag")
                        val version = JSONObject(body).optInt("version", 0)
                        if (!RemoteConfigVersionPolicy.shouldApply(version, DevConfig.remoteVersion())) {
                            if (DevConfig.hasUsableRouteConfig) {
                                DevConfig.markBootstrapReady()
                                return@use Result(Outcome.NOT_CHANGED, "远程版本未递增，保留当前有效配置", DevConfig.remoteVersion())
                            }
                            return@use fail("远程配置版本必须高于已应用版本；当前没有可用缓存，请提高版本号后重试")
                        }
                        val applied = DevConfig.applyRemoteConfig(body, etag)
                        if (applied) {
                            // The route list is now authoritative. Rebuild the API base URL and rebind the image stack
                            // before allowing a first-install UI to issue any requests.
                            runCatching { NetworkModule.rebuildApi("远程配置 v$version") }
                                .onFailure { android.util.Log.w("RemoteConfigFetcher", "API stack rebuild failed", it) }
                            runCatching { NetworkModule.rebindImageRoute("远程配置 v$version") }
                                .onFailure { android.util.Log.w("RemoteConfigFetcher", "image route rebind failed", it) }
                            DevConfig.markBootstrapReady()
                            Result(Outcome.APPLIED, "已更新到 v$version", version)
                        } else {
                            if (DevConfig.hasUsableRouteConfig) {
                                DevConfig.markBootstrapReady()
                                Result(Outcome.NOT_CHANGED, "远程版本未递增，保留当前有效配置", DevConfig.remoteVersion())
                            } else {
                                fail("远程配置未能应用；请确认版本号递增后重试")
                            }
                        }
                    }
                    else -> fail("HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            fail(e.message ?: "远程更新失败")
        }
    }

    private fun fail(msg: String): Result {
        DevConfig.recordRemoteError(msg)
        DevConfig.markBootstrapFailed(msg)
        return Result(Outcome.FAILED, msg, DevConfig.remoteVersion())
    }

    /** 远程配置严格校验；任一不满足抛 [IllegalArgumentException]。 */
    object Validator {
        fun validate(body: String) {
            val root = JSONObject(body)
            require(root.optInt("version", -1) >= 0) { "version 非法" }
            val port = root.optInt("port", -1)
            require(port in 1..65535) { "port 非法" }

            val linesArr = root.optJSONArray("lines") ?: throw IllegalArgumentException("缺少 lines")
            val sourcesArr = root.optJSONArray("sources") ?: throw IllegalArgumentException("缺少 sources")
            val lines2Arr = root.optJSONArray("lines2")
            val sources2Arr = root.optJSONArray("sources2")
            require(!root.has("lines2") || lines2Arr != null) { "lines2 必须是数组" }
            require(!root.has("sources2") || sources2Arr != null) { "sources2 必须是数组" }
            val ruleObj = root.optJSONObject("rule") ?: throw IllegalArgumentException("缺少 rule")
            require(linesArr.length() > 0) { "lines 不能为空" }
            require(sourcesArr.length() > 0) { "sources 不能为空" }

            val lineHosts = LinkedHashSet<String>()
            for (i in 0 until linesArr.length()) {
                val u = linesArr.optString(i)
                val uri = runCatching { java.net.URI(u) }.getOrNull()
                require(uri != null && uri.scheme in setOf("http", "https") && uri.host != null) { "lines[$i] 非法: $u" }
                require(HOST_REGEX.matches(uri.host)) { "lines[$i] 主机非法: ${uri.host}" }
                require(lineHosts.add(uri.host)) { "lines 主机重复: ${uri.host}" }
            }
            val sources = LinkedHashSet<String>()
            for (i in 0 until sourcesArr.length()) {
                val h = sourcesArr.optString(i)
                require(HOST_REGEX.matches(h)) { "sources[$i] 主机非法: $h" }
                require(sources.add(h)) { "sources 主机重复: $h" }
            }
            require(lineHosts.none { it in sources }) { "lines 与 sources 存在重叠主机" }

            // JM arrays are optional for old-config compatibility. Duplicate hosts within either
            // JM pool are accepted here and collapsed by first occurrence when applied.
            val lineHosts2 = LinkedHashSet<String>()
            lines2Arr?.let { array ->
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    val uri = runCatching { java.net.URI(value) }.getOrNull()
                    require(uri != null && uri.scheme in setOf("http", "https") && uri.host != null) { "lines2[$i] 非法: $value" }
                    require(HOST_REGEX.matches(uri.host.lowercase(Locale.ROOT))) { "lines2[$i] 主机非法: ${uri.host}" }
                    lineHosts2.add(uri.host.lowercase(Locale.ROOT))
                }
            }
            val sources2 = LinkedHashSet<String>()
            sources2Arr?.let { array ->
                for (i in 0 until array.length()) {
                    val host = array.optString(i)
                    require(HOST_REGEX.matches(host.lowercase(Locale.ROOT))) { "sources2[$i] 主机非法: $host" }
                    sources2.add(host.lowercase(Locale.ROOT))
                }
            }
            require(lineHosts2.none { it in sources2 }) { "lines2 与 sources2 存在重叠主机" }

            // Cross-platform identity is intentionally retained: a Manwa host and a JM host
            // may be the same hostname, while each platform keeps its own runtime pool.
            val classified = (lineHosts + sources + lineHosts2 + sources2)
                .mapTo(LinkedHashSet()) { it.lowercase(Locale.ROOT) }
            require(classified.size <= MAX_HOSTS) { "主机数超过 $MAX_HOSTS" }
            val ruleKeys = LinkedHashSet<String>()
            val keyIt = ruleObj.keys()
            while (keyIt.hasNext()) {
                val key = keyIt.next()
                require(HOST_REGEX.matches(key.lowercase(Locale.ROOT))) { "rule 主机非法: $key" }
                require(ruleKeys.add(key.lowercase(Locale.ROOT))) { "rule 主机重复（忽略大小写）: $key" }
            }
            require(ruleKeys == classified) {
                "rule.key 与 lines∪sources 不一致（差集: ${(ruleKeys + classified).filter { (it in ruleKeys) != (it in classified) }}）"
            }

            var totalIps = 0
            val hostIt = ruleObj.keys()
            while (hostIt.hasNext()) {
                val h = hostIt.next()
                val ips: JSONArray = ruleObj.optJSONArray(h)
                    ?: throw IllegalArgumentException("rule.$h 不是 IP 数组")
                require(ips.length() > 0) { "rule.$h IP 为空" }
                require(ips.length() <= MAX_IPS_PER_HOST) { "rule.$h IP 超过 $MAX_IPS_PER_HOST" }
                val seen = HashSet<String>()
                for (i in 0 until ips.length()) {
                    val ip = ips.optString(i)
                    require(isPublicIpv4(ip)) { "rule.$h 含非法/私有 IP: $ip" }
                    require(seen.add(ip)) { "rule.$h IP 重复: $ip" }
                    totalIps++
                }
            }
            require(totalIps <= MAX_TOTAL_IPS) { "IP 总数超过 $MAX_TOTAL_IPS" }
        }

        private fun isPublicIpv4(ip: String): Boolean {
            val m = IPV4_REGEX.matchEntire(ip) ?: return false
            for (i in 1..4) {
                val o = m.groupValues[i].toInt()
                if (o > 255) return false
            }
            return try {
                val a = java.net.InetAddress.getByName(ip)
                a is java.net.Inet4Address &&
                    !a.isLoopbackAddress &&
                    !a.isSiteLocalAddress &&
                    !a.isLinkLocalAddress &&
                    !a.isAnyLocalAddress &&
                    !a.isMulticastAddress
            } catch (_: Exception) {
                false
            }
        }
    }
}
