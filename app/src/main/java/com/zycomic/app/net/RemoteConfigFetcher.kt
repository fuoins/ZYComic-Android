package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程 ruledns 更新器。
 *
 * - 拉取 [REMOTE_CONFIG_URL]，GitHub raw/静态站风格：支持 ETag/If-None-Match（304 省流量），
 *   服务器不支持时退化为体内 version 比较；
 * - 使用**独立的、正常校验证书的 HTTPS 客户端**（系统 DNS、默认 TLS），
 *   绝不复用 trust-all / IP 直连客户端（ruledns 本身会改写 DNS/SNI，必须保证传输可信）；
 * - 严格校验通过且 version 更新才原子写入 [DevConfig]；任何异常/非 200/304/校验失败/版本未变，
 *   都保留当前可用配置，静默回退内置/上次可用。
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

    data class Result(val outcome: Outcome, val message: String, val version: Int)

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
        if (!DevConfig.remoteEnabled()) return@withContext Result(Outcome.NOT_CHANGED, "远程更新已关闭", DevConfig.remoteVersion())
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
                        Result(Outcome.NOT_CHANGED, "配置未更新（304）", DevConfig.remoteVersion())
                    }
                    200 -> {
                        val body = resp.body?.string()
                            ?: return@use fail("响应体为空")
                        if (body.length > MAX_BODY_BYTES) return@use fail("配置超过 ${MAX_BODY_BYTES} 字节")
                        Validator.validate(body) // 校验失败抛异常
                        val etag = resp.header("ETag")
                        val applied = DevConfig.applyRemoteConfig(body, etag)
                        if (applied) {
                            val v = JSONObject(body).optInt("version", 0)
                            Result(Outcome.APPLIED, "已更新到 v$v", v)
                        } else {
                            Result(Outcome.NOT_CHANGED, "版本未更新", DevConfig.remoteVersion())
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
            val ruleObj = root.optJSONObject("rule") ?: throw IllegalArgumentException("缺少 rule")

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

            val classified = lineHosts + sources
            require(classified.size <= MAX_HOSTS) { "主机数超过 $MAX_HOSTS" }
            val ruleKeys = LinkedHashSet<String>()
            val keyIt = ruleObj.keys()
            while (keyIt.hasNext()) ruleKeys.add(keyIt.next())
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
