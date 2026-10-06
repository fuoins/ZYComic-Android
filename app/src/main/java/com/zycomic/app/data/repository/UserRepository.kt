package com.zycomic.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zycomic.app.data.dto.LoginRequest
import com.zycomic.app.data.dto.PointLog
import com.zycomic.app.data.dto.User
import com.zycomic.app.data.dto.WelfareData
import com.zycomic.app.data.dto.WelfareRequest
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.RouteManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 用户仓库：全局登录状态源。
 *
 * - [userFlow] 是全局唯一的登录状态流，所有 ViewModel 收集它。
 * - 登录 / 登出 / 启动校验时都会更新 [userFlow]。
 * - cookie 由 [NetworkModule.cookieJar] 全局维护。
 * - 用户信息本地持久化到 SharedPreferences，启动时先读本地显示登录，后台再刷新。
 */
object UserRepository {

    private val api get() = NetworkModule.api

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private const val KEY_USER_INFO = "user_info_json"

    /** 必须在 App.onCreate 中调用一次，初始化 SharedPreferences。 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences("zycomic_user", Context.MODE_PRIVATE)
    }

    private val _userFlow = MutableStateFlow<User?>(null)

    /** 当前登录用户，未登录为 null。所有 ViewModel 收集此流。 */
    val userFlow: StateFlow<User?> = _userFlow.asStateFlow()

    /** 当前是否已登录（同步读取） */
    val isLoggedIn: Boolean get() = _userFlow.value != null

    // ==================== 本地持久化 ====================

