package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.repository.ReaderRepository
import com.zycomic.app.net.ImageSourceDisplayPolicy
import com.zycomic.app.net.RemoteConfigFetcher
import com.zycomic.app.net.RouteManager
import com.zycomic.app.net.SpeedTestLogJson
import com.zycomic.app.net.SpeedTester
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    vm: SettingsViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val fullSnapshot by RouteManager.completedSpeedTestSnapshot.collectAsState()
    val speedTesterRunning by SpeedTester.isRunningFlow.collectAsState()
    val autoSelecting by vm.autoSelecting.collectAsState()
    val testing by vm.testing.collectAsState()
    val lineDelays by vm.lineDelays.collectAsState()
    val imgDelays by vm.imgDelays.collectAsState()
    val imgVerdicts by vm.imgVerdicts.collectAsState()
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val currentLineIp by vm.currentLineIp.collectAsState()
    val currentImgIp = RouteManager.selectedImgIp
    val pinnedImg = RouteManager.pinnedImgDomain
    val imgPinDisplayRevision = RouteManager.imgPinDisplayRevision
    val autoSel by vm.autoSelectEnabled.collectAsState()
    val useFastestImg = RouteManager.useFastestImgForAll
    val followVpn by vm.followSystemVpn.collectAsState()
    val remoteChecking by vm.remoteChecking.collectAsState()
    val remoteInfo by vm.remoteInfo.collectAsState()
    val configRetest by RemoteConfigFetcher.configRetest.collectAsState()
    val speedTestResultVersion by vm.speedTestResultVersion.collectAsState()
    val speedTestProgress by vm.speedTestProgress.collectAsState()
    val pendingExport = remember { mutableStateOf<Pair<RouteManager.SpeedTestSnapshot, String>?>(null) }
    val exportSuccessMessage = stringResource(KMR.strings.speed_test_export_success)
    val exportFailedMessage = stringResource(KMR.strings.speed_test_export_failed)
    val noCompleteResultMessage = stringResource(KMR.strings.speed_test_export_no_complete_result)
    val exportInProgressMessage = stringResource(KMR.strings.speed_test_export_in_progress)
    val speedTestActive = testing || autoSelecting || configRetest != null || speedTesterRunning
    val canExport = fullSnapshot.canExport && !speedTestActive
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val pending = pendingExport.value
        pendingExport.value = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        if (SpeedTester.isRunning) {
            Toast.makeText(context, exportInProgressMessage, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (RouteManager.completedSpeedTestSnapshot.value != pending.first) {
            Toast.makeText(context, noCompleteResultMessage, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        exportScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(pending.second)
                    } ?: error("Unable to open the selected document")
                }
            }
            Toast.makeText(
                context,
                if (result.isSuccess) exportSuccessMessage else exportFailedMessage,
                if (result.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
        }
    }

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
    val serverRecommendedImgDomain = ReaderRepository.currentRecommendedImgDomain
    val serverFallbackImgDomain = ReaderRepository.currentImgDomains.firstOrNull()
    val currentImageSource = remember(
        successfulImgEndpoints,
        RouteManager.imgDomains,
        imgPinDisplayRevision,
        pinnedImg,
        currentImgIp,
        useFastestImg,
        serverRecommendedImgDomain,
        serverFallbackImgDomain,
    ) {
        ImageSourceDisplayPolicy.currentSource(
            configuredDomains = RouteManager.imgDomains,
            successfulEndpoints = successfulImgEndpoints,
            pinnedDomain = pinnedImg,
            pinnedIp = currentImgIp,
            useFastestImgForAll = useFastestImg,
            serverRecommendedDomain = serverRecommendedImgDomain,
            serverFallbackDomain = serverFallbackImgDomain,
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
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "网络维护提醒",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "本软件在每日晚上18:00前网络流畅,18:00后进行网络维护,会出现卡顿情况",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (testing || configRetest != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    configRetest?.let {
                        Text(
                            "检测到网络配置更新（v${it.version}），正在使用新配置重新测速…",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(vm.speedTestProgressMessage(speedTestProgress), style = MaterialTheme.typography.bodyMedium)
                    if (speedTestProgress.total > 0 &&
                        speedTestProgress.phase in setOf(SpeedTester.Phase.TESTING_LINES, SpeedTester.Phase.TESTING_IMAGES)
                    ) {
                        LinearProgressIndicator(
                            progress = { speedTestProgress.completed.toFloat() / speedTestProgress.total },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
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
                    Text(
                        speedTestResultVersion?.let { "测速结果对应配置：远程配置 v$it" }
                            ?: "测速结果对应配置：暂无当前配置的完整测速结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
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
                                    "已开启：未手动固定时优先用测速最快；无成功项时沿用服务端推荐"
                                } else {
                                    "已关闭：使用服务端推荐（手动固定仍优先）"
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
                                currentImageSource.description(),
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
                        isSelected = currentImageSource.matchesEndpoint(endpoint),
                        selectionLabel = currentImageSource.kind.label.takeIf { currentImageSource.matchesEndpoint(endpoint) },
                        onClick = { if (index >= 0) vm.selectImgHost(index, endpoint.ip) },
                    )
                }

                item {
                    if (currentImageSource.domain != null && imgOrder.none { currentImageSource.matchesEndpoint(it.key) }) {
                        Text(
                            currentImageSource.notInSuccessfulListDescription(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }

                item {
                    if (!canExport) {
                        Text(
                            if (speedTestActive) exportInProgressMessage else noCompleteResultMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val snapshot = fullSnapshot
                                if (!canExport || !snapshot.canExport || SpeedTester.isRunning) {
                                    Toast.makeText(
                                        context,
                                        if (SpeedTester.isRunning) exportInProgressMessage else noCompleteResultMessage,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    runCatching { SpeedTestLogJson.encode(snapshot, BuildConfig.VERSION_NAME) }
                                        .onSuccess { json ->
                                            pendingExport.value = snapshot to json
                                            exportLauncher.launch("speed_test_log.json")
                                        }
                                        .onFailure {
                                            Toast.makeText(context, exportFailedMessage, Toast.LENGTH_LONG).show()
                                        }
                                }
                            },
                            enabled = canExport,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(KMR.strings.speed_test_export_log))
                        }
                        Button(
                            onClick = { vm.runSpeedTest() },
                            enabled = !speedTestActive,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (speedTestActive) "测速中..." else "重新测速")
                        }
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
    selectionLabel: String? = null,
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
        if (selectionLabel != null) {
            Text(selectionLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
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
