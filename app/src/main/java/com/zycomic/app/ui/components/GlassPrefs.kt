package com.zycomic.app.ui.components

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 玻璃部件：每类部件有独立的模糊度与白玻璃透明度，可在外观页分别调整。 */
enum class GlassPart(
    val key: String,
    val label: String,
    val defaultBlurDp: Float,
    val defaultAlpha: Float,
) {
    BOTTOM_BAR("bottom_bar", "底栏", 20f, 0.50f),
    TOP_BAR("top_bar", "顶栏", 14f, 0.30f),
    BUTTON("button", "按钮 / 卡片", 12f, 0.22f),
    TAB("tab", "标签", 14f, 0.30f),
    COVER_NAME("cover_name", "封面名字条", 10f, 0.42f),
    SEARCH("search", "搜索框", 14f, 0.26f),
}

/**
 * 背景与液态玻璃的外观参数（外观设置页可调，Compose 可观察、实时生效）。
 * - backdropBlurDp / backdropAlpha：全局背景图的模糊度与不透明度
 * - per-part：每类玻璃部件的模糊度与白玻璃透明度
 * - glassStrength：全局玻璃强度（所有部件的总乘数）
 */
object GlassPrefs {
    private const val PREFS_NAME = "zy_glass_prefs"
    private const val KEY_BACKDROP_BLUR = "backdrop_blur_dp"
    private const val KEY_BACKDROP_ALPHA = "backdrop_alpha"
    private const val KEY_GLASS_STRENGTH = "glass_strength"
    private const val KEY_PART_BLUR_PREFIX = "part_blur_"
    private const val KEY_PART_ALPHA_PREFIX = "part_alpha_"

    private val _backdropBlurDp = MutableStateFlow(8f)
    val backdropBlurDp: StateFlow<Float> = _backdropBlurDp.asStateFlow()

    private val _backdropAlpha = MutableStateFlow(0.85f)
    val backdropAlpha: StateFlow<Float> = _backdropAlpha.asStateFlow()

    private val _glassStrength = MutableStateFlow(1f)
    val glassStrength: StateFlow<Float> = _glassStrength.asStateFlow()

    private val _partBlur = MutableStateFlow(GlassPart.entries.associate { it.key to it.defaultBlurDp })
    val partBlur: StateFlow<Map<String, Float>> = _partBlur.asStateFlow()

    private val _partAlpha = MutableStateFlow(GlassPart.entries.associate { it.key to it.defaultAlpha })
    val partAlpha: StateFlow<Map<String, Float>> = _partAlpha.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _backdropBlurDp.value = prefs.getFloat(KEY_BACKDROP_BLUR, 8f).coerceIn(0f, 24f)
        _backdropAlpha.value = prefs.getFloat(KEY_BACKDROP_ALPHA, 0.85f).coerceIn(0.3f, 1f)
        _glassStrength.value = prefs.getFloat(KEY_GLASS_STRENGTH, 1f).coerceIn(0f, 1f)
        _partBlur.value = GlassPart.entries.associate { part ->
            part.key to prefs.getFloat(KEY_PART_BLUR_PREFIX + part.key, part.defaultBlurDp).coerceIn(0f, 32f)
        }
        _partAlpha.value = GlassPart.entries.associate { part ->
            part.key to prefs.getFloat(KEY_PART_ALPHA_PREFIX + part.key, part.defaultAlpha).coerceIn(0f, 1f)
        }
    }

    fun setBackdropBlurDp(context: Context, value: Float) {
        val v = value.coerceIn(0f, 24f)
        _backdropBlurDp.value = v
        write(context, KEY_BACKDROP_BLUR, v)
    }

    fun setBackdropAlpha(context: Context, value: Float) {
        val v = value.coerceIn(0.3f, 1f)
        _backdropAlpha.value = v
        write(context, KEY_BACKDROP_ALPHA, v)
    }

    fun setGlassStrength(context: Context, value: Float) {
        val v = value.coerceIn(0f, 1f)
        _glassStrength.value = v
        write(context, KEY_GLASS_STRENGTH, v)
    }

    fun setPartBlur(context: Context, part: GlassPart, value: Float) {
        val v = value.coerceIn(0f, 32f)
        _partBlur.value = _partBlur.value + (part.key to v)
        write(context, KEY_PART_BLUR_PREFIX + part.key, v)
    }

    fun setPartAlpha(context: Context, part: GlassPart, value: Float) {
        val v = value.coerceIn(0f, 1f)
        _partAlpha.value = _partAlpha.value + (part.key to v)
        write(context, KEY_PART_ALPHA_PREFIX + part.key, v)
    }

    private fun write(context: Context, key: String, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(key, value).apply()
    }
}
