package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
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
import com.zycomic.app.net.PlatformSpeedTestCoordinator
import com.zycomic.app.net.RemoteConfigFetcher
import com.zycomic.app.net.RouteManager
import com.zycomic.app.net.SpeedTestLogJson
import com.zycomic.app.net.SpeedTestTimingLog
import com.zycomic.app.net.SpeedTestUiTextPolicy
import com.zycomic.app.net.SpeedTester
import com.zycomic.app.net.StartupCheckPhase
import com.zycomic.app.net.StartupCheckResult
import com.zycomic.app.platform.ContentPlatform
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
    val platformTestsRunning by PlatformSpeedTestCoordinator.running.collectAsState()
    val checks by PlatformSpeedTestCoordinator.startupCheckStatuses.collectAsState()
    val fullSnapshot by RouteManager.completedSpeedTestSnapshot.collectAsState()
    val currentSpeedSnapshot by RouteManager.speedTestSnapshot.collectAsState()
    val speedTesterRunning by SpeedTester.isRunningFlow.collectAsState()
    val autoSelecting by vm.autoSelecting.collectAsState()
    val testing by vm.testing.collectAsState()
    // Keep the result, timestamp and provenance from the same published snapshot.
    val lineDelays = currentSpeedSnapshot.lineDelays
    val imgDelays = currentSpeedSnapshot.imgDelays
    val imgVerdicts = currentSpeedSnapshot.imgVerdicts
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val currentLineIp by vm.currentLineIp.collectAsState()
    val currentImgIp = RouteManager.selectedImgIp
    val pinnedImg = RouteManager.pinnedImgDomain
    val imgPinDisplayRevision = RouteManager.imgPinDisplayRevision
    val useFastestImg = RouteManager.useFastestImgForAll
    val configRetest by RemoteConfigFetcher.configRetest.collectAsState()
    val speedTestResultVersion by vm.speedTestResultVersion.collectAsState()
    val speedTestProgress by vm.speedTestProgress.collectAsState()
    val pendingExport = remember { mutableStateOf<Pair<RouteManager.SpeedTestSnapshot, String>?>(null) }
    val exportSuccessMessage = stringResource(KMR.strings.speed_test_export_success)
    val exportFailedMessage = stringResource(KMR.strings.speed_test_export_failed)
    val noCompleteResultMessage = stringResource(KMR.strings.speed_test_export_no_complete_result)
    val exportInProgressMessage = stringResource(KMR.strings.speed_test_export_in_progress)
    val speedTestActive = testing || autoSelecting || configRetest != null || speedTesterRunning || platformTestsRunning
    val manwaTestActive = speedTesterRunning
    val manwaInterrupted = checks.any {
        it.phase in setOf(StartupCheckPhase.MANWA_LINE, StartupCheckPhase.MANWA_IMAGE) && it.result == StartupCheckResult.INTERRUPTED
    }
    val showingPreviousRound = manwaTestActive || manwaInterrupted || speedTestProgress.phase != SpeedTester.Phase.COMPLETE
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

    // 快测优先按历史排名自适应探测；其余端点保留历史记录并明确标记为未测。
    val lineOrder = remember(lineDelays, currentSpeedSnapshot.lineUntestedEndpoints) {
        val untested = currentSpeedSnapshot.lineUntestedEndpoints
        orderSpeedTestLogRows(
            rows = lineDelays.entries.toList(),
            testedThisRound = { it.key !in untested },
            successful = { it.value >= 0L && it.value < Long.MAX_VALUE },
            elapsedMs = { it.value },
            domain = { it.key.domain },
            ip = { it.key.ip },
        )
    }
    val imgOrder = remember(imgDelays, imgVerdicts, currentSpeedSnapshot.imageUntestedEndpoints) {
        val untested = currentSpeedSnapshot.imageUntestedEndpoints
        orderSpeedTestLogRows(
            rows = imgDelays.entries.toList(),
            testedThisRound = { it.key !in untested },
            successful = { it.value >= 0L && it.value < Long.MAX_VALUE && imgVerdicts[it.key] == SpeedTester.ImgVerdict.OK },
            elapsedMs = { it.value },
            domain = { it.key.domain },
            ip = { it.key.ip },
        )
    }
    val successfulImgEndpoints = remember(imgOrder, imgVerdicts, currentSpeedSnapshot.imageUntestedEndpoints) {
        imgOrder.filter {
            it.key !in currentSpeedSnapshot.imageUntestedEndpoints &&
                it.value < Long.MAX_VALUE && imgVerdicts[it.key] == SpeedTester.ImgVerdict.OK
        }.map { it.key }
    }
    // Use the published round's membership, not adaptive candidate ranking or current route selection.
    val lineQuickTestEndpoints = if (!showingPreviousRound && speedTestProgress.phase == SpeedTester.Phase.COMPLETE && speedTestProgress.lineQuickTest) {
        currentSpeedSnapshot.lineDelays.keys - currentSpeedSnapshot.lineUntestedEndpoints
    } else {
        emptySet()
    }
    val imageQuickTestEndpoints = if (!showingPreviousRound && speedTestProgress.phase == SpeedTester.Phase.COMPLETE && speedTestProgress.imageQuickTest) {
        currentSpeedSnapshot.imgDelays.keys - currentSpeedSnapshot.imageUntestedEndpoints
    } else {
        emptySet()
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
                title = { Text(stringResource(KMR.strings.manwa_speed_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            // 只读时间线：用于定位“进入前到底等了什么”，不改任何测速判定。
            val timingSummary = remember {
                SpeedTestTimingLog.summary().ifBlank { "暂无本轮时间线（启动测速跑完后可查看）" }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "启动时间线（最近一轮）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("speed-test-timing", timingSummary),
                                )
                            Toast.makeText(context, "已复制时间线", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("复制") }
                }
                Text(
                    timingSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (manwaTestActive || speedTestProgress.phase == SpeedTester.Phase.FAILED || (
                    speedTestProgress.phase == SpeedTester.Phase.COMPLETE &&
                        (speedTestProgress.reusedImages > 0 || speedTestProgress.reusedLines > 0 || speedTestProgress.fullRetestRecommended)
                    )
            ) {
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
                    if (manwaTestActive) {
                        Text(stringResource(KMR.strings.speed_log_pending_results), style = MaterialTheme.typography.bodySmall)
                    }
                    if (speedTestProgress.phase == SpeedTester.Phase.COMPLETE && speedTestProgress.fullRetestRecommended) {
                        Text(
                            "历史排序自适应快测未取得有效成功结果（${listOfNotNull(
                                "线路".takeIf { speedTestProgress.lineTopFiveAllFailed },
                                "图源".takeIf { speedTestProgress.imageTopFiveAllFailed },
                            ).joinToString("、")}）。未测历史只用于排序，不作为自动选择依据；如需覆盖全部候选，请点“完整重新测速”。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            ScrollbarLazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            fullSnapshot.completedAtEpochMs?.let { stringResource(KMR.strings.manwa_speed_log_recently_completed, formatSpeedTestTime(it)) }
                                ?: stringResource(KMR.strings.manwa_speed_log_no_completed_time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            speedTestResultVersion?.let { "测速结果对应配置：远程配置 v$it" }
                                ?: "测速结果对应配置：暂无当前配置的完整测速结果",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (lineQuickTestEndpoints.isNotEmpty()) {
                            Text(
                                stringResource(KMR.strings.manwa_speed_log_quick_line, lineQuickTestEndpoints.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (imageQuickTestEndpoints.isNotEmpty()) {
                            Text(
                                stringResource(KMR.strings.manwa_speed_log_quick_image, imageQuickTestEndpoints.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val selectedLine = RouteManager.lineHosts.getOrNull(currentLineIdx)
                            ?.let { runCatching { java.net.URI(it).host ?: it }.getOrDefault(it) } ?: "暂无"
                        Text(
                            stringResource(
                                KMR.strings.manwa_speed_log_current_line,
                                if (RouteManager.hasManualLineSelection) "手动" else "自动",
                                selectedLine + currentLineIp?.let { " · $it" }.orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(
                                KMR.strings.manwa_speed_log_current_image,
                                currentImageSource.kind.label,
                                (currentImageSource.domain ?: "暂无") + currentImgIp?.let { " · $it" }.orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item { DeprecatedEndpointQuerySection(ContentPlatform.MANWA, enabled = !speedTestActive) }
                item {
                    Text("线路（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                if (lineOrder.isEmpty()) {
                    item { Text(stringResource(KMR.strings.speed_log_no_line_records), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                }
                items(lineOrder) { entry ->
                    val endpoint = entry.key
                    val index = RouteManager.lineHosts.indexOfFirst {
                        runCatching { java.net.URI(it).host.equals(endpoint.domain, ignoreCase = true) }.getOrDefault(false)
                    }
                    SpeedRow(
                        host = SpeedTestUiTextPolicy.endpointLabel(endpoint.domain, endpoint.ip),
                        delay = entry.value,
                        isImage = false,
                        isSelected = index == currentLineIdx && endpoint.ip == currentLineIp,
                        isQuickTestEndpoint = endpoint in lineQuickTestEndpoints,
                        isRoundSelected = !showingPreviousRound && endpoint == speedTestProgress.roundSelectedLine,
                        isUntested = showingPreviousRound || endpoint in currentSpeedSnapshot.lineUntestedEndpoints,
                        measuredAtEpochMs = currentSpeedSnapshot.lineMeasuredAtEpochMs[endpoint],
                        onClick = { if (index >= 0) vm.selectLine(index, endpoint.ip) },
                    )
                }

                item {
                    Text("图源（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                }
                if (imgOrder.isEmpty()) {
                    item { Text(stringResource(KMR.strings.speed_log_no_image_records), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                }
                items(imgOrder) { entry ->
                    val endpoint = entry.key
                    val index = RouteManager.imgDomains.indexOfFirst { it.equals(endpoint.domain, ignoreCase = true) }
                    SpeedRow(
                        host = SpeedTestUiTextPolicy.endpointLabel(endpoint.domain, endpoint.ip),
                        delay = entry.value,
                        verdict = imgVerdicts[endpoint],
                        isQuickTestEndpoint = endpoint in imageQuickTestEndpoints,
                        isRoundSelected = !showingPreviousRound && endpoint == speedTestProgress.roundSelectedImage,
                        isUntested = showingPreviousRound || endpoint in currentSpeedSnapshot.imageUntestedEndpoints,
                        measuredAtEpochMs = currentSpeedSnapshot.imageMeasuredAtEpochMs[endpoint],
                        isSelected = currentImageSource.matchesEndpoint(endpoint),
                        selectionLabel = currentImageSource.kind.label.takeIf { currentImageSource.matchesEndpoint(endpoint) },
                        isImage = true,
                        onClick = { if (index >= 0) vm.selectImgHost(index, endpoint.ip) },
                    )
                }

                item {
                    if (currentImageSource.domain != null && imgOrder.none { currentImageSource.matchesEndpoint(it.key) }) {
                        Text(
                            SpeedTestUiTextPolicy.imageSourceDescription(currentImageSource.kind, inSuccessfulList = false),
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
                            onClick = { vm.runSpeedTest(onlyPlatform = com.zycomic.app.platform.ContentPlatform.MANWA) },
                            enabled = !speedTestActive,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                when {
                                    manwaTestActive -> "测速中..."
                                    speedTestActive -> stringResource(KMR.strings.speed_log_waiting_other_test)
                                    else -> "完整重新测速"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedRow(
    host: String,
    delay: Long?,
    isSelected: Boolean,
    isImage: Boolean,
    verdict: SpeedTester.ImgVerdict? = null,
    selectionLabel: String? = null,
    isQuickTestEndpoint: Boolean = false,
    isRoundSelected: Boolean = false,
    isUntested: Boolean = false,
    measuredAtEpochMs: Long? = null,
    onClick: () -> Unit,
) {
    val result = when {
        verdict == SpeedTester.ImgVerdict.NO_IMAGE -> stringResource(KMR.strings.manwa_speed_log_no_image)
        verdict == SpeedTester.ImgVerdict.UNREACHABLE -> stringResource(KMR.strings.manwa_speed_log_unreachable)
        delay == null || delay == Long.MAX_VALUE -> stringResource(KMR.strings.manwa_speed_log_failed)
        else -> stringResource(KMR.strings.manwa_speed_log_success, delay)
    }
    val marker = when {
        isSelected && isImage -> stringResource(KMR.strings.manwa_speed_log_selected_image) + selectionLabel?.let { " · $it" }.orEmpty()
        isSelected -> stringResource(KMR.strings.manwa_speed_log_selected_line)
        else -> null
    }
    SpeedTestEndpointRow(
        label = host,
        result = result,
        measuredAtEpochMs = measuredAtEpochMs,
        testedThisRound = !isUntested && delay != null,
        hasPreviousResult = isUntested && delay != null,
        quickSelected = isQuickTestEndpoint,
        roundSelected = isRoundSelected,
        selectionLabel = marker,
        failed = delay == Long.MAX_VALUE || verdict == SpeedTester.ImgVerdict.UNREACHABLE || verdict == SpeedTester.ImgVerdict.NO_IMAGE,
        onClick = onClick,
    )
}

private fun formatSpeedTestTime(epochMs: Long): String =
    formatSpeedTestLogTime(epochMs)

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

/** Network diagnostics live in developer settings rather than cluttering the speed-test log. */
@Composable
fun ManwaDeveloperControls(vm: SettingsViewModel) {
    val autoSel by vm.autoSelectEnabled.collectAsState()
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val useFastestImg = RouteManager.useFastestImgForAll
    val followVpn by vm.followSystemVpn.collectAsState()
    val vpnOnlyAutoRetest by vm.vpnOnlyAutoRetest.collectAsState()
    val pinnedImg = RouteManager.pinnedImgDomain
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动选择最快线路", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (autoSel) {
                        "已开启，当前线路 ${currentLineIdx + 1}（手动选择仅临时覆盖，重启后恢复自动）"
                    } else {
                        "已关闭，纯手动模式"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoSel, onCheckedChange = { vm.setAutoSelectEnabled(it) })
        }
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("当前图源", style = MaterialTheme.typography.bodyLarge)
                Text(
                    pinnedImg ?: "自动选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!pinnedImg.isNullOrBlank()) {
                Button(onClick = { vm.resetImgHostToAuto() }) { Text("恢复自动") }
            }
        }
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
                        "已关闭：强制直连，即使开着系统 VPN 也不走 VPN；仅对配置中的非 Cloudflare 主机尝试去除 SNI"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = followVpn, onCheckedChange = { vm.setFollowSystemVpn(it) })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("VPN 单独变化后自动测速", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${if (vpnOnlyAutoRetest) "已开启" else "已关闭"}：只控制可确认的 VPN 单独变化后的自动测速和新排名应用；不控制路由选择或连接清理。真实网络变化仍测速，底层网络无法确认时沿用原行为。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = vpnOnlyAutoRetest,
                onCheckedChange = { vm.setVpnOnlyAutoRetest(it) },
            )
        }
    }
}
