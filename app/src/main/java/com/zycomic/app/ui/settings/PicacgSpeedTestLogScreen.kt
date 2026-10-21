package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.PicacgRouteManager
import com.zycomic.app.net.PicacgSpeedTester
import com.zycomic.app.net.PlatformSpeedTestCoordinator
import com.zycomic.app.net.StartupCheckPhase
import com.zycomic.app.net.StartupCheckResult
import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PicacgSpeedTestLogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val routeState by PicacgRouteManager.state.collectAsState()
    val snapshot by PicacgSpeedTester.snapshot.collectAsState()
    val progress by PicacgSpeedTester.progress.collectAsState()
    val platformTestRunning by PlatformSpeedTestCoordinator.running.collectAsState()
    val platformStatuses by PlatformSpeedTestCoordinator.platformStartupStatuses.collectAsState()
    val startupCheckStatuses by PlatformSpeedTestCoordinator.startupCheckStatuses.collectAsState()
    val running = remember { mutableStateOf(false) }
    val localRunFailed = remember { mutableStateOf(false) }
    val failedRunSnapshot = remember { mutableStateOf<PicacgSpeedTester.Snapshot?>(null) }
    val status = remember { mutableStateOf<String?>(null) }
    val pendingExport = remember { mutableStateOf<Pair<PicacgSpeedTester.Snapshot, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = pendingExport.value
        pendingExport.value = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        if (PicacgSpeedTester.snapshot.value != pending.first) {
            Toast.makeText(context, "测速结果已变化，请重新导出", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pending.second) }
                    ?: error("无法打开导出文件")
            }
            Toast.makeText(context, if (result.isSuccess) "哔咔测速日志已导出" else "哔咔测速日志导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    val picacgPhases = setOf(StartupCheckPhase.PICACG_LINE, StartupCheckPhase.PICACG_IMAGE)
    val picacgPhaseRunning = startupCheckStatuses.any { it.phase in picacgPhases && it.result == StartupCheckResult.RUNNING }
    val picacgInterrupted = startupCheckStatuses.any { it.phase in picacgPhases && it.result == StartupCheckResult.INTERRUPTED }
    val picacgPlatformRunning = platformStatuses[ContentPlatform.PICACG]?.running == true
    val picacgImageBlocked = startupCheckStatuses.any {
        it.phase == StartupCheckPhase.PICACG_IMAGE && it.result in setOf(StartupCheckResult.BLOCKED, StartupCheckResult.NO_SAMPLE)
    }
    val picacgActiveStage = progress?.stage in setOf(
        PicacgSpeedTester.Stage.PREPARING_API,
        PicacgSpeedTester.Stage.TESTING_API,
        PicacgSpeedTester.Stage.PREPARING_IMAGES,
        PicacgSpeedTester.Stage.TESTING_IMAGES,
        PicacgSpeedTester.Stage.IMAGE_BLOCKED,
    )
    val picacgActuallyTesting = running.value ||
        (!picacgInterrupted && picacgPlatformRunning && picacgActiveStage && (picacgPhaseRunning || picacgImageBlocked))
    val picacgCoordinatorLocked = running.value || platformTestRunning
    val previousRoundOnly = picacgInterrupted || (localRunFailed.value && snapshot === failedRunSnapshot.value) ||
        running.value || (!picacgInterrupted && (picacgPlatformRunning || picacgPhaseRunning))
    val apiProbesForDisplay = snapshot?.apiProbes.orEmpty().map { probe ->
        if (previousRoundOnly) probe.copy(testedThisRound = false, quickSelected = false) else probe
    }
    val imageProbesForDisplay = snapshot?.imageProbes.orEmpty().map { probe ->
        if (previousRoundOnly) probe.copy(testedThisRound = false, quickSelected = false) else probe
    }
    val apiRows = orderSpeedTestLogRows(
        apiProbesForDisplay,
        testedThisRound = { it.testedThisRound },
        successful = { it.success },
        elapsedMs = { it.elapsedMs },
        domain = { it.endpoint.domain },
        ip = { it.endpoint.ip },
    )
    val imageRows = orderSpeedTestLogRows(
        imageProbesForDisplay,
        testedThisRound = { it.testedThisRound },
        successful = { it.success },
        elapsedMs = { it.elapsedMs },
        domain = { it.endpoint.domain },
        ip = { it.endpoint.ip },
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(KMR.strings.picacg_speed_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        snapshot?.let { stringResource(KMR.strings.picacg_speed_log_completed_at, formatSpeedTestLogTime(it.completedAtEpochMs)) }
                            ?: stringResource(KMR.strings.picacg_speed_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(KMR.strings.picacg_speed_log_current_line, routeState.apiUrls.getOrNull(routeState.apiIndex).orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(KMR.strings.picacg_speed_log_current_image, routeState.imageHost.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    routeState.remoteVersion?.let { Text(stringResource(KMR.strings.picacg_speed_log_remote_version, it), style = MaterialTheme.typography.bodySmall) }
                    snapshot?.imageBlockedReason?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (picacgInterrupted) {
                        platformStatuses[ContentPlatform.PICACG]?.let {
                            Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    status.value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (picacgActuallyTesting) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator()
                            val current = progress
                            Text(
                                if (current != null && current.total > 0 && (running.value || picacgPhaseRunning)) {
                                    stringResource(KMR.strings.picacg_speed_progress, current.completed, current.total)
                                } else {
                                    stringResource(KMR.strings.picacg_speed_log_progress)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            enabled = !picacgCoordinatorLocked && routeState.apiUrls.isNotEmpty(),
                            onClick = {
                                running.value = true
                                localRunFailed.value = false
                                status.value = "测速中；结果将在本轮检测结束后更新。"
                                scope.launch {
                                    runCatching { PicacgSpeedTester.run() }
                                        .onSuccess { result ->
                                            status.value = "完成：线路 ${result.apiProbes.count { it.success }}/${result.apiProbes.size} 可用；" +
                                                (
                                                    result.imageBlockedReason?.let { "图源受阻：$it" }
                                                        ?: "图源 ${result.imageProbes.count { it.success }}/${result.imageProbes.size} 验证成功。"
                                                    )
                                        }
                                        .onFailure { error ->
                                            localRunFailed.value = true
                                            failedRunSnapshot.value = PicacgSpeedTester.snapshot.value
                                            status.value = "测速失败：${error.message ?: "未知错误"}"
                                        }
                                    running.value = false
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    when {
                                        picacgActuallyTesting -> KMR.strings.picacg_speed_log_running
                                        platformTestRunning -> KMR.strings.speed_log_waiting_other_test
                                        else -> KMR.strings.picacg_speed_log_start
                                    },
                                ),
                            )
                        }
                        Button(
                            enabled = snapshot != null && !picacgActuallyTesting,
                            onClick = {
                                val current = snapshot ?: return@Button
                                val json = runCatching { PicacgSpeedTester.exportJson(current) }.getOrNull()
                                if (json == null) {
                                    Toast.makeText(context, "暂无可导出的测速结果", Toast.LENGTH_SHORT).show()
                                } else {
                                    pendingExport.value = current to json
                                    exportLauncher.launch("picacg_speed_test_log.json")
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Text("导出日志")
                        }
                    }
                }
            }
            item { DeprecatedEndpointQuerySection(ContentPlatform.PICACG, enabled = !picacgCoordinatorLocked) }
            item {
                Text(
                    stringResource(KMR.strings.picacg_speed_log_line_heading),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (apiRows.isEmpty()) {
                if (routeState.apiUrls.isEmpty()) {
                    item { Text(stringResource(KMR.strings.speed_log_no_line_records), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                }
                items(routeState.apiUrls) { url ->
                    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
                    val selected = routeState.apiUrls.getOrNull(routeState.apiIndex) == url
                    SpeedTestEndpointRow(
                        label = host,
                        result = stringResource(KMR.strings.manwa_speed_log_untested),
                        measuredAtEpochMs = null,
                        testedThisRound = false,
                        selectionLabel = stringResource(KMR.strings.manwa_speed_log_selected_line).takeIf { selected },
                        onClick = {
                            PicacgRouteManager.selectApiUrl(url)
                            status.value = "已选择API线路"
                        },
                    )
                }
            } else {
                items(apiRows) { probe ->
                    val selected = routeState.apiUrls.getOrNull(routeState.apiIndex)?.let { runCatching { java.net.URI(it).host.equals(probe.endpoint.domain, true) }.getOrDefault(false) } == true &&
                        routeState.pinnedApi == probe.endpoint
                    val result = if (probe.success) {
                        stringResource(KMR.strings.manwa_speed_log_success, probe.elapsedMs)
                    } else {
                        stringResource(KMR.strings.manwa_speed_log_failed) + probe.httpStatus?.let { " · HTTP $it" }.orEmpty()
                    }
                    SpeedTestEndpointRow(
                        label = "${probe.endpoint.domain} · ${probe.endpoint.ip}",
                        result = result,
                        measuredAtEpochMs = probe.measuredAtEpochMs,
                        testedThisRound = probe.testedThisRound,
                        hasPreviousResult = !probe.testedThisRound && probe.measuredAtEpochMs != null,
                        quickSelected = probe.quickSelected,
                        selectionLabel = stringResource(KMR.strings.manwa_speed_log_selected_line).takeIf { selected },
                        failed = probe.testedThisRound && !probe.success,
                        onClick = {
                            routeState.apiUrls.firstOrNull { runCatching { java.net.URI(it).host.equals(probe.endpoint.domain, true) }.getOrDefault(false) }?.let { PicacgRouteManager.selectApiUrl(it) }
                            PicacgRouteManager.pinApiEndpoint(probe.endpoint)
                            status.value = "已选择API线路 ${probe.endpoint.domain} · ${probe.endpoint.ip}"
                        },
                    )
                }
            }
            item {
                Text(
                    stringResource(KMR.strings.picacg_speed_log_image_heading),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (imageRows.isEmpty()) {
                if (routeState.imageHosts.isEmpty()) {
                    item { Text(stringResource(KMR.strings.speed_log_no_image_records), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                }
                items(routeState.imageHosts) { host ->
                    val selected = routeState.imageHost.equals(host, true)
                    SpeedTestEndpointRow(
                        label = host,
                        result = stringResource(KMR.strings.manwa_speed_log_untested),
                        measuredAtEpochMs = null,
                        testedThisRound = false,
                        blocked = !previousRoundOnly && snapshot?.imageBlockedReason != null,
                        selectionLabel = stringResource(KMR.strings.manwa_speed_log_selected_image).takeIf { selected },
                        onClick = {
                            PicacgRouteManager.selectImageHost(host)
                            status.value = "已选择图源 $host"
                        },
                    )
                }
            } else {
                items(imageRows) { probe ->
                    val selected = routeState.imageHost.equals(probe.endpoint.domain, true) && routeState.pinnedImage == probe.endpoint
                    val blockedHistoric = !previousRoundOnly && snapshot?.imageBlockedReason != null && !probe.testedThisRound
                    val baseResult = when {
                        probe.success -> stringResource(KMR.strings.manwa_speed_log_success, probe.elapsedMs) + " · 图片已解码"
                        probe.httpStatus != null -> stringResource(KMR.strings.manwa_speed_log_failed) + " · HTTP ${probe.httpStatus}"
                        else -> stringResource(KMR.strings.manwa_speed_log_failed)
                    }
                    val result = if (blockedHistoric) "$baseResult · ${stringResource(KMR.strings.manwa_speed_log_reused)}" else baseResult
                    SpeedTestEndpointRow(
                        label = "${probe.endpoint.domain} · ${probe.endpoint.ip}",
                        result = result,
                        measuredAtEpochMs = probe.measuredAtEpochMs,
                        testedThisRound = probe.testedThisRound,
                        hasPreviousResult = !probe.testedThisRound && probe.measuredAtEpochMs != null,
                        blocked = blockedHistoric,
                        quickSelected = probe.quickSelected,
                        selectionLabel = stringResource(KMR.strings.manwa_speed_log_selected_image).takeIf { selected },
                        failed = probe.testedThisRound && !probe.success,
                        onClick = {
                            PicacgRouteManager.selectImageHost(probe.endpoint.domain)
                            PicacgRouteManager.pinImageEndpoint(probe.endpoint)
                            status.value = "已选择图源 ${probe.endpoint.domain} · ${probe.endpoint.ip}"
                        },
                    )
                }
            }
        }
    }
}
