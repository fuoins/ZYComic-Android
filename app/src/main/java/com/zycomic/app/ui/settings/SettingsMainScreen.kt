package com.zycomic.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

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
    onOpenTagBlock: () -> Unit,
    onBlockGayTags: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    onOpenDataStorage: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState)

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
                TextPreferenceWidget(
                    title = "阅读器",
                    icon = Icons.AutoMirrored.Outlined.ChromeReaderMode,
                    onPreferenceClick = onOpenReader,
                )
            }
            item { HorizontalDivider() }
            item {
                TextPreferenceWidget(
                    title = "标签屏蔽",
                    subtitle = "管理屏蔽的标签",
                    icon = Icons.Outlined.Bookmark,
                    onPreferenceClick = onOpenTagBlock,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "gay标签一键屏蔽",
                    subtitle = "自动屏蔽所有女性向/gay标签（${SettingsViewModel.GAY_TAGS.size}个）",
                    icon = Icons.Outlined.Block,
                    onPreferenceClick = onBlockGayTags,
                )
            }
            item { HorizontalDivider() }
            item {
                TextPreferenceWidget(
                    title = "测速日志",
                    subtitle = "查看线路/图源延迟，手动测速",
                    icon = Icons.Outlined.Speed,
                    onPreferenceClick = onOpenSpeedTest,
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
                    title = "关于",
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onOpenAbout,
                )
            }
        }
    }
}
