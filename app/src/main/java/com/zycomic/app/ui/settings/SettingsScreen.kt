package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.data.repository.ReaderRepository
import com.zycomic.app.net.ImageSourceDisplayPolicy
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.RouteManager
import com.zycomic.app.net.SpeedTester
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.ui.components.FilterChip
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun SettingsScreen() {
    val vm = remember { SettingsViewModel() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jmState by JmRouteManager.state.collectAsState()
    val jmSnapshot by JmRouteManager.speedTestSnapshot.collectAsState()
    val jmLogs by JmRouteManager.logs.collectAsState()
    var jmTesting by remember { mutableStateOf(false) }
    var jmTestError by remember { mutableStateOf<String?>(null) }
    val filterEnabled by vm.filterEnabled.collectAsState()
    val platformSettings by StartupPlatformPolicy.settingsState.collectAsState()
    val restartRequiredMessage = stringResource(KMR.strings.startup_platform_restart_toast)
    val keepOneSourceMessage = stringResource(KMR.strings.startup_platform_keep_one_toast)
    val allTags by vm.allTags.collectAsState()
    val addingBlacklist by vm.addingBlacklist.collectAsState()
    val blockedTags by vm.blockedTags.collectAsState()
    val testing by vm.testing.collectAsState()
    val lineDelays by vm.lineDelays.collectAsState()
    val imgDelays by vm.imgDelays.collectAsState()
    val imgVerdicts by vm.imgVerdicts.collectAsState()
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val currentLineIp by vm.currentLineIp.collectAsState()
    val currentImgIp = RouteManager.selectedImgIp
    val pinnedImg = RouteManager.pinnedImgDomain
    val imgPinDisplayRevision = RouteManager.imgPinDisplayRevision
    val useFastestImg = RouteManager.useFastestImgForAll
    val sortedImgEntries = remember(imgDelays, imgVerdicts) {
        imgDelays.entries.sortedWith(
            compareBy({ if (imgVerdicts[it.key] == SpeedTester.ImgVerdict.OK) it.value else Long.MAX_VALUE }, { it.key.domain }, { it.key.ip }),
        )
    }
    val successfulImgEndpoints = remember(sortedImgEntries, imgVerdicts) {
        sortedImgEntries.filter { it.value < Long.MAX_VALUE && imgVerdicts[it.key] == SpeedTester.ImgVerdict.OK }.map { it.key }
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

    var showGayConfirm by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showGayConfirm) { showGayConfirm = false }
    androidx.activity.compose.BackHandler(enabled = showAddDialog) { showAddDialog = false }
    androidx.activity.compose.BackHandler(enabled = showRemoveDialog) { showRemoveDialog = false }

    LaunchedEffect(Unit) {
        vm.loadAllTags()
        vm.loadBlockedTags()
        vm.toast.collect { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                vm.toast.value = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))

        SectionTitle(stringResource(KMR.strings.startup_platform_settings_title))
        Text(
            stringResource(KMR.strings.startup_platform_settings_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(KMR.strings.startup_disable_manwa), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = platformSettings.disableManwa,
                onCheckedChange = { disable ->
                    val accepted = StartupPlatformPolicy.setSettingsDisableManwa(disable)
                    Toast.makeText(context, if (accepted) restartRequiredMessage else keepOneSourceMessage, Toast.LENGTH_SHORT).show()
                },
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(KMR.strings.startup_disable_jm), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = platformSettings.disableJm,
                onCheckedChange = { disable ->
                    val accepted = StartupPlatformPolicy.setSettingsDisableJm(disable)
                    Toast.makeText(context, if (accepted) restartRequiredMessage else keepOneSourceMessage, Toast.LENGTH_SHORT).show()
                },
            )
        }

        // 过滤屏蔽标签开关
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("过滤屏蔽标签", style = MaterialTheme.typography.titleMedium)
                Text(
                    "对屏蔽的标签漫画进行隐藏(正常会显示404)。建议登录后配合gay标签一键屏蔽使用。开启后部分分类会有大量屏蔽内容，为凑够布局会多获取几页，加载变慢属正常现象。建议选择[一般向]或[禁漫]或[搜索]，[排行]基本都是gay标签内容。如果你点击[BL向]只有两本属于正常，因为获取十页全屏蔽了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = filterEnabled, onCheckedChange = { vm.setFilterEnabled(it) })
        }

        // ===== 标签屏蔽添加 =====
        SectionTitle("添加屏蔽标签")

        // gay 一键屏蔽卡片（primaryContainer 背景区分）
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("gay 标签一键屏蔽", style = MaterialTheme.typography.titleMedium)
                    Text("自动屏蔽所有女性向/gay标签（${SettingsViewModel.GAY_TAGS.size}个）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { showGayConfirm = true }) { Text("一键屏蔽") }
            }
        }

        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { Text("添加屏蔽标签（搜索后多选）") }

        // ===== 标签屏蔽删除 =====
        SectionTitle("删除屏蔽标签")
        Button(
            onClick = { showRemoveDialog = true },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { Text("管理已屏蔽标签") }

        // ===== 测速日志 =====
        SectionTitle("测速日志")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("线路（按域名/IP速度排序，点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            lineDelays.entries.sortedWith(compareBy({ it.value.takeIf { ms -> ms < Long.MAX_VALUE } ?: Long.MAX_VALUE }, { it.key.domain }, { it.key.ip }))
                .forEachIndexed { order, entry ->
                    val endpoint = entry.key
                    val index = RouteManager.lineHosts.indexOfFirst { url ->
                        runCatching { java.net.URI(url).host.equals(endpoint.domain, ignoreCase = true) }.getOrDefault(false)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { if (index >= 0) vm.selectLine(index, endpoint.ip) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${order + 1}. ${endpoint.domain} · ${endpoint.ip}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == currentLineIdx && endpoint.ip == currentLineIp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "HTTP:${formatDelay(entry.value)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                entry.value == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                                entry.value < 300 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

            Text("图源（按域名/IP速度排序，点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                currentImageSource.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            sortedImgEntries
                .forEachIndexed { order, entry ->
                    val endpoint = entry.key
                    val index = RouteManager.imgDomains.indexOfFirst { it.equals(endpoint.domain, ignoreCase = true) }
                    val verdict = imgVerdicts[endpoint]
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { if (index >= 0) vm.selectImgHost(index, endpoint.ip) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${order + 1}. ${endpoint.domain} · ${endpoint.ip}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (currentImageSource.matchesEndpoint(endpoint)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (currentImageSource.matchesEndpoint(endpoint)) {
                            Text(currentImageSource.kind.label, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            text = "HTTP:${formatDelay(entry.value, verdict)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                verdict == SpeedTester.ImgVerdict.OK && entry.value < 300 -> MaterialTheme.colorScheme.primary
                                verdict != null && verdict != SpeedTester.ImgVerdict.OK -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

            if (currentImageSource.domain != null && sortedImgEntries.none { currentImageSource.matchesEndpoint(it.key) }) {
                Text(
                    currentImageSource.notInSuccessfulListDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Button(onClick = { vm.runSpeedTest() }, enabled = !testing, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (testing) "测速中..." else "重新测速")
            }
        }

        SectionTitle("禁漫天堂独立线路")
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                if (jmState.usingFallbackLines) {
                    "当前使用 JM 官方内置兜底线路（远程配置未提供 lines2）"
                } else {
                    "当前使用远程配置 lines2"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("JM 自动选线", style = MaterialTheme.typography.titleSmall)
                    Text("关闭后点击线路可固定为手动模式；状态与 Manwa 分开保存。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = jmState.autoSelect,
                    onCheckedChange = { JmRouteManager.setAutoSelectEnabled(it) },
                )
            }
            jmState.lines.forEachIndexed { index, url ->
                val host = runCatching { java.net.URI(url).host }.getOrDefault(url)
                val delay = jmSnapshot.lineDelaysMs[host]
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { JmRouteManager.selectLine(index) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}. $host${if (index == jmState.lineIndex) " · 当前" else ""}",
                        modifier = Modifier.weight(1f),
                        color = if (index == jmState.lineIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = when (delay) {
                            null -> "未测"
                            Long.MAX_VALUE -> "不可达"
                            else -> "${delay}ms"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text("JM 图源（点击固定；恢复自动会按 JM 测速结果选择）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            jmState.imageDomains.forEach { host ->
                val delay = jmSnapshot.imageDelaysMs[host]
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { JmRouteManager.selectImageDomain(host) }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${if (host.equals(jmState.imageDomain, true)) "● " else ""}$host",
                        modifier = Modifier.weight(1f),
                        color = if (host.equals(jmState.imageDomain, true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = when (delay) {
                            null -> "未测"
                            Long.MAX_VALUE -> "不可达"
                            else -> "${delay}ms"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { JmRouteManager.selectImageDomain(null) },
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text("图源自动") }
                Button(
                    onClick = {
                        jmTesting = true
                        jmTestError = null
                        scope.launch {
                            runCatching { com.zycomic.app.net.PlatformSpeedTestCoordinator.run(forceFresh = true, onlyPlatform = com.zycomic.app.platform.ContentPlatform.JM) }
                                .onFailure { jmTestError = it.message ?: "JM 测速失败" }
                            jmTesting = false
                        }
                    },
                    enabled = !jmTesting,
                ) { Text(if (jmTesting) "JM 测速中..." else "JM 重新测速") }
            }
            jmSnapshot.completedAtEpochMs?.let { Text("最近测速：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))}", style = MaterialTheme.typography.bodySmall) }
            jmTestError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("JM 独立日志（最近 8 条）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            jmLogs.takeLast(8).asReversed().forEach { entry ->
                Text(
                    text = "${entry.host} · ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // gay 标签一键屏蔽确认对话框
    if (showGayConfirm) {
        Dialog(onDismissRequest = { showGayConfirm = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(24.dp),
            ) {
                Text("确认屏蔽", style = MaterialTheme.typography.titleMedium)
                Text(
                    "确认将 ${SettingsViewModel.GAY_TAGS.size} 个gay相关标签加入屏蔽列表？",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.TextButton(onClick = { showGayConfirm = false }) {
                        Text("取消")
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        showGayConfirm = false
                        vm.blockGayTags()
                    }) {
                        Text("确认")
                    }
                }
            }
        }
    }

    // 添加屏蔽标签弹窗
    if (showAddDialog) {
        AddBlacklistDialog(
            tags = allTags,
            adding = addingBlacklist,
            onDismiss = { showAddDialog = false },
            onSubmit = { tags -> vm.submitAddBlacklist(tags) { showAddDialog = false } },
        )
    }

    // 删除屏蔽标签弹窗
    if (showRemoveDialog) {
        RemoveBlacklistDialog(
            vm = vm,
            onDismiss = { showRemoveDialog = false },
        )
    }
}

/**
 * 延迟显示：未测="未测"，失败="超时"，否则="123ms"。
 * 图源行传 [verdict] 时进一步区分"连不上"（网络层不通）与"无图"（能连但取不到图片对象）
 * ——这两种曾经都显示成"超时"，导致排查时分不清该换网络还是该改探活目标。
 */
private fun formatDelay(delay: Long?, verdict: SpeedTester.ImgVerdict? = null): String = when {
    verdict == SpeedTester.ImgVerdict.UNREACHABLE -> "连不上"
    verdict == SpeedTester.ImgVerdict.NO_IMAGE -> "无图"
    delay == null -> "未测"
    delay == Long.MAX_VALUE -> "超时"
    else -> "${delay}ms"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// ==================== 删除屏蔽标签弹窗 ====================

@Composable
private fun RemoveBlacklistDialog(
    vm: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val blockedTags by vm.blockedTags.collectAsState()
    val removing by vm.removingBlacklist.collectAsState()

    var loading by remember { mutableStateOf(true) }
    var keyword by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allSelected by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadBlockedTags()
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()),
        ) {
            // 顶部栏：标题 + 关闭
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "删除屏蔽标签（已选 ${selected.size}）",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(16.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            // 搜索框
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索已屏蔽标签") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            val filtered = if (keyword.isBlank()) {
                blockedTags
            } else {
                blockedTags.filter { it.contains(keyword, ignoreCase = true) }
            }

            // 全选按钮（搜索框下方）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        allSelected = !allSelected
                        selected = if (allSelected) {
                            filtered.toSet()
                        } else {
                            selected - filtered.toSet()
                        }
                    },
                ) { Text(if (allSelected) "取消全选" else "全选(已加载出的)") }
            }

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                blockedTags.isEmpty() -> Text(
                    "暂无屏蔽标签",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    filtered.forEach { tag ->
                        FilterChip(
                            text = tag,
                            selected = selected.contains(tag),
                            onClick = {
                                selected = if (selected.contains(tag)) selected - tag else selected + tag
                                allSelected = false
                            },
                        )
                    }
                }
            }

            // 底部按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onDismiss) { Text("取消") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !removing && blockedTags.isNotEmpty(),
                    onClick = {
                        if (selected.isEmpty()) {
                            Toast.makeText(context, "请先选择要删除的标签", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        vm.submitRemoveBlacklist(selected.toList()) {
                            onDismiss()
                        }
                    },
                ) { Text(if (removing) "删除中..." else "删除选中 (${selected.size})") }
            }
        }
    }
}
