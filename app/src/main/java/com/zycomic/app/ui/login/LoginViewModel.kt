package com.zycomic.app.ui.login

import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val mode = MutableStateFlow(0)

    val username = MutableStateFlow("")
    val password = MutableStateFlow("")
    val confirmPassword = MutableStateFlow("")
    val captchaInput = MutableStateFlow("")
    val email = MutableStateFlow("")
    val authCode = MutableStateFlow("")
    val captchaImage = MutableStateFlow<ByteArray?>(null)
    val authCountdown = MutableStateFlow(0)

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val success = MutableStateFlow(false)

    init { refreshCaptcha() }

    fun refreshCaptcha() {
        scope.launch {
            try { captchaImage.value = UserRepository.getCaptcha() } catch (_: Exception) {}
        }
    }

    fun login() {
        val u = username.value.trim()
        val p = password.value
        val c = captchaInput.value.trim()
        if (u.isEmpty() || p.isEmpty() || c.isEmpty()) { _error.value = "请输入账号、密码和验证码"; return }
        scope.launch {
            _loading.value = true; _error.value = null
            try { UserRepository.login(u, p, c); success.value = true }
            catch (e: Exception) { _error.value = e.message ?: "登录失败"; refreshCaptcha() }
            finally { _loading.value = false }
        }
    }

    fun emailLogin() {
        val e = email.value.trim()
        val code = authCode.value.trim()
        if (e.isEmpty() || code.isEmpty()) { _error.value = "请输入邮箱和验证码"; return }
        scope.launch {
            _loading.value = true; _error.value = null
            try { UserRepository.emailLogin(e, code); success.value = true }
            catch (ex: Exception) { _error.value = ex.message ?: "登录失败" }
            finally { _loading.value = false }
        }
    }

    fun sendAuthCode() {
        val e = email.value.trim()
        if (e.isEmpty()) { _error.value = "请输入邮箱"; return }
        val m = mode.value
        scope.launch {
            try {
                if (m == 1) UserRepository.sendAuth(e, "forget")
                else UserRepository.sendAuth(e, "register", username.value.trim())
                authCountdown.value = 60
                scope.launch { repeat(60) { delay(1000); authCountdown.value-- } }
            } catch (ex: Exception) { _error.value = ex.message ?: "发送失败" }
        }
    }

    fun register() {
        val u = username.value.trim()
        val p = password.value
        val e = email.value.trim()
        val code = authCode.value.trim()
        if (u.isEmpty()) { _error.value = "请输入账号"; return }
        if (!Regex("^[a-zA-Z0-9]{6,32}$").matches(u)) { _error.value = "账号仅限英文与数字，长度6-32位"; return }
        if (p.isEmpty() || p != confirmPassword.value) { _error.value = "两次密码不一致"; return }
        if (e.isEmpty()) { _error.value = "请输入邮箱"; return }
        if (code.isEmpty()) { _error.value = "请输入邮箱验证码"; return }
        scope.launch {
            _loading.value = true; _error.value = null
            try { UserRepository.register(u, p, e, code); success.value = true }
            catch (ex: Exception) { _error.value = ex.message ?: "注册失败" }
            finally { _loading.value = false }
        }
    }
}
