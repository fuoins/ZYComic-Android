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
 * - glassTint：所有液态玻璃的默认本体颜色（默认白）；各部件可单独覆盖
 * - partTints：按 [GlassPart.key] 单独指定的玻璃颜色（不设置则跟随全局）
 * - uiAccent：界面主色（弹窗按钮、选中文字、进度条等原本跟随主题 primary 的地方），默认 #0054D6
 * - yuzongAccent：旧版宇总排行标识色，仅用于兼容旧配置；现在宇总直接用界面主色
 */
object AppearanceColors {
    private const val PREFS = "zy_appearance_colors"
    private const val KEY_GLASS_TINT = "glass_tint"
    private const val KEY_UI_ACCENT = "ui_accent"
    private const val KEY_YUZONG = "yuzong_accent"
    private const val PART_TINT_PREFIX = "part_tint_"
    private const val PART_TEXT_PREFIX = "part_text_"
    private const val KEY_SWITCH = "switch_color"

    /** 界面主色默认值（用户指定的蓝色）。 */
    val DEFAULT_UI_ACCENT = Color(0xFF0054D6)

    val DEFAULT_YUZONG_LIGHT = Color(0xFF2F6FD0)
    val DEFAULT_YUZONG_DARK = Color(0xFF8FB7FF)

    private val _glassTint = MutableStateFlow(Color.White)
    val glassTint: StateFlow<Color> = _glassTint.asStateFlow()

    private val _partTints = MutableStateFlow<Map<String, Color>>(emptyMap())
    val partTints: StateFlow<Map<String, Color>> = _partTints.asStateFlow()

    /** 各部件内部的文字颜色（不设置则跟随主题/平台色）。 */
    private val _partTextColors = MutableStateFlow<Map<String, Color>>(emptyMap())
    val partTextColors: StateFlow<Map<String, Color>> = _partTextColors.asStateFlow()

    /** 开关（设置项里的 Switch）开启态颜色；null = 跟随界面主色（默认 #0054D6）。 */
    private val _switchColor = MutableStateFlow<Color?>(null)
    val switchColor: StateFlow<Color?> = _switchColor.asStateFlow()

    private val _uiAccent = MutableStateFlow<Color?>(DEFAULT_UI_ACCENT)
    val uiAccent: StateFlow<Color?> = _uiAccent.asStateFlow()

    private val _yuzong = MutableStateFlow(DEFAULT_YUZONG_LIGHT)
    val yuzong: StateFlow<Color> = _yuzong.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _glassTint.value = prefs.getString(KEY_GLASS_TINT, null)?.let(::parseColor) ?: Color.White
        _uiAccent.value = prefs.getString(KEY_UI_ACCENT, null)?.let(::parseColor) ?: DEFAULT_UI_ACCENT
        _yuzong.value = prefs.getString(KEY_YUZONG, null)?.let(::parseColor) ?: DEFAULT_YUZONG_LIGHT
        _partTints.value = GlassPart.entries.mapNotNull { part ->
            prefs.getString(PART_TINT_PREFIX + part.key, null)?.let(::parseColor)?.let { part.key to it }
        }.toMap()
        _partTextColors.value = GlassPart.entries.mapNotNull { part ->
            prefs.getString(PART_TEXT_PREFIX + part.key, null)?.let(::parseColor)?.let { part.key to it }
        }.toMap()
        _switchColor.value = prefs.getString(KEY_SWITCH, null)?.let(::parseColor)
    }

    /** 指定部件当前使用的玻璃颜色：单独设置优先，否则跟随全局。 */
    fun tintFor(part: GlassPart): Color = _partTints.value[part.key] ?: _glassTint.value

    fun isOverridden(part: GlassPart): Boolean = _partTints.value.containsKey(part.key)

    fun setGlassTint(context: Context, color: Color) {
        _glassTint.value = color
        write(context, KEY_GLASS_TINT, color)
    }

    /** 指定部件内部的文字颜色；null = 跟随主题。 */
    fun textColorFor(part: GlassPart): Color? = _partTextColors.value[part.key]

    fun setPartTextColor(context: Context, part: GlassPart, color: Color?) {
        val next = _partTextColors.value.toMutableMap()
        val edit = prefs(context).edit()
        if (color == null) {
            next.remove(part.key)
            edit.remove(PART_TEXT_PREFIX + part.key)
        } else {
            next[part.key] = color
            edit.putString(PART_TEXT_PREFIX + part.key, hex(color))
        }
        _partTextColors.value = next
        edit.apply()
    }

    fun setSwitchColor(context: Context, color: Color?) {
        _switchColor.value = color
        val edit = prefs(context).edit()
        if (color == null) edit.remove(KEY_SWITCH) else edit.putString(KEY_SWITCH, hex(color))
        edit.apply()
    }

    /** 单独设置某类玻璃的颜色；[color] 为 null 时恢复跟随全局。 */
    fun setPartTint(context: Context, part: GlassPart, color: Color?) {
        val next = _partTints.value.toMutableMap()
        val edit = prefs(context).edit()
        if (color == null) {
            next.remove(part.key)
            edit.remove(PART_TINT_PREFIX + part.key)
        } else {
            next[part.key] = color
            edit.putString(PART_TINT_PREFIX + part.key, hex(color))
        }
        _partTints.value = next
        edit.apply()
    }

    /** 一键把所有玻璃设为同一个颜色（清除各部件单独设置）。 */
    fun applyTintToAllParts(context: Context, color: Color) {
        setGlassTint(context, color)
        val edit = prefs(context).edit()
        GlassPart.entries.forEach { part ->
            edit.remove(PART_TINT_PREFIX + part.key)
        }
        _partTints.value = emptyMap()
        edit.apply()
    }

    fun setUiAccent(context: Context, color: Color?) {
        // 界面主色始终有值：传 null 视为恢复默认蓝色。
        val next = color ?: DEFAULT_UI_ACCENT
        _uiAccent.value = next
        write(context, KEY_UI_ACCENT, next)
    }

    fun setYuzong(context: Context, color: Color) {
        _yuzong.value = color
        write(context, KEY_YUZONG, color)
    }

    /** 宇总排行标识色 = 界面主色（深色主题下自动提亮保证可读）。 */
    fun readableYuzong(dark: Boolean): Color = readable(uiAccent.value ?: DEFAULT_UI_ACCENT, dark)

    /** 让颜色在深浅主题下都可读：深色主题下过暗时自动提亮。 */
    fun readable(color: Color, dark: Boolean): Color {
        return if (dark && color.luminance() < 0.40f) {
            Color(
                red = (color.red + (1f - color.red) * 0.42f),
                green = (color.green + (1f - color.green) * 0.42f),
                blue = (color.blue + (1f - color.blue) * 0.42f),
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
