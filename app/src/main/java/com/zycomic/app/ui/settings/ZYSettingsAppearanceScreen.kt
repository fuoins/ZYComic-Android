package com.zycomic.app.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.materialkolor.PaletteStyle
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformAccentPrefs
import com.zycomic.app.platform.rememberContentPlatformAccent
import eu.kanade.core.preference.asState
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.screen.appearance.AppCustomThemeColorPickerScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppLanguageScreen
import eu.kanade.presentation.more.settings.widget.AppThemeModePreferenceWidget
import eu.kanade.presentation.more.settings.widget.AppThemePreferenceWidget
import eu.kanade.presentation.more.settings.widget.ThemeColorPickerWidget
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
            getGlassGroup(),
        )
    }

    /** 背景与液态玻璃参数：模糊度 + 玻璃强度 + 自定义图片 + 随机图源多选，实时生效。 */
    @Composable
    private fun getGlassGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val backdropBlur by com.zycomic.app.ui.components.GlassPrefs.backdropBlurDp.collectAsState()
        val backdropMask by com.zycomic.app.ui.components.GlassPrefs.backdropMask.collectAsState()
        val glassStrength by com.zycomic.app.ui.components.GlassPrefs.glassStrength.collectAsState()
        val customPath by com.zycomic.app.ui.components.BackdropSources.customPath.collectAsState()
        val enabledIds by com.zycomic.app.ui.components.BackdropSources.enabled.collectAsState()
        val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
            if (uri != null) {
                runCatching {
                    val dest = java.io.File(context.filesDir, "backdrop_custom.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out) }
                    }
                    com.zycomic.app.ui.components.BackdropSources.setCustomPath(context, dest.absolutePath)
                    com.zycomic.app.ui.components.AppBackdropState.refresh()
                }
            }
        }

        val sourceItems = com.zycomic.app.ui.components.BackdropSources.ALL.map { source ->
            Preference.PreferenceItem.CustomPreference(title = source.name) {
                com.zycomic.app.ui.components.GlassSwitchRow(
                    title = source.name,
                    subtitle = source.url,
                    checked = source.id in enabledIds,
                    onCheckedChanged = { on ->
                        com.zycomic.app.ui.components.BackdropSources.setEnabled(context, source.id, on)
                        com.zycomic.app.ui.components.AppBackdropState.refresh()
                    },
                )
            }
        }

        return Preference.PreferenceGroup(
            title = "背景与液态玻璃",
            preferenceItems = (
                persistentListOf(
                    Preference.PreferenceItem.CustomPreference(title = "背景封面模糊度") {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "背景封面模糊度：${backdropBlur.toInt()} dp",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Slider(
                                value = backdropBlur,
                                onValueChange = {
                                    com.zycomic.app.ui.components.GlassPrefs.setBackdropBlurDp(context, it)
                                },
                                valueRange = 0f..24f,
                            )
                        }
                    },
                    Preference.PreferenceItem.CustomPreference(title = "背景遮罩强度") {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "背景遮罩强度：${(backdropMask * 100).toInt()}%（顶部与底部的渐隐压色）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Slider(
                                value = backdropMask,
                                onValueChange = {
                                    com.zycomic.app.ui.components.GlassPrefs.setBackdropMask(context, it)
                                },
                                valueRange = 0f..1f,
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
                                onValueChange = {
                                    com.zycomic.app.ui.components.GlassPrefs.setGlassStrength(context, it)
                                },
                                valueRange = 0f..1f,
                            )
                        }
                    },
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
                                TextButton(onClick = { pickImageLauncher.launch("image/*") }) { Text("选择图片") }
                                if (!customPath.isNullOrBlank()) {
                                    TextButton(onClick = {
                                        runCatching { java.io.File(customPath).delete() }
                                        com.zycomic.app.ui.components.BackdropSources.setCustomPath(context, null)
                                        com.zycomic.app.ui.components.AppBackdropState.refresh()
                                    }) { Text("清除自定义", color = MaterialTheme.colorScheme.error) }
                                }
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
