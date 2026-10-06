package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zycomic.app.net.DevConfig
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlin.io.walkTopDown
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataStorageScreen(
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zycomic_data", android.content.Context.MODE_PRIVATE) }
    var autoClear by remember { mutableStateOf(prefs.getBoolean("auto_clear_chapter_cache", true)) }

    fun coilDiskSize(): String = try {
        val dir = coil3.SingletonImageLoader.get(context).diskCache?.directory?.toFile() ?: return "0 B"
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        "%.1f MB".format(bytes / 1024.0 / 1024.0)
    } catch (_: Exception) { "未知" }

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
                    title = "图片缓存",
                    subtitle = "Coil 磁盘缓存占用：${coilDiskSize()}",
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
                    title = "清除图片缓存",
                    subtitle = "清除内存+磁盘图片缓存，不影响收藏和历史",
                    onPreferenceClick = {
                        DevConfig.clearImageCaches()
                        Toast.makeText(context, "图片缓存已清除", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "启动时清除图片缓存",
                    subtitle = "每次启动 App 自动清空图片缓存",
                    widget = {
                        Switch(
                            checked = autoClear,
                            onCheckedChange = {
                                autoClear = it
                                prefs.edit().putBoolean("auto_clear_chapter_cache", it).apply()
                            },
                        )
                    },
                )
            }
        }
    }
}
