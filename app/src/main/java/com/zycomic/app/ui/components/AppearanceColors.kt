package com.zycomic.app.ui.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观的“颜色”部分（与尺寸参数分开持久化）：
 * - glassTint：所有液态玻璃的本体颜色（默认白，可染色；不再只有灰白）
 * - uiAccent：界面主色（弹窗按钮、选中文字、进度条等原本跟随主题 primary 的地方）；null = 跟随主题
 * - yuzongAccent：宇总排行的标识色（标签/字体）
 */
object AppearanceColors {
    private const val PREFS = "zy_appearance_colors"
    private const val KEY_GLASS_TINT = "glass_tint"
    private const val KEY_UI_ACCENT = "ui_accent"
    private const val KEY_YUZONG = "yuzong_accent"

    val DEFAULT_YUZONG_LIGHT = Color(0xFF2F6FD0)
    val DEFAULT_YUZONG_DARK = Color(0xFF8FB7FF)

    private val _glassTint = MutableStateFlow(Color.White)
    val glassTint: StateFlow<Color> = _glassTint.asStateFlow()

    private val _uiAccent = MutableStateFlow<Color?>(null)
    val uiAccent: StateFlow<Color?> = _uiAccent.asStateFlow()

    private val _yuzong = MutableStateFlow(DEFAULT_YUZONG_LIGHT)
    val yuzong: StateFlow<Color> = _yuzong.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _glassTint.value = prefs.getString(KEY_GLASS_TINT, null)?.let(::parseColor) ?: Color.White
        _uiAccent.value = prefs.getString(KEY_UI_ACCENT, null)?.let(::parseColor)
        _yuzong.value = prefs.getString(KEY_YUZONG, null)?.let(::parseColor) ?: DEFAULT_YUZONG_LIGHT
    }

    fun setGlassTint(context: Context, color: Color) {
        _glassTint.value = color
        write(context, KEY_GLASS_TINT, color)
    }

    fun setUiAccent(context: Context, color: Color?) {
        _uiAccent.value = color
        val edit = prefs(context).edit()
        if (color == null) edit.remove(KEY_UI_ACCENT) else edit.putString(KEY_UI_ACCENT, hex(color))
        edit.apply()
    }

    fun setYuzong(context: Context, color: Color) {
        _yuzong.value = color
        write(context, KEY_YUZONG, color)
    }

    /** 宇总色在深浅色下的可读版本：深色主题下自动提亮。 */
    fun readableYuzong(dark: Boolean): Color {
        val color = _yuzong.value
        return if (dark && color.luminance() < 0.45f) {
            Color(
                red = (color.red + (1f - color.red) * 0.45f),
                green = (color.green + (1f - color.green) * 0.45f),
                blue = (color.blue + (1f - color.blue) * 0.45f),
                alpha = 1f,
            )
        } else {
            color
        }
    }

    fun hex(color: Color): String =
        "#" + color.toArgb().toUInt().toString(16).uppercase().padStart(8, '0').takeLast(6)

    fun parseColor(raw: String): Color? {
        val text = raw.trim().removePrefix("#")
        return runCatching {
            when (text.length) {
                6 -> Color(0xFF000000 or text.toLong(16))
                8 -> Color(text.toLong(16))
                else -> null
            }
        }.getOrNull()
    }

    private fun write(context: Context, key: String, color: Color) {
        prefs(context).edit().putString(key, hex(color)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