    /** 保存用户信息到本地。 */
    private fun saveUserInfoLocal(user: User) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_USER_INFO, json.encodeToString(User.serializer(), user)).apply()
    }

    /** 从本地读取用户信息，无缓存或解析失败返回 null。 */
    private fun loadUserInfoLocal(): User? {
        if (!::prefs.isInitialized) return null
        val jsonStr = prefs.getString(KEY_USER_INFO, null) ?: return null
        return try {
            json.decodeFromString(User.serializer(), jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    /** 清除本地用户信息。 */
    private fun clearUserInfoLocal() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY_USER_INFO).apply()
    }

    /**
     * 启动时从本地恢复登录态：直接设置 userFlow，UI 立即显示登录状态。
     * 不会发起网络请求，没网也能显示登录。
     * @return true=本地有登录缓存，false=无缓存
     */
    fun restoreLoginFromLocal(): Boolean {
        val local = loadUserInfoLocal()
        if (local != null && local.uid.isNotEmpty() && local.uid != "0") {
            _userFlow.value = local
            return true
        }
        return false
    }

    // ==================== 登录 / 注册 ====================

    /**
     * 登录：先调 /account/login，成功后再调 /users/info 获取完整用户信息，更新 userFlow 并持久化。
     */
    suspend fun login(username: String, password: String, captcha: String = ""): User {
        val loginResp = api.login(LoginRequest(username, password, captcha))
        if (loginResp.code != 1) throw IOException(loginResp.msg.ifEmpty { "登录失败" })

        // 登录后手动写入 uid cookie（服务端可能不自动设置，后续请求必须携带）
        val loginUid = loginResp.data?.uid?.takeIf { it.isNotEmpty() && it != "0" }
        if (loginUid != null) {
            NetworkModule.cookieJar.add("uid", loginUid, RouteManager.lineHost)
        }

        // 登录成功后拉取完整用户信息
        val info = getUserInfo()
        // 再次确保 uid cookie 存在（用 getUserInfo 返回的 uid）
        if (info.uid.isNotEmpty() && info.uid != "0") {
            NetworkModule.cookieJar.add("uid", info.uid, RouteManager.lineHost)
        }
        _userFlow.value = info
        saveUserInfoLocal(info)
        return info
    }

    /**
     * 注册：调用 /account/register。成功后自动登录态由 cookie 维持。
     */
    suspend fun register(username: String, password: String, email: String, authCode: String): User {
        val resp = api.register(com.zycomic.app.data.dto.RegisterRequest(username, password, email, authCode))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "注册失败" })

        val info = getUserInfo()
        _userFlow.value = info
        saveUserInfoLocal(info)
        return info
    }

    suspend fun sendAuth(email: String, type: String, username: String = "") {
        val resp = api.sendAuth(com.zycomic.app.data.dto.SendAuthRequest(email, type, username))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "发送验证码失败" })
    }

    suspend fun emailLogin(email: String, authToken: String): User {
        val resp = api.forgetPwd(com.zycomic.app.data.dto.ForgetPwdRequest(email, authToken))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "登录失败" })
        resp.data?.uid?.takeIf { it.isNotEmpty() && it != "0" }?.let {
            NetworkModule.cookieJar.add("uid", it, RouteManager.lineHost)
        }
        val info = getUserInfo()
        _userFlow.value = info
        saveUserInfoLocal(info)
        return info
    }

    suspend fun changePassword(newPassword: String) {
        val resp = api.editUser(kotlinx.serialization.json.buildJsonObject {
            put("password", kotlinx.serialization.json.JsonPrimitive(newPassword))
        })
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "修改密码失败" })
        NetworkModule.cookieJar.clear()
        clearUserInfoLocal()
        _userFlow.value = null
    }

    suspend fun getCaptcha(): ByteArray = api.getCaptcha().bytes()

    /**
     * 登出：先调用服务端登出 API，再清除全局 cookie 和本地用户信息，userFlow 置 null。
     * 即使登出 API 调用失败也会清 cookie（本地登出）。
     */
    suspend fun logout() {
        try {
            api.logout()
        } catch (_: Exception) {
            // 登出 API 失败不阻塞本地登出
        }
        NetworkModule.cookieJar.clear()
        clearUserInfoLocal()
        _userFlow.value = null
    }

    /**
     * 启动时后台校验登录态：调 /users/info 刷新用户信息。
     * - 成功 → 更新 userFlow 并持久化
     * - 服务端明确返回 uid 无效（空或 "0"）→ 调用 logout() 登出并清除 cookie 和本地缓存
     * - 网络异常 → 不清除 cookie 和本地缓存，保留登录态下次启动再试
     *   （避免首次连接超时/线路抖动导致有效 cookie 被误清除）
     * 调用前应先调用 [restoreLoginFromLocal] 让 UI 立即显示登录状态。
     */
    suspend fun verifyLogin() {
        try {
            val info = getUserInfo()
            if (info.uid.isEmpty() || info.uid == "0") {
                // 服务端明确返回 uid 无效，视为未登录
                logout()
                return
            }
            _userFlow.value = info
            saveUserInfoLocal(info)
        } catch (_: Exception) {
            // 网络异常：保留 cookie 和本地登录态，下次启动再试
        }
    }

    // ==================== 用户信息 ====================

    /** 拉取当前用户信息（/users/info），不更新 userFlow。 */
    suspend fun getUserInfo(): User {
        val resp = api.userInfo()
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取用户信息失败" })
        return resp.data ?: throw IOException("用户信息为空")
    }

    /** 刷新当前用户信息到 userFlow 并持久化。 */
    suspend fun refreshUserInfo() {
        val info = getUserInfo()
        _userFlow.value = info
        saveUserInfoLocal(info)
    }

    // ==================== 签到 / 福利 / 积分 ====================

    /** 获取签到福利信息（同时触发签到）。date 为目标月份第一天 yyyy-MM-01，默认当前月。 */
    suspend fun getWelfare(date: String? = null): WelfareData? {
        val body = date ?: run {
            val calendar = java.util.Calendar.getInstance()
            String.format("%04d-%02d-01", calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH) + 1)
        }
        val resp = api.welfare(WelfareRequest(date = body))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取福利失败" })
        return resp.data
    }

    /** 签到：调用福利接口即完成签到动作。 */
    suspend fun signIn(): WelfareData? {
        return getWelfare()
    }

    private suspend fun getAdLinks(): List<String> {
        val html = api.getWawaWise().string()
        return Regex("https?://s\\.chmsrv\\.com/click\\.php\\?d=[^\"'\\s<>]+").findAll(html).map { it.value }.toList()
    }

    private suspend fun clickAd(link: String) {
        val encoded = java.net.URLEncoder.encode(link, "UTF-8")
        api.adClick(group = "ad_wawaweise", link = encoded, task = 1)
    }

    suspend fun claimAdBonus(): Int {
        val links = getAdLinks()
        if (links.isEmpty()) throw IOException("未获取到广告链接")
        val pointBefore = getUserInfo().point
        repeat(5) { i ->
            clickAd(links[i % links.size])
            kotlinx.coroutines.delay((1000..2000).random().toLong())
        }
        getWelfare()
        val pointAfter = getUserInfo().point
        val diff = pointAfter - pointBefore
        if (diff <= 0) throw IOException("领取失败，积分未增加")
        return diff
    }

    /** 积分明细分页。 */
    suspend fun getPointLogs(page: Int): List<PointLog> {
        val resp = api.pointLogs(page)
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取积分明细失败" })
        return resp.data?.list ?: emptyList()
    }
}
