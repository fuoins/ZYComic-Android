package com.zycomic.app.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manwa/JM runtime configuration comes exclusively from a validated remote snapshot.
 * Picacg owns a separate immutable client baseline; the remote version only identifies its SmartDNS generation.
 * A previously successful remote snapshot is retained for offline startup.
 */
sealed interface RouteConfigState {
    data object Loading : RouteConfigState
    data object Ready : RouteConfigState
    data class Failed(val message: String) : RouteConfigState
}

object DevConfig {

    private const val TAG = "DevConfig"
    private const val PREFS_NAME = "zycomic_dev_config"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_MIGRATED = "proxy_migrated_v1"
    private const val KEY_VPN_ACCEL_ENABLED = "vpn_accel_enabled"
    private const val KEY_VPN_ONLY_AUTO_RETEST_ENABLED = "vpn_only_auto_retest_enabled"

    /** 远程配置持久化 */
    private const val PREFS_REMOTE = "zycomic_remote_config"
    private const val EMPTY_CONFIG_JSON = """{"version":0,"lines":[],"sources":[],"rule":{}}"""

    private var context: Context? = null
    private var cachedJson: String? = null

    private val _routeConfigState = MutableStateFlow<RouteConfigState>(RouteConfigState.Loading)
    val routeConfigState: StateFlow<RouteConfigState> = _routeConfigState.asStateFlow()

    private val _remoteVersionFlow = MutableStateFlow(0)
    val remoteVersionFlow: StateFlow<Int> = _remoteVersionFlow.asStateFlow()

    /** 全局 ApplicationContext，供 RouteManager 等使用。 */
    val appContext: Context? get() = context

    val hasUsableRouteConfig: Boolean get() = RouteManager.hasRemoteConfig

    /** Restore a validated remote snapshot for Manwa/JM and the independent Picacg client generation. */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        JmRouteManager.loadPreferences()
        val configPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remotePrefs = ctx.getSharedPreferences(PREFS_REMOTE, Context.MODE_PRIVATE)
        val version = remotePrefs.getInt("remote_version", 0)
        _remoteVersionFlow.value = version
        val remoteHosts = runCatching {
            val array = JSONArray(remotePrefs.getString("remote_hosts", "[]"))
            (0 until array.length()).map { array.getString(it).lowercase() }.toSet()
        }.getOrDefault(emptySet())
        val candidates = listOfNotNull(
            remotePrefs.getString("last_good_config", null),
            configPrefs.getString(KEY_CONFIG_JSON, null),
        ).distinct()
        val cached = candidates.firstOrNull { isValidRemoteSnapshot(it, version, remoteHosts) }

