package com.zycomic.app.ui.components

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 背景与液态玻璃的外观参数（外观设置页可调，Compose 可观察、实时生效）。
 * - backdropBlurDp：全局背景的模糊半径（0~24dp，默认 8dp）
 * - backdropMask：背景顶部/底部渐隐遮罩强度（0~1，默认 1）
 * - glassStrength：玻璃主体的强度系数（0~1，默认 1；作用于白玻璃透明度）
 */
object GlassPrefs {
    private const val PREFS_NAME = "zy_glass_prefs"
    private const val KEY_BACKDROP_BLUR = "backdrop_blur_dp"
    private const val KEY_BACKDROP_MASK = "backdrop_mask"
    private const val KEY_GLASS_STRENGTH = "glass_strength"

    private val _backdropBlurDp = MutableStateFlow(8f)
    val backdropBlurDp: StateFlow<Float> = _backdropBlurDp.asStateFlow()

    private val _backdropMask = MutableStateFlow(1f)
    val backdropMask: StateFlow<Float> = _backdropMask.asStateFlow()

    private val _glassStrength = MutableStateFlow(1f)
    val glassStrength: StateFlow<Float> = _glassStrength.asStateFlow()

    private var initialized = false

    /** App 启动时调用一次，从持久化读入内存。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _backdropBlurDp.value = prefs.getFloat(KEY_BACKDROP_BLUR, 8f).coerceIn(0f, 24f)
        _backdropMask.value = prefs.getFloat(KEY_BACKDROP_MASK, 1f).coerceIn(0f, 1f)
        _glassStrength.value = prefs.getFloat(KEY_GLASS_STRENGTH, 1f).coerceIn(0f, 1f)
    }

    fun setBackdropBlurDp(context: Context, value: Float) {
        val v = value.coerceIn(0f, 24f)
        _backdropBlurDp.value = v
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_BACKDROP_BLUR, v).apply()
    }

    fun setBackdropMask(context: Context, value: Float) {
        val v = value.coerceIn(0f, 1f)
        _backdropMask.value = v
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_BACKDROP_MASK, v).apply()
    }

    fun setGlassStrength(context: Context, value: Float) {
        val v = value.coerceIn(0f, 1f)
        _glassStrength.value = v
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_GLASS_STRENGTH, v).apply()
    }
}
