package com.zycomic.app.net

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 开发者配置管理（方案A：本地代理模式）。
 *
 * - 配置持久化到 SharedPreferences。
 * - 配置格式：{"port":7891,"rule":{"domain":["ip1"]},"sni":["domain1"]}
 * - 同时持有全局 [LocalProxyServer] 引用，方便设置页保存配置后重启代理。
 */
object DevConfig {

    private const val TAG = "DevConfig"
    private const val PREFS_NAME = "zycomic_dev_config"
    private const val KEY_CONFIG_JSON = "config_json"

    private var context: Context? = null
    private var cachedJson: String? = null

    /** 全局代理服务器引用，用于重启。 */
    @Volatile
    var proxyServer: LocalProxyServer? = null
        private set

    /** 初始化：从 SharedPreferences 读取配置（若无则用默认配置），并应用到 RouteManager。 */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        cachedJson = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG_JSON, null)
            ?: RouteManager.DEFAULT_CONFIG_JSON
        applyToRouteManager(cachedJson!!)
    }

    fun getConfigJson(): String = cachedJson ?: RouteManager.DEFAULT_CONFIG_JSON

    /** 代理端口，默认 7891。 */
    fun getPort(): Int {
        return try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(getConfigJson()).jsonObject
            root["port"]?.jsonPrimitive?.intOrNull ?: 7891
        } catch (e: Exception) {
            Log.e(TAG, "getPort failed, using default 7891", e)
            7891
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
            sniArr.map { it.jsonPrimitive.content }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "getSniDomains failed", e)
            emptySet()
        }
    }

    /** 保存配置到 SharedPreferences 并更新缓存。 */
    fun saveConfig(json: String) {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_CONFIG_JSON, json)
            ?.apply()
        cachedJson = json
    }

    /**
     * 保存配置 + 更新 RouteManager。
     * 注意：代理重启由调用方处理（stop 旧代理 + start 新代理）。
     */
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
            val sniSet = sniArr.map { it.jsonPrimitive.content }.toSet()

            RouteManager.setCustomRule(ruleMap)
            RouteManager.setSniDomains(sniSet)
        } catch (e: Exception) {
            Log.e(TAG, "applyToRouteManager failed", e)
        }
    }

    /**
     * 重启代理服务器（新配置生效）。
     * 由设置页保存配置后调用。
     */
    fun restartProxy() {
        proxyServer?.stop()
        val newProxy = LocalProxyServer(
            port = getPort(),
            rule = getRule(),
            sniDomains = getSniDomains(),
        )
        newProxy.start()
        proxyServer = newProxy
        Log.i(TAG, "Proxy restarted on port ${getPort()}")
    }

    /** 启动代理服务器（App 启动时调用）。 */
    fun startProxy() {
        if (proxyServer != null) return
        proxyServer = LocalProxyServer(
            port = getPort(),
            rule = getRule(),
            sniDomains = getSniDomains(),
        ).also { it.start() }
        Log.i(TAG, "Proxy started on port ${getPort()}")
    }
}
