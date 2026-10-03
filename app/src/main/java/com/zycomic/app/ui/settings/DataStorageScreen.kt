package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.zycomic.app.net.DevConfig
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

/**
 * 数据与存储页面：阶段1显示静态信息项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataStorageScreen(
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("数据与存储") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding) {
            item {
                TextPreferenceWidget(
                    title = "应用大小",
                    subtitle = "暂未统计",
                )
            }
            item {
                TextPreferenceWidget(
                    title = "图片缓存",
                    subtitle = "由 Coil 自动管理",
                )
            }
            item {
                TextPreferenceWidget(
                    title = "数据库",
                    subtitle = "收藏、历史记录",
                )
            }
            item {
                TextPreferenceWidget(
                    title = "清除缓存",
                    subtitle = "清除图片缓存，不影响收藏和历史",
                    onPreferenceClick = {
                        DevConfig.clearImageCaches()
                        Toast.makeText(context, "图片缓存已清除", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}
