package com.zycomic.app.ui.login

import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val username = MutableStateFlow("")
    val password = MutableStateFlow("")

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 登录成功事件。 */
    val success = MutableStateFlow(false)

    fun login() {
        val u = username.value.trim()
        val p = password.value
        if (u.isEmpty() || p.isEmpty()) {
            _error.value = "请输入用户名和密码"
            return
        }
        scope.launch {
            _loading.value = true
            _error.value = null
            try {
                UserRepository.login(u, p)
                success.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "登录失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun register() {
        val u = username.value.trim()
        val p = password.value
        if (u.isEmpty() || p.isEmpty()) {
            _error.value = "请输入用户名和密码"
            return
        }
        scope.launch {
            _loading.value = true
            _error.value = null
            try {
                UserRepository.register(u, p, "")
                success.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "注册失败"
            } finally {
                _loading.value = false
            }
        }
    }
}
