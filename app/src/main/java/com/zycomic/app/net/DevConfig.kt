package com.zycomic.app.net

import android.content.Context
import android.util.Log
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
