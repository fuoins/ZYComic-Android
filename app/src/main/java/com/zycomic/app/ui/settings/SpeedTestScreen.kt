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
    val currentImgIdx by vm.currentImgIndex.collectAsState()
    val autoSel by vm.autoSelectEnabled.collectAsState()
    val useFastestImg by vm.useFastestImgForAll.collectAsState()
    val followVpn by vm.followSystemVpn.collectAsState()
    val remoteChecking by vm.remoteChecking.collectAsState()
    val remoteInfo by vm.remoteInfo.collectAsState()

    // 仅用于展示：线路、图源两组各自按测速延迟升序（快→慢）。
    // 未测(null)/超时(MAX_VALUE)统一沉底，并列时按原始 index 稳定排序；
    // 排序结果的元素仍是“原始 index”，点击/选中/Toast 等交互全部沿用原 index，不受排序影响。
    val lineOrder = remember(lineDelays) {
        RouteManager.lineHosts.indices.sortedWith(
            compareBy(
                { idx -> lineDelays[idx]?.takeIf { ms -> ms < Long.MAX_VALUE } ?: Long.MAX_VALUE },
                { idx -> idx },
            ),
        )
    }
    val imgOrder = remember(imgDelays) {
        RouteManager.imgDomains.indices.sortedWith(
            compareBy(
                { idx -> imgDelays[idx]?.takeIf { ms -> ms < Long.MAX_VALUE } ?: Long.MAX_VALUE },
                { idx -> idx },
            ),
        )
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
                    val pinned by vm.imgPinned.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前图源", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (pinned.isNullOrBlank()) "自动：由本轮测速结果选最快且未失败的图源"
                                else "已固定：$pinned（不会被测速结果覆盖）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!pinned.isNullOrBlank()) {
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
                                if (followVpn) "已开启：系统 VPN 开则图片走 VPN、关则直连"
                                else "已关闭：强制直连（硬编码IP+去SNI），即使开着系统 VPN 也不走 VPN，用于抓包调试",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = followVpn, onCheckedChange = { vm.setFollowSystemVpn(it) })
                    }
                }
                item {
                    Text(
                        "按测速延迟升序（快→慢），未测/连不上/无图项排在最后；连不上=网络不通，无图=能连但取不到图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
                item {
                    Text("线路（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                items(lineOrder) { index ->
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
                items(imgOrder) { index ->
                    SpeedRow(
                        index = index,
                        host = RouteManager.imgDomains[index],
                        delay = imgDelays[index],
                        verdict = imgVerdicts[index],
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
            Text(" ←", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
