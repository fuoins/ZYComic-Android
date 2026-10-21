package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.JmSpeedTestProgress
import com.zycomic.app.net.JmSpeedTester
import com.zycomic.app.net.PlatformSpeedTestCoordinator
import com.zycomic.app.net.StartupCheckPhase
import com.zycomic.app.net.StartupCheckResult
import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmSpeedTestLogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by JmRouteManager.speedTestSnapshot.collectAsState()
    val routeState by JmRouteManager.state.collectAsState()
    val routeLogs by JmRouteManager.logs.collectAsState()
    val endpointSelection by JmRouteManager.endpointSelection.collectAsState()
    val running by PlatformSpeedTestCoordinator.running.collectAsState()
    val jmProgress by PlatformSpeedTestCoordinator.jmProgress.collectAsState()
    val platformStatuses by PlatformSpeedTestCoordinator.platformStartupStatuses.collectAsState()
    val checks by PlatformSpeedTestCoordinator.startupCheckStatuses.collectAsState()
    val jmPlatformRunning = platformStatuses[ContentPlatform.JM]?.running == true
    val jmInterrupted = checks.any {
        it.phase in setOf(StartupCheckPhase.JM_LINE, StartupCheckPhase.JM_IMAGE) && it.result == StartupCheckResult.INTERRUPTED
    }
    val snapshotIsCurrentRun = !jmInterrupted && jmProgress?.phase != JmSpeedTestProgress.Phase.FAILED &&
        (!jmPlatformRunning || jmProgress?.phase == JmSpeedTestProgress.Phase.COMPLETE)
    val status = remember { mutableStateOf<String?>(null) }
    val pendingExport = remember { mutableStateOf<Pair<JmRouteManager.SpeedTestSnapshot, String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = pendingExport.value
        pendingExport.value = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        if (JmRouteManager.speedTestSnapshot.value != pending.first) {
            Toast.makeText(context, "测速结果已变化，请重新导出", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pending.second) }
                        ?: error("无法打开导出文件")
                }
            }
            Toast.makeText(context, if (result.isSuccess) "禁漫测速日志已导出" else "禁漫测速日志导出失败", Toast.LENGTH_SHORT).show()
        }
    }
    val selectedLine = JmRouteManager.lineHost
    val selectedLineEndpoint = endpointSelection.line
    val selectedImageDomain = routeState.imageDomain
    val selectedImageEndpoint = endpointSelection.image?.takeIf { it.domain.equals(selectedImageDomain, true) }
    val lineRows = orderSpeedTestLogRows(
        rows = JmSpeedTestLogPolicy.rankEndpoints(snapshot.lineIpDelaysMs) + snapshot.untestedLineIpDelaysMs.keys,
        testedThisRound = { snapshotIsCurrentRun && it in snapshot.testedLineEndpoints },
        successful = { endpoint ->
            val delay = snapshot.lineIpDelaysMs[endpoint] ?: snapshot.untestedLineIpDelaysMs.getValue(endpoint)
            delay >= 0L && delay < Long.MAX_VALUE
        },
        elapsedMs = { endpoint -> snapshot.lineIpDelaysMs[endpoint] ?: snapshot.untestedLineIpDelaysMs.getValue(endpoint) },
        domain = { it.domain },
        ip = { it.ip },
    )
    val failedImageEndpoints = snapshot.imageIpDelaysMs.keys.filterTo(mutableSetOf()) { endpoint ->
        snapshot.imageIpDelaysMs.getValue(endpoint) == Long.MAX_VALUE ||
            snapshot.imageIpVerdicts[endpoint] != JmSpeedTester.ImgVerdict.OK
    }
    val imageRows = orderSpeedTestLogRows(
        rows = JmSpeedTestLogPolicy.rankEndpoints(snapshot.imageIpDelaysMs, failedImageEndpoints) + snapshot.untestedImageIpDelaysMs.keys,
        testedThisRound = { snapshotIsCurrentRun && it in snapshot.testedImageEndpoints },
        successful = { endpoint ->
            val delay = snapshot.imageIpDelaysMs[endpoint] ?: snapshot.untestedImageIpDelaysMs.getValue(endpoint)
            val verdict = snapshot.imageIpVerdicts[endpoint] ?: snapshot.untestedImageIpVerdicts[endpoint]
            delay >= 0L && delay < Long.MAX_VALUE && verdict == JmSpeedTester.ImgVerdict.OK
        },
        elapsedMs = { endpoint -> snapshot.imageIpDelaysMs[endpoint] ?: snapshot.untestedImageIpDelaysMs.getValue(endpoint) },
        domain = { it.domain },
        ip = { it.ip },
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(KMR.strings.jm_speed_log_title)) },
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
                        snapshot.completedAtEpochMs?.let { stringResource(KMR.strings.manwa_speed_log_recently_completed, formatSpeedTestLogTime(it)) }
                            ?: "暂无完整禁漫测速结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snapshot.remoteVersion?.let { Text("远程配置版本：$it", style = MaterialTheme.typography.bodySmall) }
                    if (snapshotIsCurrentRun && (snapshot.lineQuickTest || snapshot.imageQuickTest)) {
                        Text(
                            "本次快速复测：线路 ${if (snapshot.lineQuickTest) "前五历史端点" else "完整检测"}；图源 ${if (snapshot.imageQuickTest) "前五历史端点" else "完整检测"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "当前选中线路（${if (routeState.autoSelect) "自动" else "手动"}）：$selectedLine${selectedLineEndpoint?.let { " · ${it.ip}" }.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "当前选中图源域名：$selectedImageDomain",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "当前实际图源端点：${selectedImageEndpoint?.let { "${it.domain} · ${it.ip}" } ?: "暂无已确认端点"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    jmProgress?.let {
                        Text(it.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (jmProgress == null && jmInterrupted) {
                        platformStatuses[ContentPlatform.JM]?.let {
                            Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (jmPlatformRunning && jmProgress?.phase != JmSpeedTestProgress.Phase.COMPLETE) {
                        Text(
                            stringResource(KMR.strings.speed_log_pending_results),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (snapshotIsCurrentRun && (snapshot.lineQuickTestAllFailed || snapshot.imageQuickTestAllFailed)) {
                        Text(
                            "前五个历史端点本轮全部失败，未扩展到第六项；请点击“完整重新测速”检测全部端点。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    status.value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(
                        enabled = snapshot.completedAtEpochMs != null && !running,
                        onClick = {
                            val json = runCatching {
                                encodeJmSpeedTestLog(
                                    snapshot = snapshot,
                                    routeLogs = routeLogs,
                                    selectedLine = selectedLine,
                                    selectedLineEndpoint = selectedLineEndpoint,
                                    selectedImageDomain = selectedImageDomain,
                                    selectedImageEndpoint = selectedImageEndpoint,
                                    autoSelect = routeState.autoSelect,
                                )
                            }.getOrNull()
                            if (json == null) {
                                Toast.makeText(context, "暂无可导出的完整测速结果", Toast.LENGTH_SHORT).show()
                            } else {
                                pendingExport.value = snapshot to json
                                exportLauncher.launch("jm_speed_test_log.json")
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("导出禁漫测速日志") }
                    Button(
                        enabled = !running,
                        onClick = {
                            status.value = "正在重新检测禁漫天堂线路与图源…"
                            scope.launch {
                                runCatching {
                                    PlatformSpeedTestCoordinator.run(forceFresh = true, onlyPlatform = ContentPlatform.JM)
                                }.onSuccess {
                                    status.value = "禁漫测速完成"
                                }.onFailure {
                                    status.value = "禁漫测速失败：${it.message ?: "未知错误"}"
                                }
                            }
                        },
                    ) {
                        Text(
                            when {
                                jmPlatformRunning -> "测速中…"
                                running -> stringResource(KMR.strings.speed_log_waiting_other_test)
                                else -> "完整重新测速"
                            },
                        )
                    }
                }
            }
            item { DeprecatedEndpointQuerySection(ContentPlatform.JM, enabled = !running) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("线路端点（域名 · IP）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(KMR.strings.jm_speed_log_endpoint_ip_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (lineRows.isEmpty()) {
                item {
                    Text(
                        stringResource(KMR.strings.speed_log_no_line_records),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(lineRows) { endpoint ->
                val delay = snapshot.lineIpDelaysMs[endpoint] ?: snapshot.untestedLineIpDelaysMs.getValue(endpoint)
                val testedThisRound = snapshotIsCurrentRun && endpoint in snapshot.testedLineEndpoints
                val time = snapshot.lineIpTestedAtEpochMs[endpoint] ?: snapshot.untestedLineIpTestedAtEpochMs[endpoint]
                SpeedTestEndpointRow(
                    label = "${endpoint.domain} · ${endpoint.ip}",
                    result = if (delay == Long.MAX_VALUE || delay < 0L) {
                        stringResource(KMR.strings.manwa_speed_log_failed)
                    } else {
                        stringResource(KMR.strings.manwa_speed_log_success, delay)
                    },
                    measuredAtEpochMs = time,
                    testedThisRound = testedThisRound,
                    roundSelected = snapshotIsCurrentRun && endpoint == snapshot.roundSelectedLine,
                    hasPreviousResult = !testedThisRound,
                    quickSelected = snapshotIsCurrentRun && snapshot.lineQuickTest && endpoint in snapshot.testedLineEndpoints,
                    selectionLabel = "当前实际 API 端点".takeIf {
                        JmSpeedTestLogPolicy.isSelectedEndpoint(endpoint, selectedLineEndpoint)
                    },
                    failed = delay == Long.MAX_VALUE || delay < 0L,
                    onClick = { JmRouteManager.selectLineEndpoint(endpoint.domain, endpoint.ip) },
                )
            }
            item { Text("图源端点（域名 · IP）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (imageRows.isEmpty()) {
                item {
                    Text(
                        stringResource(KMR.strings.speed_log_no_image_records),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(imageRows) { endpoint ->
                val delay = snapshot.imageIpDelaysMs[endpoint] ?: snapshot.untestedImageIpDelaysMs.getValue(endpoint)
                val testedThisRound = snapshotIsCurrentRun && endpoint in snapshot.testedImageEndpoints
                val verdict = snapshot.imageIpVerdicts[endpoint] ?: snapshot.untestedImageIpVerdicts[endpoint]
                val time = snapshot.imageIpTestedAtEpochMs[endpoint] ?: snapshot.untestedImageIpTestedAtEpochMs[endpoint]
                val failed = delay == Long.MAX_VALUE || delay < 0L || verdict != JmSpeedTester.ImgVerdict.OK
                val result = when (verdict) {
                    JmSpeedTester.ImgVerdict.NO_IMAGE -> stringResource(KMR.strings.manwa_speed_log_no_image)
                    JmSpeedTester.ImgVerdict.UNREACHABLE -> stringResource(KMR.strings.manwa_speed_log_unreachable)
                    else -> if (failed) {
                        stringResource(KMR.strings.manwa_speed_log_failed)
                    } else {
                        stringResource(KMR.strings.manwa_speed_log_success, delay)
                    }
                }
                SpeedTestEndpointRow(
                    label = "${endpoint.domain} · ${endpoint.ip}",
                    result = result,
                    measuredAtEpochMs = time,
                    testedThisRound = testedThisRound,
                    roundSelected = snapshotIsCurrentRun && endpoint == snapshot.roundSelectedImage,
                    hasPreviousResult = !testedThisRound,
                    quickSelected = snapshotIsCurrentRun && snapshot.imageQuickTest && endpoint in snapshot.testedImageEndpoints,
                    selectionLabel = "当前实际图源端点".takeIf {
                        JmSpeedTestLogPolicy.isSelectedEndpoint(endpoint, selectedImageEndpoint)
                    },
                    failed = failed,
                )
            }
        }
    }
}

private fun encodeJmSpeedTestLog(
    snapshot: JmRouteManager.SpeedTestSnapshot,
    routeLogs: List<JmRouteManager.LogEntry>,
    selectedLine: String,
    selectedLineEndpoint: DomainIp?,
    selectedImageDomain: String,
    selectedImageEndpoint: DomainIp?,
    autoSelect: Boolean,
): String = buildJsonObject {
    put("platform", JsonPrimitive("禁漫天堂"))
    put("startedAtEpochMs", snapshot.startedAtEpochMs?.let(::JsonPrimitive) ?: JsonNull)
    put("completedAtEpochMs", snapshot.completedAtEpochMs?.let(::JsonPrimitive) ?: JsonNull)
    put("remoteConfigVersion", snapshot.remoteVersion?.let(::JsonPrimitive) ?: JsonNull)
    put("configFingerprint", snapshot.configFingerprint?.let(::JsonPrimitive) ?: JsonNull)
    put("imageConfigFingerprint", snapshot.imageConfigFingerprint?.let(::JsonPrimitive) ?: JsonNull)
    put("imageNetworkPolicy", snapshot.imageNetworkPolicy?.let(::JsonPrimitive) ?: JsonNull)
    put("imageResultsReused", JsonPrimitive(snapshot.imageResultsReused))
    put("lineQuickTest", JsonPrimitive(snapshot.lineQuickTest))
    put("imageQuickTest", JsonPrimitive(snapshot.imageQuickTest))
    put("selectedLineHost", JsonPrimitive(selectedLine))
    put(
        "selectedLineEndpoint",
        selectedLineEndpoint?.let {
            buildJsonObject {
                put("domain", JsonPrimitive(it.domain))
                put("ip", JsonPrimitive(it.ip))
            }
        } ?: JsonNull,
    )
    put("lineSelectionMode", JsonPrimitive(if (autoSelect) "automatic" else "manual"))
    put("selectedImageDomain", JsonPrimitive(selectedImageDomain))
    put(
        "selectedImageEndpoint",
        selectedImageEndpoint?.let { endpoint ->
            buildJsonObject {
                put("domain", JsonPrimitive(endpoint.domain))
                put("ip", JsonPrimitive(endpoint.ip))
            }
        } ?: JsonNull,
    )
    put(
        "lineResults",
        buildJsonObject {
            JmSpeedTestLogPolicy.rankHosts(snapshot.lineDelaysMs).forEach { host ->
                val delay = snapshot.lineDelaysMs.getValue(host)
                put(host, if (delay < 0L || delay == Long.MAX_VALUE) JsonNull else JsonPrimitive(delay))
            }
        },
    )
    put(
        "imageResults",
        buildJsonObject {
            JmSpeedTestLogPolicy.rankHosts(snapshot.imageDelaysMs).forEach { host ->
                val delay = snapshot.imageDelaysMs.getValue(host)
                put(host, if (delay < 0L || delay == Long.MAX_VALUE) JsonNull else JsonPrimitive(delay))
            }
        },
    )
    put(
        "lineEndpoints",
        JsonArray(
            (JmSpeedTestLogPolicy.rankEndpoints(snapshot.lineIpDelaysMs) + snapshot.untestedLineIpDelaysMs.keys).map { endpoint ->
                val delay = snapshot.lineIpDelaysMs[endpoint] ?: snapshot.untestedLineIpDelaysMs.getValue(endpoint)
                endpointJson(
                    endpoint = endpoint,
                    delay = delay,
                    verdict = null,
                    testedThisRound = endpoint in snapshot.testedLineEndpoints,
                    testedAtEpochMs = snapshot.lineIpTestedAtEpochMs[endpoint] ?: snapshot.untestedLineIpTestedAtEpochMs[endpoint],
                )
            },
        ),
    )
    put(
        "imageEndpoints",
        JsonArray(
            (
                JmSpeedTestLogPolicy.rankEndpoints(
                    snapshot.imageIpDelaysMs,
                    snapshot.imageIpDelaysMs.keys.filterTo(mutableSetOf()) { endpoint ->
                        snapshot.imageIpDelaysMs.getValue(endpoint) == Long.MAX_VALUE ||
                            snapshot.imageIpVerdicts[endpoint] != JmSpeedTester.ImgVerdict.OK
                    },
                ) + snapshot.untestedImageIpDelaysMs.keys
                ).map { endpoint ->
                val delay = snapshot.imageIpDelaysMs[endpoint] ?: snapshot.untestedImageIpDelaysMs.getValue(endpoint)
                val verdict = snapshot.imageIpVerdicts[endpoint] ?: snapshot.untestedImageIpVerdicts[endpoint]
                val tested = endpoint in snapshot.testedImageEndpoints
                endpointJson(
                    endpoint = endpoint,
                    delay = delay,
                    verdict = verdict,
                    selected = selectedImageEndpoint == endpoint,
                    successful = delay < Long.MAX_VALUE && verdict == JmSpeedTester.ImgVerdict.OK,
                    testedThisRound = tested,
                    testedAtEpochMs = snapshot.imageIpTestedAtEpochMs[endpoint] ?: snapshot.untestedImageIpTestedAtEpochMs[endpoint],
                )
            },
        ),
    )
    put(
        "routeLogs",
        JsonArray(
            routeLogs.map { entry ->
                buildJsonObject {
                    put("atEpochMs", JsonPrimitive(entry.atEpochMs))
                    put("kind", JsonPrimitive(entry.kind))
                    put("host", JsonPrimitive(entry.host))
                    put("message", JsonPrimitive(entry.message))
                }
            },
        ),
    )
}.toString()

private fun endpointJson(
    endpoint: DomainIp,
    delay: Long,
    verdict: JmSpeedTester.ImgVerdict?,
    selected: Boolean? = null,
    successful: Boolean = delay >= 0L && delay < Long.MAX_VALUE,
    testedThisRound: Boolean = true,
    testedAtEpochMs: Long? = null,
): JsonObject = buildJsonObject {
    put("domain", JsonPrimitive(endpoint.domain))
    put("ip", JsonPrimitive(endpoint.ip))
    put("latencyMs", if (delay < 0L || delay == Long.MAX_VALUE) JsonNull else JsonPrimitive(delay))
    put("success", JsonPrimitive(successful))
    put("imageVerdict", verdict?.let { JsonPrimitive(it.name.lowercase(java.util.Locale.ROOT)) } ?: JsonNull)
    put("testedThisRound", JsonPrimitive(testedThisRound))
    testedAtEpochMs?.let { put("testedAtEpochMs", JsonPrimitive(it)) }
    selected?.let { put("selected", JsonPrimitive(it)) }
}
