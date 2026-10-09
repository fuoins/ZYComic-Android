package com.zycomic.app.net

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 开发者配置管理（直连模式：自定义 DNS + SNI 移除）。
 *
 * 新规范（无独立 sni）：{"version","updated_at","port","lines":[完整URL],"sources":[裸域名],"rule":{domain:[ip]}}
 *  - rule：域名 -> IPv4，RuleDns 做 IP 直连（唯一 DNS 数据源）；
 *  - SNI 集合由 host(lines) ∪ sources 派生（并包含运行时学到的额外 rule 主机），不再单独维护；
 *  - 旧格式 {"rule","sni"} 仍可容错读取。
 */
object DevConfig {

    private const val TAG = "DevConfig"
    private const val PREFS_NAME = "zycomic_dev_config"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_MIGRATED = "proxy_migrated_v1"

    /** 内置基线配置版本：提升该值可在下次启动时把新基线再次合并进已持久化配置。 */
    private const val KEY_BASELINE_VERSION = "baseline_config_version"
    private const val BASELINE_VERSION = 3

    /** 远程配置持久化 */
    private const val PREFS_REMOTE = "zycomic_remote_config"

    private var context: Context? = null
    private var cachedJson: String? = null

    /** 全局 ApplicationContext，供 RouteManager 等使用。 */
    val appContext: Context? get() = context

