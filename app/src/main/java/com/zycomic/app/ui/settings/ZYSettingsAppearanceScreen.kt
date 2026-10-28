package com.zycomic.app.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.materialkolor.PaletteStyle
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformAccentPrefs
import com.zycomic.app.platform.rememberContentPlatformAccent
import com.zycomic.app.ui.components.AppBackdropState
import com.zycomic.app.ui.components.AppearanceColors
import com.zycomic.app.ui.components.AppearancePreviewState
import com.zycomic.app.ui.components.BackdropSources
import com.zycomic.app.ui.components.ColorPickerDialog
import com.zycomic.app.ui.components.GlassPart
import com.zycomic.app.ui.components.GlassPrefs
import com.zycomic.app.ui.components.GlassPreferenceRow
import com.zycomic.app.ui.components.GlassSwitchRow
import com.zycomic.app.ui.components.glassSurface
import eu.kanade.core.preference.asState
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScreen
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.screen.appearance.AppCustomThemeColorPickerScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppLanguageScreen
import eu.kanade.presentation.more.settings.widget.AppThemeModePreferenceWidget
import eu.kanade.presentation.more.settings.widget.AppThemePreferenceWidget
import eu.kanade.presentation.more.settings.widget.ThemeColorPickerWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate

/**
 * ZYComic 外观设置：复制 komikku SettingsAppearanceScreen，但去掉 Fork 分组。
 */
object ZYSettingsAppearanceScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = ZYSettingsAppearanceScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_appearance

    @Composable
    override fun getPreferences(): List<Preference> {
        return listOf(
            getColorGroup(),
            getGlassGroup(),
            getBackdropGroup(),
            getConfigGroup(),
        )
    }

    /** 标签页式外观页：颜色 / 背景与玻璃 / 背景图 / 配置备份。 */
    @Composable
    override fun Content() {
        val handleBack = LocalBackPress.current
        var selectedTab by rememberSaveable { mutableStateOf(0) }
        val tabs = listOf("颜色", "背景与玻璃", "背景图", "配置备份")

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(getTitleRes()),
                    navigateUp = handleBack,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        TextButton(
                            onClick = {
                                // 关掉设置页回到主界面，主界面上会出现「返回外观」悬浮按钮。
                                AppearancePreviewState.enter()
                                handleBack?.invoke()
                            },
                        ) {
                            Text("预览")
                        }
                    },
                )
            },
        ) { contentPadding ->
            Column(modifier = Modifier.padding(contentPadding)) {
                AppearanceTabBar(tabs = tabs, selected = selectedTab, onSelect = { selectedTab = it })
                key(selectedTab) {
                    PreferenceScreen(
                        items = listOf(
                            when (selectedTab) {
                                0 -> getColorGroup()
                                1 -> getGlassGroup()
                                2 -> getBackdropGroup()
                                else -> getConfigGroup()
                            },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    /** 颜色：玻璃染色 / 界面主色 / 三个平台与宇总排行的标识色。 */
    @Composable
    private fun getColorGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val glassTint by AppearanceColors.glassTint.collectAsState()
        val partTints by AppearanceColors.partTints.collectAsState()
        val partTextColors by AppearanceColors.partTextColors.collectAsState()
        val switchColor by AppearanceColors.switchColor.collectAsState()
        val uiAccent by AppearanceColors.uiAccent.collectAsState()
        val manwa by ContentPlatformAccentPrefs.manwa.collectAsState()
        val picacg by ContentPlatformAccentPrefs.picacg.collectAsState()
        val jm by ContentPlatformAccentPrefs.jm.collectAsState()
        var target by remember { mutableStateOf<AppearanceColorTarget?>(null) }
        var applyAllConfirm by remember { mutableStateOf(false) }
        val themePrimary = MaterialTheme.colorScheme.primary

        val group = Preference.PreferenceGroup(
            title = "颜色",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(title = "玻璃颜色") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AppearanceColorRow(
                            title = "全部玻璃颜色",
                            subtitle = "默认 ${AppearanceColors.hex(glassTint)}；未单独设置的部件都使用这个颜色",
                            color = glassTint,
                            onClick = {
                                target = AppearanceColorTarget("全部玻璃颜色", glassTint) { ctx, color ->
                                    AppearanceColors.setGlassTint(ctx, color)
                                }
                            },
                        )
                        GlassPreferenceRow(
                            title = "一键应用到所有玻璃",
                            subtitle = "把当前颜色套到全部 ${GlassPart.entries.size} 类玻璃，并清除各部件单独设置",
                            onPreferenceClick = { applyAllConfirm = true },
                        )
                    }
                },
                Preference.PreferenceItem.CustomPreference(title = "各部件玻璃颜色") {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            "每类玻璃可以单独指定颜色；不设置则跟随「全部玻璃颜色」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        GlassPart.entries.forEach { part ->
                            val overridden = partTints.containsKey(part.key)
                            val current = partTints[part.key] ?: glassTint
                            val textColor = partTextColors[part.key]
                            AppearanceColorRow(
                                title = part.label,
                                subtitle = buildString {
                                    append(
                                        if (overridden) "玻璃 ${AppearanceColors.hex(current)}" else "玻璃跟随全局",
                                    )
                                    append(" · ")
                                    append(textColor?.let { "字体 ${AppearanceColors.hex(it)}" } ?: "字体跟随主题")
                                },
                                color = current,
                                onClick = {
                                    target = AppearanceColorTarget(part.label, current) { ctx, color ->
                                        AppearanceColors.setPartTint(ctx, part, color)
                                    }
                                },
                                action = {
                                    if (overridden) {
                                        TextButton(onClick = {
                                            AppearanceColors.setPartTint(context, part, null)
                                        }) { Text("跟随全局", style = MaterialTheme.typography.labelSmall) }
                                    }
                                    // 字体颜色（"A" 色块）：点开调色板；未设置时为空心块。
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 2.dp)
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(textColor ?: Color.Transparent)
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                                                shape = RoundedCornerShape(6.dp),
                                            )
                                            .clickable {
                                                target = AppearanceColorTarget(
                                                    title = "${part.label} 字体颜色",
                                                    current = textColor ?: MaterialTheme.colorScheme.onSurface,
                                                    apply = { ctx, color ->
                                                        AppearanceColors.setPartTextColor(ctx, part, color)
                                                    },
                                                    reset = { ctx ->
                                                        AppearanceColors.setPartTextColor(ctx, part, null)
                                                    },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "A",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = textColor?.let {
                                                if (it.luminance() > 0.55f) Color.Black else Color.White
                                            } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }
                },
                Preference.PreferenceItem.CustomPreference(title = "界面主色") {
                    AppearanceColorRow(
                        title = "界面主色",
                        subtitle = "弹窗按钮 / 选中文字 / 宇总排行（${AppearanceColors.hex(uiAccent ?: themePrimary)}）",
                        color = uiAccent ?: themePrimary,
                        onClick = {
                            target = AppearanceColorTarget(
                                "界面主色",
                                uiAccent ?: themePrimary,
                            ) { ctx, color ->
                                AppearanceColors.setUiAccent(ctx, color)
                            }
                        },
                    )
                },
                Preference.PreferenceItem.CustomPreference(title = "开关颜色") {
                    AppearanceColorRow(
                        title = "开关颜色",
                        subtitle = switchColor?.let { "开启态 ${AppearanceColors.hex(it)}" } ?: "跟随系统中性样式；点此自定义",
                        color = switchColor ?: themePrimary,
                        onClick = {
                            target = AppearanceColorTarget(
                                title = "开关颜色",
                                current = switchColor ?: themePrimary,
                                apply = { ctx, color -> AppearanceColors.setSwitchColor(ctx, color) },
                                reset = { ctx -> AppearanceColors.setSwitchColor(ctx, null) },
                            )
                        },
                        action = {
                            if (switchColor != null) {
                                TextButton(onClick = {
                                    AppearanceColors.setSwitchColor(context, null)
                                }) { Text("恢复默认", style = MaterialTheme.typography.labelSmall) }
                            }
                        },
                    )
                },
                Preference.PreferenceItem.CustomPreference(title = "平台字体颜色") {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        AppearanceColorRow(
                            title = "漫蛙",
                            subtitle = AppearanceColors.hex(manwa),
                            color = manwa,
                            onClick = {
                                target = AppearanceColorTarget("漫蛙颜色", manwa) { _, color ->
                                    ContentPlatformAccentPrefs.set(ContentPlatform.MANWA, color)
                                }
                            },
                        )
                        AppearanceColorRow(
                            title = "哔咔",
                            subtitle = AppearanceColors.hex(picacg),
                            color = picacg,
                            onClick = {
                                target = AppearanceColorTarget("哔咔颜色", picacg) { _, color ->
                                    ContentPlatformAccentPrefs.set(ContentPlatform.PICACG, color)
                                }
                            },
                        )
                        AppearanceColorRow(
                            title = "禁漫天堂",
                            subtitle = AppearanceColors.hex(jm),
                            color = jm,
                            onClick = {
                                target = AppearanceColorTarget("禁漫颜色", jm) { _, color ->
                                    ContentPlatformAccentPrefs.set(ContentPlatform.JM, color)
                                }
                            },
                        )
                    }
                },
            ),
        )

        target?.let { picked ->
            ColorPickerDialog(
                title = picked.title,
                initial = picked.current,
                onDismiss = { target = null },
                onConfirm = { color ->
                    picked.apply(context, color)
                    target = null
                },
                onReset = picked.reset?.let { reset ->
                    {
                        reset(context)
                        target = null
                    }
                },
            )
        }

        if (applyAllConfirm) {
            AlertDialog(
                onDismissRequest = { applyAllConfirm = false },
                title = { Text("应用到所有玻璃") },
                text = {
                    Text(
                        "会把全部 ${GlassPart.entries.size} 类玻璃设为 ${AppearanceColors.hex(glassTint)}，" +
                            "并清除各部件单独设置的颜色。确定继续？",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        AppearanceColors.applyTintToAllParts(context, glassTint)
                        applyAllConfirm = false
                        context.toast("已应用到所有玻璃")
                    }) { Text("应用") }
                },
                dismissButton = {
                    TextButton(onClick = { applyAllConfirm = false }) { Text("取消") }
                },
            )
        }
        return group
    }

    /** 配置备份：外观参数导入 / 导出 / 恢复默认。 */
    @Composable
    private fun getConfigGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(com.zycomic.app.ui.components.AppearanceConfig.exportJson(context).toByteArray())
                    }
                }.onSuccess {
                    context.toast("外观配置已导出")
                }.onFailure {
                    context.toast("导出失败：${it.message}")
                }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                    com.zycomic.app.ui.components.AppearanceConfig.importJson(context, text)
                }.onSuccess { applied ->
                    context.toast(if (applied) "外观配置已导入" else "配置文件无效")
                }.onFailure {
                    context.toast("导入失败：${it.message}")
                }
            }
        }
        return Preference.PreferenceGroup(
            title = "配置备份",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(title = "导出外观配置") {
                    com.zycomic.app.ui.components.GlassPreferenceRow(
                        title = "导出外观配置",
                        subtitle = "把当前外观参数保存成 JSON 文件",
                        onPreferenceClick = { exportLauncher.launch("zycomic-appearance.json") },
                    )
                },
                Preference.PreferenceItem.CustomPreference(title = "导入外观配置") {
                    com.zycomic.app.ui.components.GlassPreferenceRow(
                        title = "导入外观配置",
                        subtitle = "从 JSON 文件恢复外观参数",
                        onPreferenceClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                },
                Preference.PreferenceItem.CustomPreference(title = "恢复默认外观") {
                    com.zycomic.app.ui.components.GlassPreferenceRow(
                        title = "恢复默认外观",
                        subtitle = "玻璃参数、颜色、图源全部回到默认值",
                        onPreferenceClick = {
                            GlassPart.entries.forEach { part ->
                                GlassPrefs.setPartBlur(context, part, part.defaultBlurDp)
                                GlassPrefs.setPartAlpha(context, part, part.defaultAlpha)
                                GlassPrefs.setPartFrosted(context, part, true)
                                AppearanceColors.setPartTint(context, part, null)
                                AppearanceColors.setPartTextColor(context, part, null)
                            }
                            GlassPrefs.setBackdropBlurDp(context, 8f)
                            GlassPrefs.setBackdropAlpha(context, 0.85f)
                            GlassPrefs.setGlassStrength(context, 1f)
                            AppearanceColors.setGlassTint(context, Color.White)
                            AppearanceColors.setUiAccent(context, AppearanceColors.DEFAULT_UI_ACCENT)
                            AppearanceColors.setSwitchColor(context, null)
                            AppearanceColors.setYuzong(context, AppearanceColors.DEFAULT_YUZONG_LIGHT)
                            ContentPlatform.entries.forEach { ContentPlatformAccentPrefs.reset(it) }
                            BackdropSources.ALL.forEach { source ->
                                BackdropSources.setEnabled(context, source.id, false)
                            }
                            AppBackdropState.refresh("settings")
                            context.toast("已恢复默认外观")
                        },
                    )
                },
            ),
        )
    }

    /** 背景与液态玻璃参数：模糊度 + 玻璃强度 + 自定义图片 + 随机图源多选，实时生效。 */
    /** 背景与玻璃：模糊度 / 透明度 / 玻璃强度 / 各部件参数。 */
    @Composable
    private fun getGlassGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val backdropBlur by GlassPrefs.backdropBlurDp.collectAsState()
        val backdropAlpha by GlassPrefs.backdropAlpha.collectAsState()
        val glassStrength by GlassPrefs.glassStrength.collectAsState()
        val partBlur by GlassPrefs.partBlur.collectAsState()
        val partAlpha by GlassPrefs.partAlpha.collectAsState()
        val partFrosted by GlassPrefs.partFrosted.collectAsState()

        return Preference.PreferenceGroup(
            title = "背景与液态玻璃",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(title = "背景封面模糊度") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "背景封面模糊度：${backdropBlur.toInt()} dp",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = backdropBlur,
                            onValueChange = { GlassPrefs.setBackdropBlurDp(context, it) },
                            valueRange = 0f..24f,
                        )
                    }
                },
                Preference.PreferenceItem.CustomPreference(title = "背景图透明度") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "背景图透明度：${(backdropAlpha * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = backdropAlpha,
                            onValueChange = { GlassPrefs.setBackdropAlpha(context, it) },
                            valueRange = 0.3f..1f,
                        )
                    }
                },
                Preference.PreferenceItem.CustomPreference(title = "液态玻璃强度") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "液态玻璃强度：${(glassStrength * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = glassStrength,
                            onValueChange = { GlassPrefs.setGlassStrength(context, it) },
                            valueRange = 0f..1f,
                        )
                    }
                },
                Preference.PreferenceItem.CustomPreference(title = "各部件玻璃参数") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            "每个部件独立设置：上滑杆=模糊度，下滑杆=玻璃不透明度；颜色在「颜色」页单独调整。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        GlassPart.entries.forEach { part ->
                            val blurValue = partBlur[part.key] ?: part.defaultBlurDp
                            val alphaValue = partAlpha[part.key] ?: part.defaultAlpha
                            val frosted = partFrosted[part.key] ?: true
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(part.label, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "模糊 ${blurValue.toInt()}dp · 不透明度 ${(alphaValue * 100).toInt()}%",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        if (frosted) "磨砂" else "无磨砂",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                    Switch(
                                        checked = frosted,
                                        onCheckedChange = { GlassPrefs.setPartFrosted(context, part, it) },
                                    )
                                }
                                Slider(
                                    value = blurValue,
                                    onValueChange = { GlassPrefs.setPartBlur(context, part, it) },
                                    valueRange = 0f..32f,
                                )
                                Slider(
                                    value = alphaValue,
                                    onValueChange = { GlassPrefs.setPartAlpha(context, part, it) },
                                    valueRange = 0f..1f,
                                )
                            }
                        }
                    }
                },
            ),
        )
    }

    /** 背景图：全局自定义 / 每页自定义 / 随机图源。 */
    @Composable
    private fun getBackdropGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val customPath by BackdropSources.customPath.collectAsState()
        val perPage by BackdropSources.perPage.collectAsState()
        val enabledIds by BackdropSources.enabled.collectAsState()
        var pickingPage by remember { mutableStateOf<String?>(null) }
        val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
            val pageKey = pickingPage
            if (uri != null && pageKey != null) {
                runCatching {
                    val dest = java.io.File(context.filesDir, "backdrop_${pageKey}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (pageKey == "global") {
                        BackdropSources.setCustomPath(context, dest.absolutePath)
                    } else {
                        BackdropSources.setPagePath(context, pageKey, dest.absolutePath)
                    }
                    AppBackdropState.refresh("settings")
                }
                pickingPage = null
            }
        }

        val pageLabel = mapOf(
            "browse" to "分类页",
            "library" to "书架页",
            "history" to "历史页",
            "profile" to "我的页",
            "settings" to "设置页",
            "search" to "搜索页",
        )

        val sourceItems = BackdropSources.ALL.map { source ->
            Preference.PreferenceItem.CustomPreference(title = source.name) {
                GlassSwitchRow(
                    title = source.name,
                    subtitle = source.url,
                    checked = source.id in enabledIds,
                    onCheckedChanged = { on ->
                        BackdropSources.setEnabled(context, source.id, on)
                        AppBackdropState.refresh("settings")
                    },
                )
            }
        }

        return Preference.PreferenceGroup(
            title = "背景图",
            preferenceItems = (
                persistentListOf(
                    Preference.PreferenceItem.CustomPreference(title = "自定义背景图片") {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                if (customPath.isNullOrBlank()) {
                                    "选择一张本地图片作为全局背景；设置后优先于随机图源。"
                                } else {
                                    "已设置自定义背景（优先于随机图源）。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    pickingPage = "global"
                                    pickImageLauncher.launch("image/*")
                                }) { Text("自定义图片") }
                                if (!customPath.isNullOrBlank()) {
                                    TextButton(onClick = {
                                        runCatching { java.io.File(customPath).delete() }
                                        BackdropSources.setCustomPath(context, null)
                                        AppBackdropState.refresh("settings")
                                    }) { Text("清除自定义", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    },
                    Preference.PreferenceItem.CustomPreference(title = "分页自定义背景") {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            BackdropSources.PAGE_KEYS.forEach { key ->
                                val path = perPage[key]
                                GlassPreferenceRow(
                                    title = pageLabel[key] ?: key,
                                    subtitle = if (path.isNullOrBlank()) "跟随全局/随机源" else "已设置本页背景",
                                    widget = {
                                        TextButton(onClick = {
                                            pickingPage = key
                                            pickImageLauncher.launch("image/*")
                                        }) { Text("自定义图片") }
                                        if (!path.isNullOrBlank()) {
                                            TextButton(onClick = {
                                                runCatching { java.io.File(path).delete() }
                                                BackdropSources.setPagePath(context, key, null)
                                                AppBackdropState.refresh("settings")
                                            }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                                        }
                                    },
                                )
                            }
                        }
                    },
                    Preference.PreferenceItem.CustomPreference(title = "随机图源（可多选）") {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(
                                "未设置自定义图片时，从开启的随机图源里随机取图；全部关闭则使用默认渐变背景。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    },
                ) + sourceItems
                ).toImmutableList(),
        )
    }

    @Composable
    private fun getThemeGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val themeModePref = uiPreferences.themeMode()
        val themeMode by themeModePref.collectAsState()

        val appThemePref = uiPreferences.appTheme()
        val appTheme by appThemePref.collectAsState()

        val amoledPref = uiPreferences.themeDarkAmoled()
        val amoled by amoledPref.collectAsState()
        val manwaAccent = rememberContentPlatformAccent(ContentPlatform.MANWA)
        val picacgAccent = rememberContentPlatformAccent(ContentPlatform.PICACG)
        val jmAccent = rememberContentPlatformAccent(ContentPlatform.JM)
        var editingAccent by remember { mutableStateOf<ContentPlatform?>(null) }

        editingAccent?.let { platform ->
            PlatformAccentDialog(platform = platform, onDismiss = { editingAccent = null })
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_theme),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_app_theme),
                ) {
                    Column {
                        AppThemeModePreferenceWidget(
                            value = themeMode,
                            onItemClick = {
                                themeModePref.set(it)
                            },
                        )

                        AppThemePreferenceWidget(
                            value = appTheme,
                            amoled = amoled,
                            onItemClick = { appThemePref.set(it) },
                        )
                    }
                },
                Preference.PreferenceItem.CustomPreference(title = stringResource(KMR.strings.zy_platform_manwa_accent)) {
                    PlatformAccentPreferenceRow(
                        title = stringResource(KMR.strings.zy_platform_manwa_accent),
                        color = manwaAccent,
                        onEditClick = { editingAccent = ContentPlatform.MANWA },
                        onPaletteClick = { navigator.push(PlatformAccentPaletteScreen(ContentPlatform.MANWA)) },
                    )
                },
                Preference.PreferenceItem.CustomPreference(title = stringResource(KMR.strings.content_platform_picacg_accent)) {
                    PlatformAccentPreferenceRow(
                        title = stringResource(KMR.strings.content_platform_picacg_accent),
                        color = picacgAccent,
                        onEditClick = { editingAccent = ContentPlatform.PICACG },
                        onPaletteClick = { navigator.push(PlatformAccentPaletteScreen(ContentPlatform.PICACG)) },
                    )
                },
                Preference.PreferenceItem.CustomPreference(title = stringResource(KMR.strings.zy_platform_jm_accent)) {
                    PlatformAccentPreferenceRow(
                        title = stringResource(KMR.strings.zy_platform_jm_accent),
                        color = jmAccent,
                        onEditClick = { editingAccent = ContentPlatform.JM },
                        onPaletteClick = { navigator.push(PlatformAccentPaletteScreen(ContentPlatform.JM)) },
                    )
                },
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(KMR.strings.pref_custom_color),
                    subtitle = stringResource(KMR.strings.custom_color_description),
                    enabled = appTheme == AppTheme.CUSTOM,
                    onClick = { navigator.push(AppCustomThemeColorPickerScreen()) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.customThemeStyle(),
                    entries = PaletteStyle.entries
                        .associateWith {
                            when (it) {
                                PaletteStyle.TonalSpot ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_tonalspot)
                                PaletteStyle.Neutral ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_neutral)
                                PaletteStyle.Vibrant ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_vibrant)
                                PaletteStyle.Expressive ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_expressive)
                                PaletteStyle.Rainbow ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_rainbow)
                                PaletteStyle.FruitSalad ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_fruitsalad)
                                PaletteStyle.Monochrome ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_monochrome)
                                PaletteStyle.Fidelity ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_fidelity)
                                PaletteStyle.Content ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_content)
                            }
                        }
                        .toImmutableMap(),
                    title = stringResource(KMR.strings.pref_custom_theme_style),
                    enabled = appTheme == AppTheme.CUSTOM,
                    onValueChanged = { true },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = amoledPref,
                    title = stringResource(MR.strings.pref_dark_theme_pure_black),
                    enabled = themeMode != ThemeMode.LIGHT,
                    onValueChanged = {
                        true
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getMangaInfoThemeGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val mangaInfoThemeCoverBased by remember {
            Injekt.get<UiPreferences>().themeCoverBased().asState(scope)
        }
        return Preference.PreferenceGroup(
            title = stringResource(KMR.strings.pref_manga_info),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.themeCoverBased(),
                    title = stringResource(KMR.strings.pref_theme_cover_based),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.themeCoverBasedStyle(),
                    entries = PaletteStyle.entries
                        .associateWith {
                            when (it) {
                                PaletteStyle.TonalSpot ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_tonalspot)
                                PaletteStyle.Neutral ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_neutral)
                                PaletteStyle.Vibrant ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_vibrant)
                                PaletteStyle.Expressive ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_expressive)
                                PaletteStyle.Rainbow ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_rainbow)
                                PaletteStyle.FruitSalad ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_fruitsalad)
                                PaletteStyle.Monochrome ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_monochrome)
                                PaletteStyle.Fidelity ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_fidelity)
                                PaletteStyle.Content ->
                                    stringResource(KMR.strings.pref_theme_cover_based_style_content)
                            }
                        }
                        .toImmutableMap(),
                    title = stringResource(KMR.strings.pref_theme_cover_based_style),
                    enabled = mangaInfoThemeCoverBased,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.usePanoramaCoverMangaInfo(),
                    title = stringResource(KMR.strings.pref_panorama_cover),
                    subtitle = stringResource(KMR.strings.pref_panorama_cover_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.topAlignCover(),
                    title = stringResource(KMR.strings.pref_top_align_cover),
                    subtitle = stringResource(KMR.strings.pref_top_align_cover_summary),
                ),
            ),
        )
    }

    @Composable
    private fun getDisplayGroup(
        uiPreferences: UiPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val now = remember { LocalDate.now() }

        val dateFormat by uiPreferences.dateFormat().collectAsState()
        val formattedNow = remember(dateFormat) {
            UiPreferences.dateFormat(dateFormat).format(now)
        }

        val currentLanguage = remember {
            AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: ""
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_app_language) +
                        if (currentLanguage.isNotEmpty() && !currentLanguage.startsWith("en")) " (App Language)" else "",
                    onClick = { navigator.push(AppLanguageScreen()) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.tabletUiMode(),
                    entries = TabletUiMode.entries
                        .associateWith { stringResource(it.titleRes) }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_tablet_ui_mode),
                    onValueChanged = {
                        context.toast(MR.strings.requires_app_restart)
                        true
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = uiPreferences.dateFormat(),
                    entries = DateFormats
                        .associateWith {
                            val formattedDate = UiPreferences.dateFormat(it).format(now)
                            "${it.ifEmpty { stringResource(MR.strings.label_default) }} ($formattedDate)"
                        }
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_date_format),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.relativeTime(),
                    title = stringResource(MR.strings.pref_relative_format),
                    subtitle = stringResource(
                        MR.strings.pref_relative_format_summary,
                        stringResource(MR.strings.relative_time_today),
                        formattedNow,
                    ),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.imagesInDescription(),
                    title = stringResource(MR.strings.pref_display_images_description),
                ),
            ),
        )
    }

    @Composable
    fun getNavbarGroup(uiPreferences: UiPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(tachiyomi.i18n.sy.SYMR.strings.pref_category_navbar),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.showNavUpdates(),
                    title = stringResource(tachiyomi.i18n.sy.SYMR.strings.pref_hide_updates_button),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.showNavHistory(),
                    title = stringResource(tachiyomi.i18n.sy.SYMR.strings.pref_hide_history_button),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = uiPreferences.bottomBarLabels(),
                    title = stringResource(tachiyomi.i18n.sy.SYMR.strings.pref_show_bottom_bar_labels),
                ),
            ),
        )
    }
}