        if (cached != null) {
            cachedJson = cached
            runCatching { applyToRouteManager(cached) }
                .onSuccess { _routeConfigState.value = RouteConfigState.Ready }
                .onFailure {
                    Log.e(TAG, "Unable to restore validated remote configuration", it)
                    cachedJson = EMPTY_CONFIG_JSON
                    RouteManager.clearConfiguration()
                    JmRouteManager.applyRemoteConfig(emptyList(), emptyList(), emptyMap(), null)
                    PicacgNetworkGeneration.restore(null)
                    remotePrefs.edit()
                        .remove("remote_etag")
                        .remove("remote_updated_at")
                        .remove("remote_hosts")
                        .remove("last_good_config")
                        .remove("last_success_ts")
                        .commit()
                    _routeConfigState.value = RouteConfigState.Loading
                }
        } else {
            cachedJson = EMPTY_CONFIG_JSON
            RouteManager.clearConfiguration()
            JmRouteManager.applyRemoteConfig(emptyList(), emptyList(), emptyMap(), null)
            PicacgNetworkGeneration.restore(null)
            // Discard only the unusable snapshot/ETag. Keep the last applied version marker monotonic,
            // while forcing a full response instead of allowing an old ETag to produce a cacheless 304.
            remotePrefs.edit()
                .remove("remote_etag")
                .remove("remote_updated_at")
                .remove("remote_hosts")
                .remove("last_good_config")
                .remove("last_success_ts")
                .remove("last_error")
                .commit()
            _routeConfigState.value = RouteConfigState.Loading
        }
    }

    fun getConfigJson(): String = cachedJson ?: EMPTY_CONFIG_JSON

    fun markBootstrapLoading() {
        if (!hasUsableRouteConfig) _routeConfigState.value = RouteConfigState.Loading
    }

    fun markBootstrapReady() {
        if (hasUsableRouteConfig) _routeConfigState.value = RouteConfigState.Ready
    }

    fun markBootstrapFailed(message: String) {
        if (!hasUsableRouteConfig) _routeConfigState.value = RouteConfigState.Failed(message)
    }

    suspend fun awaitUsableRouteConfig() {
        _routeConfigState.first { it is RouteConfigState.Ready }
    }

    private fun isValidRemoteSnapshot(json: String, expectedVersion: Int, expectedHosts: Set<String>): Boolean {
        if (expectedVersion <= 0 || expectedHosts.isEmpty()) return false
        return runCatching {
            RemoteConfigFetcher.Validator.validate(json)
            val root = JSONObject(json)
            if (root.optInt("version", -1) != expectedVersion) {
                false
            } else {
                val actual = LinkedHashSet<String>()
                val lines = root.getJSONArray("lines")
                for (i in 0 until lines.length()) actual += bareHost(lines.getString(i)).lowercase()
                val sources = root.getJSONArray("sources")
                for (i in 0 until sources.length()) actual += sources.getString(i).lowercase()
                root.optJSONArray("lines2")?.let { jmLines ->
                    for (i in 0 until jmLines.length()) actual += bareHost(jmLines.getString(i)).lowercase()
                }
                root.optJSONArray("sources2")?.let { jmSources ->
                    for (i in 0 until jmSources.length()) actual += jmSources.getString(i).lowercase()
                }
                root.optJSONArray("lines3")?.let { picacgLines ->
                    for (i in 0 until picacgLines.length()) actual += bareHost(picacgLines.getString(i)).lowercase()
                }
                root.optJSONArray("sources3")?.let { picacgSources ->
                    for (i in 0 until picacgSources.length()) actual += picacgSources.getString(i).lowercase()
                }
                actual == expectedHosts
            }
        }.getOrDefault(false)
    }

    /** 老用户迁移：清理曾显式开启代理的遗留 SP key。 */
    fun migrateProxyDefaultIfNeeded() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PROXY_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.getBoolean(KEY_PROXY_ENABLED, false)) editor.remove(KEY_PROXY_ENABLED)
        editor.putBoolean(KEY_PROXY_MIGRATED, true).commit()
    }

    /**
     * 「自动跟随系统 VPN」开关，默认开启（持久化 key 沿用 vpn_accel_enabled）。
     * - ON（默认）：检测到系统 VPN 时走 VPN 路径（系统 DNS + 保留 SNI + VPN 出口）；
     *   未检测到 VPN 时走直连策略（远程 RuleDns + 去 SNI）。
     * - OFF：不自动跟随，**强制直连**，即使开着系统 VPN 也保持远程 RuleDns 直连，用于抓包调试。
     */
    fun isFollowSystemVpnEnabled(): Boolean {
        val ctx = context ?: return true
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VPN_ACCEL_ENABLED, true)
    }

    /** 设置「自动跟随系统 VPN」开关。 */
    fun setFollowSystemVpnEnabled(enabled: Boolean) {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_VPN_ACCEL_ENABLED, enabled)
            ?.apply()
    }

    /**
     * VPN 单独变化后是否自动完整测速并应用新排名；默认关闭。
     * 老安装没有该 key 时也读取为 false。此偏好不影响路由策略或网络变化时的连接清理。
     */
    fun isVpnOnlyAutoRetestEnabled(): Boolean {
        val ctx = context ?: return false
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VPN_ONLY_AUTO_RETEST_ENABLED, false)
    }

    fun setVpnOnlyAutoRetestEnabled(enabled: Boolean) {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_VPN_ONLY_AUTO_RETEST_ENABLED, enabled)
            ?.apply()
    }

    private fun bareHost(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').trim('/')

    private fun deduplicateLineArray(input: JSONArray): JSONArray {
        val seen = LinkedHashSet<String>()
        return JSONArray().apply {
            for (index in 0 until input.length()) {
                val value = input.getString(index)
                val host = runCatching { java.net.URI(value).host }.getOrNull().orEmpty().lowercase()
                if (host.isNotBlank() && seen.add(host)) put(value)
            }
        }
    }

    private fun deduplicateHostArray(input: JSONArray): JSONArray {
        val seen = LinkedHashSet<String>()
        return JSONArray().apply {
            for (index in 0 until input.length()) {
                val value = input.getString(index)
                if (seen.add(value.lowercase())) put(value)
            }
        }
    }

    /** 域名→IP 列表映射。 */
    fun getRule(): Map<String, List<String>> {
        return try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(getConfigJson()).jsonObject
            val ruleObj = root["rule"]?.jsonObject ?: return emptyMap()
            val manwaHosts = root["lines"]?.jsonArray.orEmpty().map { bareHost(it.jsonPrimitive.content).lowercase() }.toSet() +
                root["sources"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content.lowercase() }
            buildMap {
                ruleObj.forEach { (domain, arr) ->
                    if (domain.lowercase() in manwaHosts) {
                        val ips = arr.jsonArray.map { it.jsonPrimitive.content }
                        put(domain, ips)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRule failed", e)
            emptyMap()
        }
    }

    /** Manwa TLS host set; JM entries are intentionally isolated in [getJmSniDomains]. */
    fun getSniDomains(): Set<String> {
        return try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(getConfigJson()).jsonObject
            fun strList(key: String): List<String> =
                root[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val lines = strList("lines")
            val sources = strList("sources")
            val base = lines.mapTo(HashSet()) { bareHost(it).lowercase() }
            base.addAll(sources.map { it.lowercase() })
            val ruleKeys = root["rule"]?.jsonObject?.keys?.map { it.lowercase() }
                ?.filter { it in base }.orEmpty()
            (base + ruleKeys).filter { it != "dns.google" }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "getSniDomains failed", e)
            emptySet()
        }
    }

    /** JM TLS host set, kept separate from the Manwa NetworkModule's SNI policy. */
    fun getJmSniDomains(): Set<String> {
        return try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(getConfigJson()).jsonObject
            fun strList(key: String): List<String> =
                root[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val base = strList("lines2").mapTo(HashSet()) { bareHost(it).lowercase() }
            base.addAll(strList("sources2").map { it.lowercase() })
            val ruleKeys = root["rule"]?.jsonObject?.keys?.map { it.lowercase() }
                ?.filter { it in base }.orEmpty()
            (base + ruleKeys).filter { it != "dns.google" }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "getJmSniDomains failed", e)
            emptySet()
        }
    }

    /** Picacg candidates are client-owned and never merged into Manwa or JM route maps. */
    fun getPicacgRouteRules(): Map<String, List<String>> = PicacgRouteManager.routeRules()

    /** 保存配置到 SharedPreferences 并更新缓存。 */
    fun saveConfig(json: String) {
        val ctx = context
        if (ctx != null) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ok = prefs.edit().putString(KEY_CONFIG_JSON, json).commit()
            if (!ok) {
                // commit 失败时用 apply 异步写入兜底
                prefs.edit().putString(KEY_CONFIG_JSON, json).apply()
            }
        }
        cachedJson = json
    }

    /** 保存配置 + 更新 RouteManager。 */
    fun applyConfig(json: String) {
        saveConfig(json)
        applyToRouteManager(json)
    }

    /** Apply an explicit remote configuration; legacy rule-key classification is intentionally unsupported. */
    private fun applyToRouteManager(json: String) {
        RemoteConfigFetcher.Validator.validate(json)
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
        fun strList(key: String): List<String> = root[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val ruleMap = LinkedHashMap<String, List<String>>()
        root["rule"]?.jsonObject?.forEach { (domain, arr) ->
            ruleMap[domain] = arr.jsonArray.map { it.jsonPrimitive.content }
        }
        val lines = strList("lines")
        val sources = strList("sources")
        val lines2 = strList("lines2")
        val sources2 = strList("sources2")
        require(lines.isNotEmpty() && sources.isNotEmpty()) { "Remote routes and sources must not be empty" }
        val manwaHosts = (lines.map(::bareHost) + sources).map { it.lowercase() }.toSet()
        val manwaRules = ruleMap.filterKeys { it.lowercase() in manwaHosts }
        RouteManager.setCustomRule(manwaRules)
        val version = root["version"]?.jsonPrimitive?.content?.toIntOrNull()
        RouteManager.applyBaseLists(lines, sources, version)
        JmRouteManager.applyRemoteConfig(lines2, sources2, ruleMap, version)
        PicacgNetworkGeneration.restore(version)
        val manwaRuleHosts = manwaRules.keys.map { it.lowercase() }.filter { it in manwaHosts }
        RouteManager.setSniDomains((manwaHosts + manwaRuleHosts) - "dns.google")
    }

    // ==================== 远程配置 ====================

    private fun remotePrefs() =
        context!!.getSharedPreferences(PREFS_REMOTE, Context.MODE_PRIVATE)

    fun remoteVersion(): Int = runCatching { remotePrefs().getInt("remote_version", 0) }.getOrDefault(0)
    fun remoteEtag(): String? = runCatching {
        remotePrefs().getString("remote_etag", null)?.takeIf { it.isNotBlank() }
    }.getOrNull()
    fun remoteUpdatedAt(): String? = runCatching { remotePrefs().getString("remote_updated_at", null) }.getOrNull()
    fun remoteLastCheck(): Long = runCatching { remotePrefs().getLong("last_check_ts", 0L) }.getOrDefault(0L)
    fun remoteLastSuccess(): Long = runCatching { remotePrefs().getLong("last_success_ts", 0L) }.getOrDefault(0L)
    fun remoteError(): String? = runCatching { remotePrefs().getString("last_error", null) }.getOrNull()
    fun remoteEnabled(): Boolean = runCatching { remotePrefs().getBoolean("remote_enabled", true) }.getOrDefault(true)

    fun setRemoteEnabled(v: Boolean) = runCatching {
        remotePrefs().edit().putBoolean("remote_enabled", v).apply()
    }

    fun markRemoteCheck() = runCatching {
        remotePrefs().edit().putLong("last_check_ts", System.currentTimeMillis()).apply()
    }

    fun recordRemoteError(msg: String) = runCatching {
        remotePrefs().edit()
            .putString("last_error", msg)
            .putLong("last_check_ts", System.currentTimeMillis())
            .apply()
    }

    /** Apply only a strictly newer remote version and retain that exact successful snapshot offline. */
    fun applyRemoteConfig(body: String, etag: String?): Boolean {
        val ctx = context ?: return false
        return try {
            RemoteConfigFetcher.Validator.validate(body)
            val remote = JSONObject(body)
            val version = remote.getInt("version")
            val rp = ctx.getSharedPreferences(PREFS_REMOTE, Context.MODE_PRIVATE)
            val appliedVersion = rp.getInt("remote_version", 0)
            if (!RemoteConfigVersionPolicy.shouldApply(version, appliedVersion)) return false

            val lines = remote.getJSONArray("lines")
            val sources = remote.getJSONArray("sources")
            val lines2 = remote.optJSONArray("lines2")
            val sources2 = remote.optJSONArray("sources2")
            val lines3 = remote.optJSONArray("lines3")
            val sources3 = remote.optJSONArray("sources3")
            val rules = remote.getJSONObject("rule")
            val hosts = LinkedHashSet<String>()
            for (i in 0 until lines.length()) hosts += bareHost(lines.getString(i)).lowercase()
            for (i in 0 until sources.length()) hosts += sources.getString(i).lowercase()
            lines2?.let { array -> for (i in 0 until array.length()) hosts += bareHost(array.getString(i)).lowercase() }
            sources2?.let { array -> for (i in 0 until array.length()) hosts += array.getString(i).lowercase() }
            lines3?.let { array -> for (i in 0 until array.length()) hosts += bareHost(array.getString(i)).lowercase() }
            sources3?.let { array -> for (i in 0 until array.length()) hosts += array.getString(i).lowercase() }

            val snapshot = JSONObject().apply {
                put("version", version)
                put("updated_at", remote.optString("updated_at", ""))
                put("port", remote.getInt("port"))
                put("lines", lines)
                put("sources", sources)
                lines2?.let { put("lines2", deduplicateLineArray(it)) }
                sources2?.let { put("sources2", deduplicateHostArray(it)) }
                lines3?.let { put("lines3", deduplicateLineArray(it)) }
                sources3?.let { put("sources3", deduplicateHostArray(it)) }
                put("rule", rules)
            }.toString()
            val previous = getConfigJson()
            try {
                applyConfig(snapshot)
            } catch (error: Exception) {
                runCatching {
                    if (previous != EMPTY_CONFIG_JSON) applyConfig(previous) else RouteManager.clearConfiguration()
                }
                throw error
            }

            val editor = rp.edit()
                .putInt("remote_version", version)
                .putString("remote_etag", etag ?: "")
                .putString("remote_updated_at", remote.optString("updated_at", ""))
                .putString("remote_hosts", JSONArray(hosts.toList()).toString())
                .putString("last_good_config", snapshot)
                .putLong("last_success_ts", System.currentTimeMillis())
                .remove("last_error")
            if (!editor.commit()) editor.apply()
            _remoteVersionFlow.value = version
            Log.i(TAG, "authoritative remote config applied v$version (hosts=${hosts.size})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "applyRemoteConfig failed", e)
            false
        }
    }

    /** 清除 Coil 图片加载器的内存缓存 + 磁盘缓存。 */
    fun clearImageCaches() {
        try {
            val ctx = context ?: run {
                Log.w(TAG, "clearImageCaches: context is null")
                return
            }
            val loader = coil3.SingletonImageLoader.get(ctx)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            Log.i(TAG, "Coil image caches cleared (memory + disk)")
        } catch (e: Exception) {
            Log.w(TAG, "clearImageCaches failed: ${e.message}")
        }
    }

    /** 仅清除 Coil 内存缓存（网络切换重建图片栈时用；磁盘内均为成功图，无需删除）。 */
    fun clearImageMemoryCache() {
        try {
            val ctx = context ?: return
            coil3.SingletonImageLoader.get(ctx).memoryCache?.clear()
        } catch (e: Exception) {
            Log.w(TAG, "clearImageMemoryCache failed: ${e.message}")
        }
    }
}
