package com.zycomic.app.ui.login

import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmNetworkModule
import com.zycomic.app.net.JmRouteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Login transport uses only the isolated JM client, cookie jar, JWT store, and configured JM route. */
internal object JmAccountLogin {
    suspend fun login(username: String, password: String) {
        require(username.isNotBlank()) { "请输入禁漫账号" }
        require(password.isNotBlank()) { "请输入密码" }

        JmNetworkModule.ensureAgeCookie()
        val baseUrl = JmRouteManager.baseUrl.trimEnd('/')
        if (baseUrl.isBlank()) throw IOException("JM线路尚未就绪")
        val request = Request.Builder()
            .url("$baseUrl/login")
            .post(
                FormBody.Builder()
                    .add("username", username.trim())
                    .add("password", password)
                    .build(),
            )
            .build()

        withContext(Dispatchers.IO) {
            JmNetworkModule.client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("禁漫登录失败（HTTP ${response.code}）")
                val root = runCatching { JSONObject(payload) }
                    .getOrElse { throw IOException("禁漫登录响应无法解析") }
                val member = root.optJSONObject("data") ?: root
                val token = member.optString("jwttoken").takeIf(String::isNotBlank)
                if (token == null) {
                    val message = root.optString("errorMsg").ifBlank {
                        root.optString("msg").ifBlank { "账号或密码错误，或登录响应缺少认证信息" }
                    }
                    throw IOException(message)
                }
                val displayName = member.optString("username").ifBlank { username.trim() }
                val uid = member.optString("uid").takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                JmAuthStore.save(token, displayName, uid)
            }
        }
    }
}
