package com.zycomic.app.net

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 全局共享 CookieJar：所有线路域名共享同一份 cookie，不按 host 分开存储。
 *
 * - 持久化到 SharedPreferences，App 重启后恢复登录态（uid / PHPSESSID）。
 * - [saveFromResponse]：按 name 去重覆盖，写入全局缓存 + 持久化。
 * - [loadForRequest]：无论请求哪个 host，都返回全部未过期 cookie。
 * - [rewriteForHost]：切换线路后用新域名重新写入所有 cookie。
 */
class GlobalCookieJar(context: Context) : CookieJar {

    private val prefs = context.getSharedPreferences("zycomic_cookies", Context.MODE_PRIVATE)

    /** name -> Cookie 全局缓存 */
    private val store = linkedMapOf<String, Cookie>()

    init {
        // 启动时从 SharedPreferences 恢复 cookie
        restoreFromPrefs()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { c ->
            store[c.name] = c
        }
        persistToPrefs()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        // 清理过期 cookie
        val removed = store.values.removeAll { it.expiresAt in 1 until now }
        if (removed) persistToPrefs()
        // 返回全部 cookie，不按 host 过滤
        return store.values.toList()
    }

    /** 当前所有 cookie（未过期） */
    @Synchronized
    fun getAll(): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.filter { it.expiresAt == 0L || it.expiresAt > now }
    }

    /**
     * 切换线路后，用新域名重新写入所有 cookie。
     * @param newBaseUrl 新线路 baseUrl（如 https://mseeowpm.pro）
     */
    @Synchronized
    fun rewriteForHost(newBaseUrl: String) {
        val url = newBaseUrl.toHttpUrl()
        val host = url.host
        val existing = getAll()
        store.clear()
        existing.forEach { c ->
            val rebuilt = Cookie.Builder()
                .name(c.name)
                .value(c.value)
                .domain(host)
                .path("/")
                .expiresAt(if (c.expiresAt == 0L) Long.MAX_VALUE else c.expiresAt)
                .build()
            store[c.name] = rebuilt
        }
        persistToPrefs()
    }

    /** 手动写入一个 cookie（登录后确保 uid 存在） */
    @Synchronized
    fun add(name: String, value: String, domain: String) {
        val c = Cookie.Builder()
            .name(name)
            .value(value)
            .domain(domain)
            .path("/")
            .expiresAt(System.currentTimeMillis() + 92_000_000_000L)
            .build()
        store[name] = c
        persistToPrefs()
    }

    /** 清空所有 cookie（登出） */
    @Synchronized
    fun clear() {
        store.clear()
        prefs.edit().clear().apply()
    }

    // ==================== 持久化 ====================

    /** 把当前所有未过期 cookie 写入 SharedPreferences。格式：name|value|expiresAt */
    private fun persistToPrefs() {
        val now = System.currentTimeMillis()
        val valid = store.values.filter { it.expiresAt == 0L || it.expiresAt > now }
        val encoded = valid.joinToString(";;") { "${it.name}|${it.value}|${it.expiresAt}|${it.domain}" }
        prefs.edit().putString("cookies", encoded).apply()
    }

    /** 从 SharedPreferences 恢复 cookie。domain 用当前线路 host。 */
    private fun restoreFromPrefs() {
        val encoded = prefs.getString("cookies", null) ?: return
        val now = System.currentTimeMillis()
        val host = RouteManager.lineHost
        encoded.split(";;").forEach { entry ->
            val parts = entry.split("|")
            if (parts.size >= 3) {
                val name = parts[0]
                val value = parts[1]
                val expiresAt = parts[2].toLongOrNull() ?: Long.MAX_VALUE
                if (expiresAt == 0L || expiresAt > now) {
                    val c = Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(host)
                        .path("/")
                        .expiresAt(expiresAt)
                        .build()
                    store[name] = c
                }
            }
        }
    }
}
