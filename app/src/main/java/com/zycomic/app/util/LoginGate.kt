package com.zycomic.app.util

import android.content.Context
import android.widget.Toast
import com.zycomic.app.data.repository.UserRepository

/**
 * 统一的登录门槛：账号级功能（收藏/历史/标签屏蔽/一键屏蔽）入口共用。
 *
 * - 已登录：执行 [action] 并返回 true；
 * - 未登录：统一 Toast 文案并返回 false，由调用方负责跳转到登录页（不同场景导航方式不同）。
 */
object LoginGate {
    const val NEED_LOGIN_MSG = "需要登录才能使用"

    fun ensureLogin(context: Context, action: () -> Unit = {}): Boolean {
        return if (UserRepository.isLoggedIn) {
            action()
            true
        } else {
            Toast.makeText(context, NEED_LOGIN_MSG, Toast.LENGTH_SHORT).show()
            false
        }
    }
}
