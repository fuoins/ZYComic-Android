package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.RouteManager
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onClose: () -> Unit,
    onOpenSpeedTest: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by RouteManager.speedTestSnapshot.collectAsState()
    val availableDomains = buildAvailableDomainText(
        lineCandidates = RouteManager.lineHosts,
        imageCandidates = RouteManager.imgDomains,
        lineResults = snapshot.lineDelays,
        imageResults = snapshot.imgDelays,
        imageVerdicts = snapshot.imgVerdicts,
    )
    val domainCount = availableDomains.lineSequence().count(String::isNotBlank)

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(availableDomains)
            } ?: error("无法打开导出文件")
        }.onSuccess {
            Toast.makeText(context, "已导出 $domainCount 个可用域名", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导出失败：${it.message ?: "请重试"}", Toast.LENGTH_LONG).show()
        }
    }

    fun requireAvailableDomains(action: (String) -> Unit) {
        if (availableDomains.isBlank()) {
            Toast.makeText(context, "暂无当前配置的测速成功域名，请先完成测速", Toast.LENGTH_SHORT).show()
        } else {
            action(availableDomains)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("开发者设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "可用域名取自当前配置最近一次完整测速结果；导出和复制不会重新测速。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
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
                    title = "导出可用域名",
                    subtitle = if (domainCount == 0) "暂无可用域名" else "保存为纯文本文件（$domainCount 行域名）",
                    icon = Icons.Outlined.Download,
                    onPreferenceClick = {
                        requireAvailableDomains { exportLauncher.launch("available_domains.txt") }
                    },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "复制可用域名",
                    subtitle = if (domainCount == 0) "暂无可用域名" else "复制与导出文件相同的纯文本内容",
                    icon = Icons.Outlined.ContentCopy,
                    onPreferenceClick = {
                        requireAvailableDomains { context.copyToClipboard("可用域名", it) }
                    },
                )
            }
        }
    }
}