private class PlatformAccentPaletteScreen(
    private val platform: ContentPlatform,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val currentColor = rememberContentPlatformAccent(platform)
        val controller = rememberColorPickerController()
        val title = when (platform) {
            ContentPlatform.MANWA -> stringResource(KMR.strings.zy_platform_manwa_accent)
            ContentPlatform.PICACG -> stringResource(KMR.strings.content_platform_picacg_accent)
            ContentPlatform.JM -> stringResource(KMR.strings.zy_platform_jm_accent)
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = title,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(modifier = Modifier.padding(contentPadding)) {
                ThemeColorPickerWidget(
                    controller = controller,
                    initialColor = currentColor,
                    onItemClick = { color, _ ->
                        ContentPlatformAccentPrefs.set(platform, color)
                        navigator.pop()
                    },
                )
            }
        }
    }
}

@Composable
private fun PlatformAccentPreferenceRow(
    title: String,
    color: Color,
    onEditClick: () -> Unit,
    onPaletteClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ContentPlatformAccentPrefs.format(color),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).clickable(onClick = onEditClick),
            )
            Spacer(Modifier.size(24.dp).background(color, CircleShape).clickable(onClick = onEditClick))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onPaletteClick) { Text("个性调色板") }
        }
    }
}

@Composable
private fun PlatformAccentDialog(platform: ContentPlatform, onDismiss: () -> Unit) {
    val current = rememberContentPlatformAccent(platform)
    var hex by remember(platform) { mutableStateOf(ContentPlatformAccentPrefs.format(current)) }
    val parsed = ContentPlatformAccentPrefs.parseColor(hex)
    val platformName = when (platform) {
        ContentPlatform.MANWA -> stringResource(KMR.strings.content_platform_manwa)
        ContentPlatform.PICACG -> stringResource(KMR.strings.content_platform_picacg)
        ContentPlatform.JM -> stringResource(KMR.strings.content_platform_jm_full)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$platformName 标签颜色") },
        text = {
            Column {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("ARGB 色值") },
                    placeholder = { Text("#FF248A48") },
                    supportingText = { Text("支持 #RRGGBB 或 #AARRGGBB") },
                    singleLine = true,
                    isError = hex.isNotBlank() && parsed == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("预览", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Spacer(Modifier.size(28.dp).background(parsed ?: current, CircleShape))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = parsed != null,
                onClick = {
                    parsed?.let { ContentPlatformAccentPrefs.set(platform, it) }
                    onDismiss()
                },
            ) { Text("应用") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    ContentPlatformAccentPrefs.reset(platform)
                    onDismiss()
                }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private val DateFormats = listOf(
    "", // Default
    "MM/dd/yy",
    "dd/MM/yy",
    "yyyy-MM-dd",
    "dd MMM yyyy",
    "MMM dd, yyyy",
)


/** 颜色选择目标：标题 + 当前值 + 应用回调。 */
private data class AppearanceColorTarget(
    val title: String,
    val current: Color,
    val apply: (android.content.Context, Color) -> Unit,
    val reset: ((android.content.Context) -> Unit)? = null,
)

/** 外观页里的一行颜色设置：左侧标题/颜色代码，右侧色块（可附带额外操作）。 */
@Composable
private fun AppearanceColorRow(
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    com.zycomic.app.ui.components.GlassPreferenceRow(
        title = title,
        subtitle = subtitle,
        onPreferenceClick = onClick,
        widget = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                action?.invoke()
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            shape = CircleShape,
                        ),
                )
            }
        },
    )
}

/** 外观页顶部标签栏：玻璃胶囊容器 + 选中白玻璃，与平台切换器同一套语言。 */
@Composable
private fun AppearanceTabBar(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val accent by AppearanceColors.uiAccent.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .glassSurface(
                shape = RoundedCornerShape(50),
                radius = 999.dp,
                part = GlassPart.TAB,
                stroke = false,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (isSelected) {
                            Modifier.glassSurface(
                                shape = RoundedCornerShape(50),
                                radius = 999.dp,
                                part = GlassPart.TAB_SELECTED,
                                stroke = false,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) {
                        accent ?: MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
