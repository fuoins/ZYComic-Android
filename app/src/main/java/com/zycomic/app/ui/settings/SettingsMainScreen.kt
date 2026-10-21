package com.zycomic.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.platform.rememberContentPlatformAccent
import com.zycomic.app.ui.browse.BrowseGuideDialog
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

/**
 * 设置主页面：一级选项列表。
 *
 * 每个选项通过回调打开对应的全屏 Dialog 子页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMainScreen(
    onOpenAppearance: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onOpenDataStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    onReplayOnboarding: () -> Unit,
    guideInitialPlatform: ContentPlatform,
) {
    val context = LocalContext.current
    val platformPolicy by StartupPlatformPolicy.settingsState.collectAsState()
    var showDeveloperWarning by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState)
    val restartMessage = stringResource(KMR.strings.startup_platform_restart_toast)
    val keepOneMessage = stringResource(KMR.strings.startup_platform_keep_one_toast)

    fun updatePlatform(platform: ContentPlatform, enabled: Boolean) {
        val accepted = when (platform) {
            ContentPlatform.MANWA -> StartupPlatformPolicy.setSettingsManwaEnabled(enabled)
            ContentPlatform.PICACG -> StartupPlatformPolicy.setSettingsPicacgEnabled(enabled)
            ContentPlatform.JM -> StartupPlatformPolicy.setSettingsJmEnabled(enabled)
        }
        if (accepted && platform == ContentPlatform.PICACG && enabled) return
        val message = when {
            !accepted -> keepOneMessage
            else -> restartMessage
        }
        Toast.makeText(context, message, if (accepted) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                TopAppBar(
                    title = { Text("设置") },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            item {
                TextPreferenceWidget(
                    title = "外观",
                    icon = Icons.Outlined.Palette,
                    onPreferenceClick = onOpenAppearance,
                )
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("内容平台", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "修改后下次启动生效；至少开启一个平台。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    SettingsPlatformSwitch(stringResource(KMR.strings.startup_enable_manwa), !platformPolicy.disableManwa, ContentPlatform.MANWA) {
                        updatePlatform(ContentPlatform.MANWA, it)
                    }
                    SettingsPlatformSwitch(stringResource(KMR.strings.startup_enable_picacg), !platformPolicy.disablePicacg, ContentPlatform.PICACG) {
                        updatePlatform(ContentPlatform.PICACG, it)
                    }
                    SettingsPlatformSwitch(stringResource(KMR.strings.startup_enable_jm), !platformPolicy.disableJm, ContentPlatform.JM) {
                        updatePlatform(ContentPlatform.JM, it)
                    }
                }
            }
            item {
                TextPreferenceWidget(
                    title = "阅读器",
                    icon = Icons.AutoMirrored.Outlined.ChromeReaderMode,
                    onPreferenceClick = onOpenReader,
                )
            }
            item { HorizontalDivider() }
            item {
                TextPreferenceWidget(
                    title = "开发者设置",
                    subtitle = "测速日志与可用域名工具",
                    icon = Icons.Outlined.Code,
                    onPreferenceClick = { showDeveloperWarning = true },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "数据与存储",
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = onOpenDataStorage,
                )
            }
            item { HorizontalDivider() }
            item {
                TextPreferenceWidget(
                    title = "赞助",
                    icon = Icons.Outlined.FavoriteBorder,
                    onPreferenceClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://zy520.de5.net/juanzeng/"),
                        )
                        context.startActivity(intent)
                    },
                )
            }
            item { HorizontalDivider() }
            item {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.usage_guide_title),
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = { showGuide = true },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "新手引导",
                    subtitle = "重新查看使用说明",
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onReplayOnboarding,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "关于",
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onOpenAbout,
                )
            }
        }
    }

    if (showGuide) {
        BrowseGuideDialog(
            onDismiss = { showGuide = false },
            initialPlatform = guideInitialPlatform,
        )
    }

    if (showDeveloperWarning) {
        AlertDialog(
            onDismissRequest = { showDeveloperWarning = false },
            title = { Text("开发者设置") },
            text = { Text("如果你不知道你在干什么，请不要点击此按钮！这可能会影响漫画的正常运行。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeveloperWarning = false
                        onOpenDeveloperSettings()
                    },
                ) {
                    Text("继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeveloperWarning = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SettingsPlatformSwitch(
    title: String,
    checked: Boolean,
    platform: ContentPlatform,
    onCheckedChange: (Boolean) -> Unit,
) {
    val accent = rememberContentPlatformAccent(platform)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics(mergeDescendants = true) { contentDescription = title },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), color = accent, style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.42f),
                    checkedBorderColor = accent,
                ),
            )
        }
    }
}
