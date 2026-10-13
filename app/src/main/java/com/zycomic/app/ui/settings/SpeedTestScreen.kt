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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.ImageEndpointSelectionPolicy
import com.zycomic.app.net.RouteManager
import com.zycomic.app.net.SpeedTester
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
    val imgVerdicts by vm.imgVerdicts.collectAsState()
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val currentLineIp by vm.currentLineIp.collectAsState()
    val currentImgIdx by vm.currentImgIndex.collectAsState()
    val currentImgIp by vm.currentImgIp.collectAsState()
    val pinnedImg by vm.imgPinned.collectAsState()
    val autoSel by vm.autoSelectEnabled.collectAsState()
    val useFastestImg by vm.useFastestImgForAll.collectAsState()
    val followVpn by vm.followSystemVpn.collectAsState()
    val remoteChecking by vm.remoteChecking.collectAsState()
    val remoteInfo by vm.remoteInfo.collectAsState()

    // 每个域名/IP独立排序；不可用端点沉底并保留在测速日志中。
    val lineOrder = remember(lineDelays) {
        lineDelays.entries.sortedWith(compareBy({ it.value.takeIf { ms -> ms < Long.MAX_VALUE } ?: Long.MAX_VALUE }, { it.key.domain }, { it.key.ip }))
    }
    val imgOrder = remember(imgDelays, imgVerdicts) {
        imgDelays.entries.sortedWith(compareBy({ if (imgVerdicts[it.key] == SpeedTester.ImgVerdict.OK) it.value else Long.MAX_VALUE }, { it.key.domain }, { it.key.ip }))
    }
    val successfulImgEndpoints = remember(imgOrder, imgVerdicts) {
        imgOrder.filter { it.value < Long.MAX_VALUE && imgVerdicts[it.key] == SpeedTester.ImgVerdict.OK }.map { it.key }
    }
    val currentImgEndpoint = remember(imgOrder, imgVerdicts, currentImgIdx, currentImgIp) {
        val domain = RouteManager.imgDomains.getOrNull(currentImgIdx)
        ImageEndpointSelectionPolicy.currentEndpoint(successfulImgEndpoints, domain, currentImgIp)
            ?: ImageEndpointSelectionPolicy.currentEndpoint(imgOrder.map { it.key }, domain, currentImgIp)
    }

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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    remoteInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = !remoteChecking,
                    onClick = { vm.checkRemoteConfig() },
                ) {
                    Text(if (remoteChecking) "检查中…" else "检查更新")
                }
            }

            ScrollbarLazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动选择最快线路", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (autoSel) {
                                    "已开启，当前自动选中线路 ${currentLineIdx + 1}（手动点击线路将关闭自动）"
                                } else {
                                    "已关闭，纯手动模式"
                                },
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
                                if (useFastestImg) {
                                    "已开启：封面和章节图片都用测速最快图源"
                                } else {
                                    "已关闭：封面用当前图源，章节用服务端推荐"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = useFastestImg, onCheckedChange = { vm.setUseFastestImgForAll(it) })
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前图源", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (pinnedImg.isNullOrBlank()) {
                                    "自动：由测速结果选择最快图源"
                                } else {
                                    "已固定：$pinnedImg（不会被测速结果覆盖）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!pinnedImg.isNullOrBlank()) {
                            Button(onClick = { vm.resetImgHostToAuto() }) { Text("恢复自动") }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动跟随系统 VPN", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (followVpn) {
                                    "已开启：系统 VPN 开则图片走 VPN、关则直连"
                                } else {
                                    "已关闭：强制直连（硬编码IP+去SNI），即使开着系统 VPN 也不走 VPN，用于抓包调试"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = followVpn, onCheckedChange = { vm.setFollowSystemVpn(it) })
                    }
                }
                item {
                    Text(
                        "按每个域名/IP测速延迟升序；失败端点留在日志末尾，阅读器只显示成功项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
                item {
                    Text("线路（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                items(lineOrder.withIndex().toList()) { (position, entry) ->
                    val endpoint = entry.key
                    val index = RouteManager.lineHosts.indexOfFirst {
                        runCatching { java.net.URI(it).host.equals(endpoint.domain, ignoreCase = true) }.getOrDefault(false)
                    }
                    SpeedRow(
                        index = position,
                        host = "${endpoint.domain} · ${endpoint.ip}",
                        delay = entry.value,
                        isSelected = index == currentLineIdx && endpoint.ip == currentLineIp,
                        onClick = { if (index >= 0) vm.selectLine(index, endpoint.ip) },
                    )
                }

                item {
                    Text("图源（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                items(imgOrder.withIndex().toList()) { (position, entry) ->
                    val endpoint = entry.key
                    val index = RouteManager.imgDomains.indexOfFirst { it.equals(endpoint.domain, ignoreCase = true) }
                    SpeedRow(
                        index = position,
                        host = "${endpoint.domain} · ${endpoint.ip}",
                        delay = entry.value,
                        verdict = imgVerdicts[endpoint],
                        isSelected = endpoint == currentImgEndpoint,
                        onClick = { if (index >= 0) vm.selectImgHost(index, endpoint.ip) },
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
    verdict: SpeedTester.ImgVerdict? = null,
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
            text = "HTTP:${formatDelay(delay, verdict)}",
            style = MaterialTheme.typography.bodySmall,
            color = when {
                verdict == SpeedTester.ImgVerdict.OK && (delay ?: 0) < 300 -> MaterialTheme.colorScheme.primary
                verdict != null && verdict != SpeedTester.ImgVerdict.OK -> MaterialTheme.colorScheme.error
                delay == null -> MaterialTheme.colorScheme.onSurfaceVariant
                delay == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                delay < 300 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (isSelected) {
            Text(" ✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 延迟显示。图源行带 [verdict] 时区分三种曾经都叫"超时"的情况：
 * 连不上（网络层就没通）、无图（链路通但拿不到图片对象）、未测（这一轮没探到它）。
 */
private fun formatDelay(delay: Long?, verdict: SpeedTester.ImgVerdict? = null): String = when {
    verdict == SpeedTester.ImgVerdict.UNREACHABLE -> "连不上"
    verdict == SpeedTester.ImgVerdict.NO_IMAGE -> "无图"
    delay == null -> "未测"
    delay == Long.MAX_VALUE -> "超时"
    else -> "${delay}ms"
}
