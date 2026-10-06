package com.zycomic.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.RouteManager
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    vm: SettingsViewModel,
    onClose: () -> Unit,
) {
    val testing by vm.testing.collectAsState()
    val lineDelays by vm.lineDelays.collectAsState()
    val imgDelays by vm.imgDelays.collectAsState()
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val currentImgIdx by vm.currentImgIndex.collectAsState()
    val autoSel by vm.autoSelectEnabled.collectAsState()
    val useFastestImg by vm.useFastestImgForAll.collectAsState()
    val updateTime by vm.configUpdateTime.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("测速日志") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            Text(
                "网络配置更新时间：$updateTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ScrollbarLazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动选择最快线路", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (autoSel) "已开启，当前自动选中线路 ${currentLineIdx + 1}（手动点击线路将关闭自动）"
                                else "已关闭，纯手动模式",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoSel, onCheckedChange = { vm.setAutoSelectEnabled(it) })
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("封面和章节用测速最快图源", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (useFastestImg) "已开启：封面和章节图片都用测速最快图源"
                                else "已关闭：封面用当前图源，章节用服务端推荐",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = useFastestImg, onCheckedChange = { vm.setUseFastestImgForAll(it) })
                    }
                }
                item {
                    Text("线路（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                items(RouteManager.lineHosts.size) { index ->
                    SpeedRow(
                        index = index,
                        host = RouteManager.lineHosts[index].removePrefix("https://").removePrefix("http://").substringBefore('/'),
                        delay = lineDelays[index],
                        isSelected = index == currentLineIdx,
                        onClick = { vm.selectLine(index) },
                    )
                }

                item {
                    Text("图源（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                items(RouteManager.imgDomains.size) { index ->
                    SpeedRow(
                        index = index,
                        host = RouteManager.imgDomains[index],
                        delay = imgDelays[index],
                        isSelected = index == currentImgIdx,
                        onClick = { vm.selectImgHost(index) },
                    )
                }

                item {
                    Button(
                        onClick = { vm.runSpeedTest() },
                        enabled = !testing,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(if (testing) "测速中..." else "重新测速")
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedRow(
    index: Int,
    host: String,
    delay: Long?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}. $host",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = "HTTP:${formatDelay(delay)}",
            style = MaterialTheme.typography.bodySmall,
            color = when {
                delay == null -> MaterialTheme.colorScheme.onSurfaceVariant
                delay == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                delay < 300 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (isSelected) {
            Text(" ←", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun formatDelay(delay: Long?): String = when {
    delay == null -> "--"
    delay == Long.MAX_VALUE -> "超时"
    else -> "${delay}ms"
}
