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
    val routeLogs by JmRouteManager.logs.collectAsState()
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
    val lineRows = snapshot.lineIpDelaysMs.entries.sortedWith(compareBy({ it.key.domain }, { it.key.ip }))
    val imageRows = snapshot.imageIpDelaysMs.entries.sortedWith(compareBy({ it.key.domain }, { it.key.ip }))

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
                    status.value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(
                        enabled = snapshot.completedAtEpochMs != null && !running,
                        onClick = {
                            val json = runCatching { encodeJmSpeedTestLog(snapshot, routeLogs) }.getOrNull()
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
            item { Text("线路端点（域名 · IP）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (lineRows.isEmpty()) {
                item { Text("暂无线路端点记录", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            items(lineRows) { (endpoint, delay) -> EndpointLogRow(endpoint, delay, null) }
            item { Text("图源端点（域名 · IP）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (imageRows.isEmpty()) {
                item { Text("暂无图源端点记录", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            }
            items(imageRows) { (endpoint, delay) -> EndpointLogRow(endpoint, delay, snapshot.imageIpVerdicts[endpoint]) }
        }
    }
}

@Composable
private fun EndpointLogRow(endpoint: DomainIp, delay: Long, verdict: JmSpeedTester.ImgVerdict?) {
    val result = when {
        verdict == JmSpeedTester.ImgVerdict.NO_IMAGE -> "无图"
        verdict == JmSpeedTester.ImgVerdict.UNREACHABLE -> "不可达"
        delay == Long.MAX_VALUE -> "失败"
        else -> "${delay}ms"
    }
    Text(
        "${endpoint.domain} · ${endpoint.ip}    $result",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (delay == Long.MAX_VALUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

private fun encodeJmSpeedTestLog(
    snapshot: JmRouteManager.SpeedTestSnapshot,
    routeLogs: List<JmRouteManager.LogEntry>,
): String = buildJsonObject {
    put("platform", JsonPrimitive("禁漫天堂"))
    put("startedAtEpochMs", snapshot.startedAtEpochMs?.let(::JsonPrimitive) ?: JsonNull)
    put("completedAtEpochMs", snapshot.completedAtEpochMs?.let(::JsonPrimitive) ?: JsonNull)
    put("remoteConfigVersion", snapshot.remoteVersion?.let(::JsonPrimitive) ?: JsonNull)
    put("configFingerprint", snapshot.configFingerprint?.let(::JsonPrimitive) ?: JsonNull)
    put("imageConfigFingerprint", snapshot.imageConfigFingerprint?.let(::JsonPrimitive) ?: JsonNull)
    put("imageNetworkPolicy", snapshot.imageNetworkPolicy?.let(::JsonPrimitive) ?: JsonNull)
    put("imageResultsReused", JsonPrimitive(snapshot.imageResultsReused))
    put(
        "lineResults",
        buildJsonObject {
            snapshot.lineDelaysMs.toSortedMap().forEach { (host, delay) ->
                put(host, if (delay == Long.MAX_VALUE) JsonNull else JsonPrimitive(delay))
            }
        },
    )
    put(
        "imageResults",
        buildJsonObject {
            snapshot.imageDelaysMs.toSortedMap().forEach { (host, delay) ->
                put(host, if (delay == Long.MAX_VALUE) JsonNull else JsonPrimitive(delay))
            }
        },
    )
    put(
        "lineEndpoints",
        JsonArray(
            snapshot.lineIpDelaysMs.entries.sortedWith(compareBy({ it.key.domain }, { it.key.ip })).map { (endpoint, delay) ->
                endpointJson(endpoint, delay, null)
            },
        ),
    )
    put(
        "imageEndpoints",
        JsonArray(
            snapshot.imageIpDelaysMs.entries.sortedWith(compareBy({ it.key.domain }, { it.key.ip })).map { (endpoint, delay) ->
                endpointJson(endpoint, delay, snapshot.imageIpVerdicts[endpoint])
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

private fun endpointJson(endpoint: DomainIp, delay: Long, verdict: JmSpeedTester.ImgVerdict?): JsonObject = buildJsonObject {
    put("domain", JsonPrimitive(endpoint.domain))
    put("ip", JsonPrimitive(endpoint.ip))
    put("latencyMs", if (delay == Long.MAX_VALUE) JsonNull else JsonPrimitive(delay))
    put("success", JsonPrimitive(delay < Long.MAX_VALUE && verdict != JmSpeedTester.ImgVerdict.UNREACHABLE && verdict != JmSpeedTester.ImgVerdict.NO_IMAGE))
    put("imageVerdict", verdict?.let { JsonPrimitive(it.name.lowercase(java.util.Locale.ROOT)) } ?: JsonNull)
}
