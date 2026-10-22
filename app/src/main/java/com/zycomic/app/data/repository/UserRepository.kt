package com.zycomic.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zycomic.app.data.dto.LoginRequest
import com.zycomic.app.data.dto.PointLog
import com.zycomic.app.data.dto.User
import com.zycomic.app.data.dto.WelfareData
import com.zycomic.app.data.dto.WelfareRequest
import com.zycomic.app.net.ApiService
import com.zycomic.app.net.NetworkModule
import com.zycomic.app.net.RouteManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * 用户仓库：全局登录状态源。
 *
 * - [userFlow] 是缓存/当前用户资料流；本地缓存本身不证明服务端会话有效。
 * - [sessionStatusFlow] 独立表示 Manwa 会话是否已验证。
 * - cookie 由 [NetworkModule.cookieJar] 全局维护。
 * - 用户信息本地持久化到 SharedPreferences，启动时先读本地显示登录，后台再刷新。
 */
object UserRepository {

    @Volatile private var apiOverrideForTests: ApiService? = null

    private val api get() = apiOverrideForTests ?: NetworkModule.api

    /** Allows offline repository tests to exercise this object against a fake API. */
    internal fun setApiForTesting(api: ApiService?) {
        apiOverrideForTests = api
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var appCtx: Context
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val sessionVerification = ManwaSessionVerificationPolicy()

    // KMK --> Fence browse reads without changing authentication confidence.
    internal fun captureBrowseSession(): ManwaSessionVerificationPolicy.Ticket = sessionVerification.beginRefresh {}

    internal fun isCurrentBrowseSession(ticket: ManwaSessionVerificationPolicy.Ticket): Boolean =
        sessionVerification.isCurrent(ticket)
    // KMK <--

    private const val KEY_USER_INFO = "user_info_json"

    /** 必须在 App.onCreate 中调用一次，初始化 SharedPreferences。 */
    fun init(context: Context) {
        appCtx = context.applicationContext
        prefs = context.getSharedPreferences("zycomic_user", Context.MODE_PRIVATE)
    }

    private val _userFlow = MutableStateFlow<User?>(null)

    /** 当前登录用户，未登录为 null。所有 ViewModel 收集此流。 */
    val userFlow: StateFlow<User?> = _userFlow.asStateFlow()

    private val _sessionStatusFlow = MutableStateFlow(ManwaSessionStatus.UNKNOWN)

    /** Authentication confidence is independent of the possibly cached [userFlow] profile. */
    val sessionStatusFlow: StateFlow<ManwaSessionStatus> = _sessionStatusFlow.asStateFlow()

    /** Whether a cached/current profile is available, regardless of remote verification state. */
    val isLoggedIn: Boolean get() = _userFlow.value != null

    /** Whether the current session was explicitly verified by the server. */
    val isSessionVerified: Boolean get() = _sessionStatusFlow.value == ManwaSessionStatus.VERIFIED

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
        if (local != null && ManwaSessionVerificationPolicy.isValidIdentity(local.uid)) {
            sessionVerification.restore(local.uid) {
                _userFlow.value = local
                _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
            }
            return true
        }
        return false
    }

    // ==================== 登录 / 注册 ====================

    /**
     * 登录：先调 /account/login，成功后再调 /users/info 获取完整用户信息，更新 userFlow 并持久化。
     */
    suspend fun login(username: String, password: String, captcha: String = ""): User {
        val loginGeneration = sessionVerification.beginLogin {
            _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
        }
        val loginResp = try {
            api.login(LoginRequest(username, password, captcha))
        } catch (e: CancellationException) {
            sessionVerification.cancelLogin(loginGeneration) { _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN }
            throw e
        } catch (e: Exception) {
            sessionVerification.cancelLogin(loginGeneration) { _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN }
            throw e
        }
        if (loginResp.code != 1) {
            sessionVerification.cancelLogin(loginGeneration) { _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN }
            throw IOException(loginResp.msg.ifEmpty { "登录失败" })
        }

        // 登录后手动写入 uid cookie（服务端可能不自动设置，后续请求必须携带）
        val loginUid = loginResp.data?.uid?.takeIf { it.isNotEmpty() && it != "0" }
        val refreshGeneration = sessionVerification.establishLogin(loginGeneration, loginUid) {
            if (loginUid != null) NetworkModule.cookieJar.add("uid", loginUid, RouteManager.lineHost)
            _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
        } ?: throw CancellationException("Manwa login was superseded by a newer session")

        // 登录成功后拉取完整用户信息
        val info = getUserInfo()
        if (!ManwaSessionVerificationPolicy.isValidIdentity(info.uid) ||
            (loginUid != null && loginUid != info.uid)
        ) {
            throw IOException("登录后无法验证账号信息")
        }
        // 再次确保 uid cookie 存在（用 getUserInfo 返回的 uid）
        val ticket = sessionVerification.beginRefresh {}
        if (ticket.generation != refreshGeneration || ticket.accountIdentity != loginUid) {
            throw CancellationException("Manwa login was superseded by a newer session")
        }
        val committed = sessionVerification.commitVerified(ticket, info.uid) {
            NetworkModule.cookieJar.add("uid", info.uid, RouteManager.lineHost)
            _userFlow.value = info
            saveUserInfoLocal(info)
            _sessionStatusFlow.value = ManwaSessionStatus.VERIFIED
        }
        if (!committed) throw CancellationException("Manwa login was superseded by a newer session")
        return info
    }

