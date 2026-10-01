package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.LoginRequest
import com.zycomic.app.data.dto.PointLog
import com.zycomic.app.data.dto.User
import com.zycomic.app.data.dto.WelfareData
import com.zycomic.app.net.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * 用户仓库：全局登录状态源。
 *
 * - [userFlow] 是全局唯一的登录状态流，所有 ViewModel 收集它。
 * - 登录 / 登出 / 启动校验时都会更新 [userFlow]。
 * - cookie 由 [NetworkModule.cookieJar] 全局维护。
 */
object UserRepository {

    private val api get() = NetworkModule.api

    private val _userFlow = MutableStateFlow<User?>(null)

    /** 当前登录用户，未登录为 null。所有 ViewModel 收集此流。 */
    val userFlow: StateFlow<User?> = _userFlow.asStateFlow()

    /** 当前是否已登录（同步读取） */
    val isLoggedIn: Boolean get() = _userFlow.value != null

    // ==================== 登录 / 注册 ====================

    /**
     * 登录：先调 /users/login，成功后再调 /users/info 获取完整用户信息，更新 userFlow。
     */
    suspend fun login(username: String, password: String): User {
        val loginResp = api.login(LoginRequest(username, password))
        if (loginResp.code != 0) throw IOException(loginResp.msg.ifEmpty { "登录失败" })

        // 登录成功后拉取完整用户信息
        val info = getUserInfo()
        _userFlow.value = info
        return info
    }

    /**
     * 注册：调用 /users/register。成功后自动登录态由 cookie 维持。
     */
    suspend fun register(username: String, password: String, email: String): User {
        // 注册接口当前 DTO 仅支持 username/password；email 参数保留以兼容未来扩展
        val resp = api.register(LoginRequest(username, password))
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "注册失败" })

        // 注册成功后拉取用户信息
        val info = getUserInfo()
        _userFlow.value = info
        return info
    }

    /**
     * 登出：清除全局 cookie，userFlow 置 null。
     */
    fun logout() {
        NetworkModule.cookieJar.clear()
        _userFlow.value = null
    }

    /**
     * 启动时校验登录态：调 /users/info，成功则更新 userFlow，失败则自动登出。
     * @return true=登录有效，false=已登出
     */
    suspend fun verifyLogin(): Boolean {
        return try {
            val info = getUserInfo()
            _userFlow.value = info
            true
        } catch (e: Exception) {
            logout()
            false
        }
    }

    // ==================== 用户信息 ====================

    /** 拉取当前用户信息（/users/info），不更新 userFlow。 */
    suspend fun getUserInfo(): User {
        val resp = api.userInfo()
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取用户信息失败" })
        return resp.data ?: throw IOException("用户信息为空")
    }

    /** 刷新当前用户信息到 userFlow。 */
    suspend fun refreshUserInfo() {
        _userFlow.value = getUserInfo()
    }

    // ==================== 签到 / 福利 / 积分 ====================

    /** 获取签到福利信息（同时触发签到）。 */
    suspend fun getWelfare(): WelfareData? {
        val resp = api.welfare()
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取福利失败" })
        return resp.data
    }

    /** 签到：调用福利接口即完成签到动作。 */
    suspend fun signIn(): WelfareData? {
        return getWelfare()
    }

    /** 积分明细分页。 */
    suspend fun getPointLogs(page: Int): List<PointLog> {
        val resp = api.pointLogs(page)
        if (resp.code != 0) throw IOException(resp.msg.ifEmpty { "获取积分明细失败" })
        return resp.data
    }
}
