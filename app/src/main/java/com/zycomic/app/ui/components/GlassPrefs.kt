package com.zycomic.app.ui.components

import android.content.Context

/**
 * 背景与液态玻璃的外观参数（外观设置页可调）。
 * - backdropBlurDp：全局封面背景的模糊半径（0~24dp，默认 8dp）
 * - glassStrength：玻璃主体的强度系数（0~1，默认 1；作用于白玻璃透明度）
 */
object GlassPrefs {
    private const val PREFS_NAME = "zy_glass_prefs"
    private const val KEY_BACKDROP_BLUR = "backdrop_blur_dp"
    private const val KEY_GLASS_STRENGTH = "glass_strength"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun backdropBlurDp(context: Context): Float =
        prefs(context).getFloat(KEY_BACKDROP_BLUR, 8f).coerceIn(0f, 24f)

    fun setBackdropBlurDp(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_BACKDROP_BLUR, value.coerceIn(0f, 24f)).apply()
    }

    fun glassStrength(context: Context): Float =
        prefs(context).getFloat(KEY_GLASS_STRENGTH, 1f).coerceIn(0f, 1f)

    fun setGlassStrength(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_GLASS_STRENGTH, value.coerceIn(0f, 1f)).apply()
    }
}