    /**
     * 注册：调用 /account/register。成功后自动登录态由 cookie 维持。
     */
    suspend fun register(username: String, password: String, email: String, authCode: String): String {
        val resp = try {
            api.register(com.zycomic.app.data.dto.RegisterRequest(username, password, email, authCode))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return "注册成功，请登录"
        }
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "注册失败" })
        return resp.msg.ifEmpty { "注册成功，请登录" }
    }

    suspend fun sendAuth(email: String, type: String, username: String = "") {
        val resp = api.sendAuth(com.zycomic.app.data.dto.SendAuthRequest(email, type, username))
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "发送验证码失败" })
    }

    suspend fun emailLogin(email: String, authToken: String): User {
        val loginGeneration = sessionVerification.beginLogin {
            _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
        }
        val resp = try {
            api.forgetPwd(com.zycomic.app.data.dto.ForgetPwdRequest(email, authToken))
        } catch (e: CancellationException) {
            sessionVerification.cancelLogin(loginGeneration) { _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN }
            throw e
        } catch (e: Exception) {
            sessionVerification.cancelLogin(loginGeneration) { _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN }
            throw e
        }
        if (resp.code != 1) {
            sessionVerification.cancelLogin(loginGeneration) { _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN }
            throw IOException(resp.msg.ifEmpty { "登录失败" })
        }
        val loginUid = resp.data?.uid?.takeIf { it.isNotEmpty() && it != "0" }
        val refreshGeneration = sessionVerification.establishLogin(loginGeneration, loginUid) {
            if (loginUid != null) NetworkModule.cookieJar.add("uid", loginUid, RouteManager.lineHost)
            _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
        } ?: throw CancellationException("Manwa login was superseded by a newer session")
        val info = getUserInfo()
        if (!ManwaSessionVerificationPolicy.isValidIdentity(info.uid) ||
            (loginUid != null && loginUid != info.uid)
        ) {
            throw IOException("登录后无法验证账号信息")
        }
        val ticket = sessionVerification.beginRefresh {}
        if (ticket.generation != refreshGeneration || ticket.accountIdentity != loginUid) {
            throw CancellationException("Manwa login was superseded by a newer session")
        }
        val committed = sessionVerification.commitVerified(ticket, info.uid) {
            NetworkModule.cookieJar.add("uid", info.uid, RouteManager.lineHost)
            _userFlow.value = info
            saveUserInfoLocal(info)
            _sessionStatusFlow.value = ManwaSessionStatus.VERIFIED
        }
        if (!committed) throw CancellationException("Manwa login was superseded by a newer session")
        return info
    }

    suspend fun changePassword(newPassword: String) {
        val transitionGeneration = sessionVerification.beginLocalTransition {
            _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
        }
        val resp = api.editUser(
            kotlinx.serialization.json.buildJsonObject {
                put("password", kotlinx.serialization.json.JsonPrimitive(newPassword))
            },
        )
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "修改密码失败" })
        sessionVerification.finishLocalTransition(transitionGeneration) {
            NetworkModule.cookieJar.clear()
            clearUserInfoLocal()
            _userFlow.value = null
            _sessionStatusFlow.value = ManwaSessionStatus.SIGNED_OUT
        }
    }

    suspend fun getCaptcha(): ByteArray = api.getCaptcha().bytes()

    /** Clear local credentials first, then attempt remote sign-out without blocking local logout. */
    suspend fun logout() {
        sessionVerification.beginLocalTransition {
            NetworkModule.cookieJar.clear()
            clearUserInfoLocal()
            _userFlow.value = null
            _sessionStatusFlow.value = ManwaSessionStatus.SIGNED_OUT
        }
        // The synchronized generation transition above fences earlier refreshes. There are no
        // local writes after the request, so its result cannot erase or downgrade a newer login.
        try {
            api.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 登出 API 失败不阻塞本地登出
        }
    }

    /**
     * Refresh /users/info. HTTP authorization errors can also come from an edge or challenge;
     * without a verified service-expiry contract, retain the cached profile and cookies as unknown.
     */
    suspend fun verifyLogin() {
        try {
            refreshUserInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Startup verification is best-effort; UNKNOWN is visible and cached data is retained.
        }
    }

    // ==================== 用户信息 ====================

    /** 拉取当前用户信息（/users/info），不更新 userFlow。 */
    suspend fun getUserInfo(): User {
        val resp = api.userInfo()
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取用户信息失败" })
        return resp.data ?: throw IOException("用户信息为空")
    }

    /** Refresh the cached profile and verify the current Manwa session. */
    suspend fun refreshUserInfo() {
        val ticket = sessionVerification.beginRefresh {
            _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
        }
        try {
            val resp = api.userInfo()
            if (!ManwaSessionVerificationPolicy.hasSuccessfulProfileResponse(resp.code, resp.data != null)) {
                throw IOException(resp.msg.ifEmpty { "获取用户信息失败" })
            }
            val info = resp.data ?: throw IOException("用户信息为空")
            if (!ManwaSessionVerificationPolicy.isValidIdentity(info.uid)) {
                throw IOException("无法验证 Manwa 账号信息")
            }
            if (ticket.accountIdentity != null && ticket.accountIdentity != info.uid) {
                val current = sessionVerification.markUnknown(ticket) {
                    _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
                }
                if (!current) throw CancellationException("Manwa refresh was superseded by a newer session")
                throw IOException("Manwa 账号在刷新期间发生变化")
            }
            val committed = sessionVerification.commitVerified(ticket, info.uid) {
                _userFlow.value = info
                saveUserInfoLocal(info)
                _sessionStatusFlow.value = ManwaSessionStatus.VERIFIED
            }
            if (!committed) throw CancellationException("Manwa refresh was superseded by a newer session")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val current = sessionVerification.markUnknown(ticket) {
                _sessionStatusFlow.value = ManwaSessionStatus.UNKNOWN
            }
            if (!current) throw CancellationException("Manwa refresh was superseded by a newer session")
            throw e
        }
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

    private suspend fun getAdLinks(): List<String> =
        Regex("https?://s\\.chmsrv\\.com/click\\.php\\?d=[^\"'\\s<>]+")
            .findAll(api.getWawaWise().string()).map { it.value }.toList()

    private suspend fun clickAd(link: String) {
        val encoded = java.net.URLEncoder.encode(link, "UTF-8")
        api.adClick(group = "ad_wawaweise", link = encoded, task = 1)
    }

    class AdDebugException(val debug: String) : IOException("广告链接提取失败")

    suspend fun claimAdBonus(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): Int {
        onProgress(0, 1, "查询福利状态...")
        val welfare = getWelfare()
        val needed = if (welfare?.check_ad_bonus_today == true) 0 else (5 - (welfare?.ad_clicks ?: 0)).coerceAtLeast(0)
        val total = needed.coerceAtLeast(1)
        if (needed > 0) {
            onProgress(0, total, "获取广告页面...")
            val helper = com.zycomic.app.net.AdClickHelper(appCtx)
            val attemptDiagnostics = mutableListOf<String>()
            var lastHtml = ""
            var links = emptyList<String>()
            for (attempt in 1..2) {
                if (attempt == 2) onProgress(0, total, "广告链接尚未渲染，重新获取页面...")
                val raw = api.getWawaWise().string()
                val html = try {
                    org.json.JSONObject(raw).getString("data")
                } catch (_: Exception) {
                    raw
                }
                lastHtml = html
                val extraction = helper.getAllAdLinks(html)
                attemptDiagnostics += "attempt=$attempt html_length=${html.length} ${extraction.diagnostics}"
                if (extraction.links.isNotEmpty()) {
                    links = extraction.links
                    break
                }
            }
            if (links.isEmpty()) {
                val debug = "ad_clicks=${welfare?.ad_clicks}, bonus_ready=${welfare?.check_ad_bonus_today}\n" +
                    attemptDiagnostics.joinToString("\n") +
                    "\nhtml前200: ${lastHtml.take(200)}"
                logcat { "每日广告奖励链接提取失败: $debug" }
                throw AdDebugException(debug)
            }
            repeat(needed) { i ->
                onProgress(i + 1, total, "点击广告 ${i + 1}/$total")
                clickAd(links[i % links.size])
                kotlinx.coroutines.delay(500)
            }
        }
        onProgress(total, total, "领取奖励中...")
        val resp = api.claimAdBonus(com.zycomic.app.data.dto.AdBonusRequest(action = "ad_bonus"))
        if (resp.code != 1 || resp.data?.status != "success") {
            throw IOException(resp.data?.msg?.ifEmpty { resp.msg.ifEmpty { "领取失败" } } ?: "领取失败")
        }
        return 10
    }

    /** 积分明细分页。 */
    suspend fun getPointLogs(page: Int): List<PointLog> {
        val resp = api.pointLogs(page)
        if (resp.code != 1) throw IOException(resp.msg.ifEmpty { "获取积分明细失败" })
        return resp.data?.list ?: emptyList()
    }
}
