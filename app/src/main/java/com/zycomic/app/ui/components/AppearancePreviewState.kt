package com.zycomic.app.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观预览模式：
 * - 在外观设置页点「预览」→ 关闭设置页回到主界面，并标记为预览中；
 * - 主界面显示一个悬浮按钮，点击回到外观设置页并退出预览；
 * - 预览期间主界面完全可交互（切 tab、进详情、搜索等），方便边看边调。
 */
object AppearancePreviewState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun enter() {
        _active.value = true
    }

    fun exit() {
        _active.value = false
    }
}
