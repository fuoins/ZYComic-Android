package com.zycomic.app.ui.components

import android.content.Context
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformAccentPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 外观配置导出 / 导入（JSON）：
 * 覆盖背景参数、每个玻璃部件的模糊/透明度、玻璃染色、界面主色、平台/宇总颜色、随机图源开关。
 * 自定义背景图是设备本地文件，不随配置迁移（导入后仍用本机已选图片）。
 */
object AppearanceConfig {
    private const val VERSION = 1

    fun exportJson(context: Context): String {
        val root = JSONObject()
        root.put("version", VERSION)

        root.put(
            "glass",
            JSONObject().apply {
                put("backdropBlur", GlassPrefs.backdropBlurDp.value)
                put("backdropAlpha", GlassPrefs.backdropAlpha.value)
                put("strength", GlassPrefs.glassStrength.value)
                put("tint", AppearanceColors.hex(AppearanceColors.glassTint.value))
                put("uiAccent", AppearanceColors.uiAccent.value?.let(AppearanceColors::hex) ?: "")
            },
        )

        root.put(
            "parts",
            JSONObject().apply {
                GlassPart.entries.forEach { part ->
                    put(
                        part.key,
                        JSONObject().apply {
                            put("blur", GlassPrefs.partBlur.value[part.key] ?: part.defaultBlurDp)
                            put("alpha", GlassPrefs.partAlpha.value[part.key] ?: part.defaultAlpha)
                            put(
                                "tint",
                                AppearanceColors.partTints.value[part.key]?.let(AppearanceColors::hex) ?: "",
                            )
                        },
                    )
                }
            },
        )

        root.put(
            "platforms",
            JSONObject().apply {
                put("manwa", AppearanceColors.hex(ContentPlatformAccentPrefs.manwa.value))
                put("picacg", AppearanceColors.hex(ContentPlatformAccentPrefs.picacg.value))
                put("jm", AppearanceColors.hex(ContentPlatformAccentPrefs.jm.value))
                put("yuzong", AppearanceColors.hex(AppearanceColors.yuzong.value))
            },
        )

        root.put("sources", JSONArray(BackdropSources.enabled.value.toList()))

        root.put(
            "backdrop",
            JSONObject().apply {
                BackdropSources.PAGE_KEYS.forEach { key ->
                    put(key, BackdropSources.pagePath(key) ?: "")
                }
                put("global", BackdropSources.customPath.value ?: "")
            },
        )
        return root.toString(2)
    }

    /** 应用导入的 JSON；格式不合法或没有任何字段时返回 false。 */
    fun importJson(context: Context, raw: String): Boolean = runCatching {
        val root = JSONObject(raw)
        var applied = 0

        root.optJSONObject("glass")?.let { glass ->
            if (glass.has("backdropBlur")) {
                GlassPrefs.setBackdropBlurDp(context, glass.optDouble("backdropBlur").toFloat()); applied++
            }
            if (glass.has("backdropAlpha")) {
                GlassPrefs.setBackdropAlpha(context, glass.optDouble("backdropAlpha").toFloat()); applied++
            }
            if (glass.has("strength")) {
                GlassPrefs.setGlassStrength(context, glass.optDouble("strength").toFloat()); applied++
            }
            AppearanceColors.parseColor(glass.optString("tint"))?.let {
                AppearanceColors.setGlassTint(context, it); applied++
            }
            if (glass.has("uiAccent")) {
                val parsed = AppearanceColors.parseColor(glass.optString("uiAccent"))
                AppearanceColors.setUiAccent(context, parsed)
                applied++
            }
        }

        root.optJSONObject("parts")?.let { parts ->
            GlassPart.entries.forEach { part ->
                val obj = parts.optJSONObject(part.key) ?: return@forEach
                if (obj.has("blur")) {
                    GlassPrefs.setPartBlur(context, part, obj.optDouble("blur").toFloat())
                }
                if (obj.has("alpha")) {
                    GlassPrefs.setPartAlpha(context, part, obj.optDouble("alpha").toFloat())
                }
                if (obj.has("tint")) {
                    AppearanceColors.setPartTint(context, part, AppearanceColors.parseColor(obj.optString("tint")))
                }
                applied++
            }
        }

        root.optJSONObject("platforms")?.let { platforms ->
            AppearanceColors.parseColor(platforms.optString("manwa"))?.let {
                ContentPlatformAccentPrefs.set(ContentPlatform.MANWA, it); applied++
            }
            AppearanceColors.parseColor(platforms.optString("picacg"))?.let {
                ContentPlatformAccentPrefs.set(ContentPlatform.PICACG, it); applied++
            }
            AppearanceColors.parseColor(platforms.optString("jm"))?.let {
                ContentPlatformAccentPrefs.set(ContentPlatform.JM, it); applied++
            }
            AppearanceColors.parseColor(platforms.optString("yuzong"))?.let {
                AppearanceColors.setYuzong(context, it); applied++
            }
        }

        root.optJSONArray("sources")?.let { arr ->
            val ids = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
            BackdropSources.ALL.forEach { source ->
                BackdropSources.setEnabled(context, source.id, source.id in ids)
            }
            applied++
        }

        AppBackdropState.refresh()
        applied > 0
    }.getOrDefault(false)
}
