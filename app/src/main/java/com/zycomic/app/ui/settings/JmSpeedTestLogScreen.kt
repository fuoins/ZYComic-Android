package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.JmSpeedTester
import com.zycomic.app.net.PlatformSpeedTestCoordinator
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
    val lineRows = JmSpeedTestLogPolicy.rankEndpoints(snapshot.lineIpDelaysMs)
    val failedImageEndpoints = snapshot.imageIpDelaysMs.keys.filterTo(mutableSetOf()) { endpoint ->
        snapshot.imageIpDelaysMs.getValue(endpoint) == Long.MAX_VALUE ||
            snapshot.imageIpVerdicts[endpoint] != JmSpeedTester.ImgVerdict.OK
    }
    val imageRows = JmSpeedTestLogPolicy.rankEndpoints(snapshot.imageIpDelaysMs, failedImageEndpoints)

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
                        snapshot.completedAtEpochMs?.let { "最近完成：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))}" }
                            ?: "暂无完整禁漫测速结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    snapshot.remoteVersion?.let { Text("远程配置版本：$it", style = MaterialTheme.typography.bodySmall) }
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
                    ) { Text(if (running) "测速中…" else "重新测速") }
                }
            }
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
                item { Text("暂无线路端点记录", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            items(lineRows) { endpoint ->
                val delay = snapshot.lineIpDelaysMs.getValue(endpoint)
                val isSelectedEndpoint = JmSpeedTestLogPolicy.isSelectedEndpoint(endpoint, selectedLineEndpoint)
                EndpointLogRow(
                    endpoint = endpoint,
                    delay = delay,
                    verdict = null,
                    marker = if (isSelectedEndpoint) "当前实际 API 端点" else null,
                    onClick = { JmRouteManager.selectLineEndpoint(endpoint.domain, endpoint.ip) },
                )
            }
            item { Text("图源端点（域名 · IP）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (imageRows.isEmpty()) {
                item { Text("暂无图源端点记录", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            items(imageRows) { endpoint ->
                val delay = snapshot.imageIpDelaysMs.getValue(endpoint)
                val isSelected = selectedImageEndpoint == endpoint
                EndpointLogRow(
                    endpoint = endpoint,
                    delay = delay,
                    verdict = snapshot.imageIpVerdicts[endpoint],
                    marker = if (isSelected) "当前实际图源端点" else null,
                )
            }
        }
    }
}

@Composable
private fun EndpointLogRow(endpoint: DomainIp, delay: Long, verdict: JmSpeedTester.ImgVerdict?, marker: String?, onClick: (() -> Unit)? = null) {
    val result = when {
        verdict == JmSpeedTester.ImgVerdict.NO_IMAGE -> "无图"
        verdict == JmSpeedTester.ImgVerdict.UNREACHABLE -> "不可达"
        delay == Long.MAX_VALUE -> "失败"
        else -> "${delay}ms"
    }
    Text(
        "${endpoint.domain} · ${endpoint.ip}    $result${marker?.let { "    [$it]" }.orEmpty()}",
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (marker != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            delay == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
            marker != null -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        fontWeight = if (marker != null) FontWeight.SemiBold else FontWeight.Normal,
    )
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
            JmSpeedTestLogPolicy.rankEndpoints(snapshot.lineIpDelaysMs).map { endpoint ->
                endpointJson(
                    endpoint = endpoint,
                    delay = snapshot.lineIpDelaysMs.getValue(endpoint),
                    verdict = null,
                )
            },
        ),
    )
    put(
        "imageEndpoints",
        JsonArray(
            JmSpeedTestLogPolicy.rankEndpoints(
                snapshot.imageIpDelaysMs,
                snapshot.imageIpDelaysMs.keys.filterTo(mutableSetOf()) { endpoint ->
                    snapshot.imageIpDelaysMs.getValue(endpoint) == Long.MAX_VALUE ||
                        snapshot.imageIpVerdicts[endpoint] != JmSpeedTester.ImgVerdict.OK
                },
            ).map { endpoint ->
                endpointJson(
                    endpoint = endpoint,
                    delay = snapshot.imageIpDelaysMs.getValue(endpoint),
                    verdict = snapshot.imageIpVerdicts[endpoint],
                    selected = selectedImageEndpoint == endpoint,
                    successful = snapshot.imageIpDelaysMs.getValue(endpoint) < Long.MAX_VALUE &&
                        snapshot.imageIpVerdicts[endpoint] == JmSpeedTester.ImgVerdict.OK,
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
): JsonObject = buildJsonObject {
    put("domain", JsonPrimitive(endpoint.domain))
    put("ip", JsonPrimitive(endpoint.ip))
    put("latencyMs", if (delay < 0L || delay == Long.MAX_VALUE) JsonNull else JsonPrimitive(delay))
    put("success", JsonPrimitive(successful))
    put("imageVerdict", verdict?.let { JsonPrimitive(it.name.lowercase(java.util.Locale.ROOT)) } ?: JsonNull)
    selected?.let { put("selected", JsonPrimitive(it)) }
}
