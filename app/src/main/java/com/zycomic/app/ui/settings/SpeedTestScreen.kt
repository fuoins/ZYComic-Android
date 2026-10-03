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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.RouteManager
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

/**
 * 测速日志页面：线路/图源延迟列表 + 重新测速 + 更新网络配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    vm: SettingsViewModel,
    onClose: () -> Unit,
) {
    val testing by vm.testing.collectAsState()
    val lineDelays by vm.lineDelays.collectAsState()
    val imgDelays by vm.imgDelays.collectAsState()
    val ipDelays by vm.ipDelays.collectAsState()
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val currentImgIdx by vm.currentImgIndex.collectAsState()
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

            var expandedHosts by remember { mutableStateOf<Set<String>>(emptySet()) }

            ScrollbarLazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Text("线路（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                items(RouteManager.LINE_HOSTS.size) { index ->
                    SpeedLineRow(
                        index = index,
                        host = RouteManager.LINE_HOSTS[index].removePrefix("https://").removePrefix("http://").substringBefore('/'),
                        delay = lineDelays[index],
                        ipMap = ipDelays[RouteManager.LINE_HOSTS[index].removePrefix("https://").removePrefix("http://").substringBefore('/')],
                        isSelected = index == currentLineIdx,
                        expandKey = "line_$index",
                        expandedHosts = expandedHosts,
                        onToggleExpand = { key ->
                            expandedHosts = if (expandedHosts.contains(key)) expandedHosts - key else expandedHosts + key
                        },
                        onClick = { vm.selectLine(index) },
                    )
                }

                item {
                    Text("图源（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                items(RouteManager.IMG_DOMAINS.size) { index ->
                    SpeedLineRow(
                        index = index,
                        host = RouteManager.IMG_DOMAINS[index],
                        delay = imgDelays[index],
                        ipMap = ipDelays[RouteManager.IMG_DOMAINS[index]],
                        isSelected = index == currentImgIdx,
                        expandKey = "img_$index",
                        expandedHosts = expandedHosts,
                        onToggleExpand = { key ->
                            expandedHosts = if (expandedHosts.contains(key)) expandedHosts - key else expandedHosts + key
                        },
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

                item {
                    TextPreferenceWidget(
                        title = "更新网络配置(DoH)",
                        subtitle = "通过 DNS over HTTPS 刷新 IP 列表并重启代理",
                        onPreferenceClick = { vm.updateNetworkConfig() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedLineRow(
    index: Int,
    host: String,
    delay: Long?,
    ipMap: Map<String, Long>?,
    isSelected: Boolean,
    expandKey: String,
    expandedHosts: Set<String>,
    onToggleExpand: (String) -> Unit,
    onClick: () -> Unit,
) {
    val fastest = ipMap?.filterValues { it < Long.MAX_VALUE }?.minByOrNull { it.value }
    val isExpanded = expandedHosts.contains(expandKey)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        androidx.compose.foundation.layout.Row(
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
            if (fastest != null) {
                Text(
                    text = " 最快IP:${fastest.key}(${fastest.value}ms)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isSelected) {
                Text(" ←", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!ipMap.isNullOrEmpty()) {
                Text(
                    text = if (isExpanded) " ▲" else " ▼",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onToggleExpand(expandKey) },
                )
            }
        }
        if (isExpanded && !ipMap.isNullOrEmpty()) {
            ipMap.forEach { (ip, tcpDelay) ->
                Text(
                    text = "  $ip: ${formatDelay(tcpDelay)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        tcpDelay == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                        tcpDelay < 300 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 40.dp, top = 1.dp),
                )
            }
        }
    }
}

/** 延迟显示：未测="--"，失败="超时"，否则="123ms" */
private fun formatDelay(delay: Long?): String = when {
    delay == null -> "--"
    delay == Long.MAX_VALUE -> "超时"
    else -> "${delay}ms"
}
