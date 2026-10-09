package com.zycomic.app.net

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 开发者配置管理（直连模式：自定义 DNS + SNI 移除）。
 * 配置持久化到 SharedPreferences，格式：{"rule":{"domain":["ip1"]},"sni":["domain1"]}
 */
object DevConfig {

    private const val TAG = "DevConfig"
    private const val PREFS_NAME = "zycomic_dev_config"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_MIGRATED = "proxy_migrated_v1"

    /** 内置基线配置版本：提升该值可在下次启动时把新基线再次合并进已持久化配置。 */
    private const val KEY_BASELINE_VERSION = "baseline_config_version"
    private const val BASELINE_VERSION = 2

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

    /**
     * 一次性基线合并（按版本号 [BASELINE_VERSION] 驱动，未来提升版本号可再次执行）。
     *
     * 把内置 [RouteManager.DEFAULT_CONFIG_JSON] 的 rule/sni 合并进当前（可能已被动态学习持久化的）配置：
     * - 基线中存在的主机：rule 的 IP 列表**整体替换为基线值**（覆盖，不与旧 IP 取并集）；
     * - 基线中没有、但本机动态学到的主机：原样保留；
     * - sni：保证基线全部条目存在（补齐），其余已学条目保留；
     * - port 以基线为准。
     * 成功后写入版本号；异常不写版本号，下次启动重试。
     */
    fun mergeBaselineConfigIfNeeded() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_BASELINE_VERSION, 0) >= BASELINE_VERSION) return
        try {
            val baseline = JSONObject(RouteManager.DEFAULT_CONFIG_JSON)
            val current = JSONObject(getConfigJson())

            // rule：基线主机整体覆盖 IP，基线外主机保留
            val baseRule = baseline.optJSONObject("rule") ?: JSONObject()
            val rule = current.optJSONObject("rule") ?: JSONObject()
            val hostIt = baseRule.keys()
            while (hostIt.hasNext()) {
                val host = hostIt.next()
                rule.put(host, baseRule.getJSONArray(host))
            }
            current.put("rule", rule)

            // sni：基线条目全部补齐，已学的额外条目保留（保序去重）
            val baseSni = baseline.optJSONArray("sni") ?: JSONArray()
            val sniSet = LinkedHashSet<String>()
            val curSni = current.optJSONArray("sni")
            if (curSni != null) {
                for (i in 0 until curSni.length()) sniSet.add(curSni.getString(i))
            }
            for (i in 0 until baseSni.length()) sniSet.add(baseSni.getString(i))
            current.put("sni", JSONArray(sniSet.toList()))

            current.put("port", baseline.optInt("port", 7891))

            applyConfig(current.toString())
            prefs.edit().putInt(KEY_BASELINE_VERSION, BASELINE_VERSION).apply()
            Log.i(TAG, "baseline config merged to v$BASELINE_VERSION (rule=${rule.length()}, sni=${sniSet.size})")
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

    /** 需要 MITM / SNI 绕过的域名集合。 */
    fun getSniDomains(): Set<String> {
        return try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(getConfigJson()).jsonObject
            val sniArr = root["sni"]?.jsonArray ?: JsonArray(emptyList())
            // dns.google 不走 MITM，用普通隧道
            sniArr.map { it.jsonPrimitive.content }.filter { it != "dns.google" }.toSet()
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

    /** 将 JSON 配置解析并设置到 RouteManager。 */
    private fun applyToRouteManager(json: String) {
        try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(json).jsonObject

            val ruleObj = root["rule"]?.jsonObject ?: emptyMap()
            val ruleMap = mutableMapOf<String, List<String>>()
            ruleObj.forEach { (domain, arr) ->
                val ips = arr.jsonArray.map { it.jsonPrimitive.content }
                ruleMap[domain] = ips
            }

            val sniArr = root["sni"]?.jsonArray ?: JsonArray(emptyList())
            // dns.google 不走 MITM，用普通隧道（IP直连+正常SNI即可，8.8.8.8支持）
            val sniSet = sniArr.map { it.jsonPrimitive.content }.filter { it != "dns.google" }.toSet()

            RouteManager.setCustomRule(ruleMap)
            RouteManager.setSniDomains(sniSet)
        } catch (e: Exception) {
            Log.e(TAG, "applyToRouteManager failed", e)
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