    /** 初始化：从 SharedPreferences 读取配置（若无则用默认配置），并应用到 RouteManager。 */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        cachedJson = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG_JSON, null)
            ?: RouteManager.DEFAULT_CONFIG_JSON
        applyToRouteManager(cachedJson!!)
    }

    fun getConfigJson(): String = cachedJson ?: RouteManager.DEFAULT_CONFIG_JSON

    /** 老用户迁移：清理曾显式开启代理的遗留 SP key。 */
    fun migrateProxyDefaultIfNeeded() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PROXY_MIGRATED, false)) return
        val editor = prefs.edit()
        if (prefs.getBoolean(KEY_PROXY_ENABLED, false)) editor.remove(KEY_PROXY_ENABLED)
        editor.putBoolean(KEY_PROXY_MIGRATED, true).commit()
    }

    private fun bareHost(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').trim('/')

    private fun jsonKeys(o: JSONObject): MutableSet<String> {
        val s = LinkedHashSet<String>()
        val it = o.keys()
        while (it.hasNext()) s.add(it.next())
        return s
    }

    private fun jsonArrayToStringSet(arr: JSONArray?): MutableSet<String> {
        val s = LinkedHashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) s.add(arr.getString(i))
        return s
    }

    /**
     * 一次性基线合并（按版本号 [BASELINE_VERSION]=3 驱动）。
     * 把内置新规范（lines/sources/rule）合并进当前（可能是旧 {rule,sni} 或已被动态学习污染的）配置：
     *  - 输出新规范结构（无 sni）；
     *  - 内置主机 IP 以内置基线为准（覆盖）；当前 rule 中不属于内置的"学到主机"原样保留；
     *  - 学到的图片主机写入章节图源集合（chapter_img_domains），避免升级后丢失；
     * 成功后写入版本号；异常不写版本号，下次启动重试。
     */
    fun mergeBaselineConfigIfNeeded() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_BASELINE_VERSION, 0) >= BASELINE_VERSION) return
        try {
            val baseline = JSONObject(RouteManager.DEFAULT_CONFIG_JSON)
            val current = JSONObject(getConfigJson())
            val baseRule = baseline.optJSONObject("rule") ?: JSONObject()
            val curRule = current.optJSONObject("rule") ?: JSONObject()

            val baseHosts = jsonKeys(baseRule)
            val baseSources = jsonArrayToStringSet(baseline.optJSONArray("sources"))
            val baseLineHosts = jsonArrayToStringSet(baseline.optJSONArray("lines"))
                .mapTo(HashSet()) { bareHost(it) }

            // 运行时学到的主机 = 当前 rule 中不属于内置基线的主机
            val learned = jsonKeys(curRule).filter { it !in baseHosts }

            val outRule = JSONObject()
            learned.forEach { outRule.put(it, curRule.getJSONArray(it)) }
            baseHosts.forEach { outRule.put(it, baseRule.getJSONArray(it)) }

            val out = JSONObject()
            out.put("version", baseline.optInt("version", 1))
            out.put("updated_at", baseline.optString("updated_at", ""))
            out.put("port", baseline.optInt("port", 7891))
            out.put("lines", baseline.getJSONArray("lines"))
            out.put("sources", baseline.getJSONArray("sources"))
            out.put("rule", outRule)

            applyConfig(out.toString())

            // 学到的图片主机（非内置图源、非线路）归入章节运行时图源集合
            val routePrefs = ctx.getSharedPreferences("zycomic_route", Context.MODE_PRIVATE)
            val chap = jsonArrayToStringSet(routePrefs.getString("chapter_img_domains", null)?.let(::JSONArray))
            learned.forEach { h -> if (h !in baseSources && h !in baseLineHosts) chap.add(h) }
            routePrefs.edit().putString("chapter_img_domains", JSONArray(chap.toList()).toString()).apply()

            prefs.edit().putInt(KEY_BASELINE_VERSION, BASELINE_VERSION).apply()
            Log.i(TAG, "baseline migrated to v$BASELINE_VERSION (rule=${outRule.length()}, chapter=${chap.size})")
        } catch (e: Exception) {
            Log.e(TAG, "mergeBaselineConfigIfNeeded failed", e)
        }
    }

    /** 域名→IP 列表映射。 */
    fun getRule(): Map<String, List<String>> {
        return try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(getConfigJson()).jsonObject
            val ruleObj = root["rule"]?.jsonObject ?: return emptyMap()
            buildMap {
                ruleObj.forEach { (domain, arr) ->
                    val ips = arr.jsonArray.map { it.jsonPrimitive.content }
                    put(domain, ips)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRule failed", e)
            emptyMap()
        }
    }

    /**
     * 需要去 SNI 的域名集合（派生）：
     * 新规范 = host(lines) ∪ sources，并并入 rule 中运行时学到的额外主机；
     * 旧格式优先用其 sni，缺省回退 rule.keys；统一排除 dns.google。
     */
    fun getSniDomains(): Set<String> {
        return try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(getConfigJson()).jsonObject
            fun strList(key: String): List<String> =
                root[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val lines = strList("lines")
            val sources = strList("sources")
            val ruleKeys = root["rule"]?.jsonObject?.keys ?: emptySet()
            val base: Set<String> = if (lines.isNotEmpty() || sources.isNotEmpty()) {
                lines.mapTo(HashSet()) { bareHost(it) }.apply { addAll(sources) }
            } else {
                root["sni"]?.jsonArray?.mapTo(HashSet()) { it.jsonPrimitive.content } ?: ruleKeys.toMutableSet()
            }
            (base + ruleKeys).filter { it != "dns.google" }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "getSniDomains failed", e)
            emptySet()
        }
    }

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

    /** 将 JSON 配置解析并设置到 RouteManager（rule 直连；lines/sources 显式分类；SNI 派生）。 */
    private fun applyToRouteManager(json: String) {
        try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(json).jsonObject
            fun strList(key: String): List<String> =
                root[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            val ruleMap = LinkedHashMap<String, List<String>>()
            root["rule"]?.jsonObject?.forEach { (domain, arr) ->
                ruleMap[domain] = arr.jsonArray.map { it.jsonPrimitive.content }
            }
            val lines = strList("lines")
            val sources = strList("sources")

            RouteManager.setCustomRule(ruleMap)
            if (lines.isNotEmpty() && sources.isNotEmpty()) {
                RouteManager.applyBaseLists(lines, sources)
            } else {
                // 旧格式（无 lines/sources）兜底：后缀猜测分类
                RouteManager.applyLegacyClassification(ruleMap)
            }
            // SNI 派生：显式 lines+sources，并包含运行时学到的额外 rule 主机
            val canonical = lines.mapTo(HashSet()) { bareHost(it) }.apply { addAll(sources) }
            val sniSet = (canonical + ruleMap.keys) - "dns.google"
            RouteManager.setSniDomains(sniSet)
        } catch (e: Exception) {
            Log.e(TAG, "applyToRouteManager failed", e)
        }
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

    /**
     * 应用一份**已通过校验**的远程配置（B' 语义），成功返回 true。
     *  - 远程所含主机 IP 整体覆盖（含内置主机）；
     *  - 内置主机永不删（远程未含的内置主机保留内置 IP）；
     *  - 运行时学到（既非内置也非上份远程）的主机原样保留；
     *  - 上份远程托管、本份已删、且非内置/学到的主机被移除；
     *  - 版本不更新返回 false；异常回滚上一份可用配置并返回 false。
     */
    fun applyRemoteConfig(body: String, etag: String?): Boolean {
        val ctx = context ?: return false
        try {
            val remote = JSONObject(body)
            val rVersion = remote.optInt("version", 0)
            val rp = ctx.getSharedPreferences(PREFS_REMOTE, Context.MODE_PRIVATE)
            if (rVersion <= rp.getInt("remote_version", 0)) return false

            val rLines = remote.getJSONArray("lines")
            val rSources = remote.getJSONArray("sources")
            val rRule = remote.getJSONObject("rule")

            val baseline = JSONObject(RouteManager.DEFAULT_CONFIG_JSON)
            val bRule = baseline.optJSONObject("rule") ?: JSONObject()
            val current = JSONObject(getConfigJson())
            val cRule = current.optJSONObject("rule") ?: JSONObject()

            val baseHosts = jsonKeys(bRule)
            val prevRemote = jsonArrayToStringSet(rp.getString("remote_hosts", null)?.let(::JSONArray))
            val newRemote = LinkedHashSet<String>()
            for (i in 0 until rLines.length()) newRemote.add(bareHost(rLines.getString(i)))
            for (i in 0 until rSources.length()) newRemote.add(rSources.getString(i))

            val learned = jsonKeys(cRule).filter { it !in baseHosts && it !in prevRemote }

            val outRule = JSONObject()
            learned.forEach { outRule.put(it, cRule.getJSONArray(it)) }
            baseHosts.forEach { h -> if (h !in newRemote) outRule.put(h, bRule.getJSONArray(h)) }
            newRemote.forEach { h ->
                val ips = rRule.optJSONArray(h) ?: bRule.optJSONArray(h)
                if (ips != null) outRule.put(h, ips)
            }

            val out = JSONObject()
            out.put("version", rVersion)
            out.put("updated_at", remote.optString("updated_at", ""))
            out.put("port", remote.optInt("port", baseline.optInt("port", 7891)))
            out.put("lines", rLines)
            out.put("sources", rSources)
            out.put("rule", outRule)

            val previous = getConfigJson()
            rp.edit().putString("last_good_config", previous).apply()
            try {
                applyConfig(out.toString())
            } catch (ae: Exception) {
                runCatching { applyConfig(previous) }
                throw ae
            }

            rp.edit()
                .putInt("remote_version", rVersion)
                .putString("remote_etag", etag ?: "")
                .putString("remote_updated_at", remote.optString("updated_at", ""))
                .putString("remote_hosts", JSONArray(newRemote.toList()).toString())
                .putLong("last_success_ts", System.currentTimeMillis())
                .remove("last_error")
                .apply()
            Log.i(TAG, "remote config applied v$rVersion (hosts=${newRemote.size})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "applyRemoteConfig failed", e)
            return false
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
}
