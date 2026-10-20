package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.DomainIp
import com.zycomic.app.net.PicacgRouteManager
import com.zycomic.app.net.PicacgSpeedTester
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
    val running = remember { mutableStateOf(false) }
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

    val apiRows = snapshot?.apiProbes.orEmpty().sortedWith(compareBy({ if (it.success) it.elapsedMs else Long.MAX_VALUE }, { it.endpoint.domain }, { it.endpoint.ip }))
    val imageRows = snapshot?.imageProbes.orEmpty().sortedWith(compareBy({ if (it.success) it.elapsedMs else Long.MAX_VALUE }, { it.endpoint.domain }, { it.endpoint.ip }))

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
                        snapshot?.let { "测速完成：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it.completedAtEpochMs))}" }
                            ?: "暂无哔咔测速结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("当前线路：${routeState.apiUrls.getOrNull(routeState.apiIndex).orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    Text("当前图源：${routeState.imageHost.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    routeState.remoteVersion?.let { Text("远程配置版本：$it", style = MaterialTheme.typography.bodySmall) }
                    snapshot?.imageBlockedReason?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    status.value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (running.value) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator()
                            val current = progress
                            Text(
                                if (current != null && current.total > 0) {
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
                            enabled = !running.value && routeState.apiUrls.isNotEmpty(),
                            onClick = {
                                running.value = true
                                status.value = "测速中；结果将在本轮检测结束后更新。"
                                scope.launch {
                                    runCatching { PicacgSpeedTester.run() }
                                        .onSuccess { result ->
                                            status.value = "完成：线路 ${result.apiProbes.count { it.success }}/${result.apiProbes.size} 可用；图源 ${result.imageProbes.count { it.success }}/${result.imageProbes.size} 验证成功。"
                                        }
                                        .onFailure { error -> status.value = "测速失败：${error.message ?: "未知错误"}" }
                                    running.value = false
                                }
                            },
                        ) { Text(if (running.value) "测速中…" else "完整重新测速") }
                        Button(
                            enabled = snapshot != null && !running.value,
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
            item { Text("线路端点（域名 · IP；点击选择API线路）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (apiRows.isEmpty()) {
                items(routeState.apiUrls) { url ->
                    val selected = routeState.apiUrls.getOrNull(routeState.apiIndex) == url
                    EndpointRow(
                        label = "${java.net.URI(url).host.orEmpty()} · 尚未测速",
                        selected = selected,
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
                    EndpointRow(
                        label = "${probe.endpoint.domain} · ${probe.endpoint.ip}    ${if (probe.success) "${probe.elapsedMs}ms" else "失败${probe.httpStatus?.let { " · HTTP $it" }.orEmpty()}"}",
                        selected = selected,
                        onClick = {
                            routeState.apiUrls.firstOrNull { runCatching { java.net.URI(it).host.equals(probe.endpoint.domain, true) }.getOrDefault(false) }?.let { PicacgRouteManager.selectApiUrl(it) }
                            PicacgRouteManager.pinApiEndpoint(probe.endpoint)
                            status.value = "已选择API线路 ${probe.endpoint.domain} · ${probe.endpoint.ip}"
                        },
                    )
                }
            }
            item { Text("图源端点（域名 · IP；点击选择图源）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (imageRows.isEmpty()) {
                items(routeState.imageHosts) { host ->
                    EndpointRow(
                        label = "$host · 尚未测速",
                        selected = routeState.imageHost.equals(host, true),
                        onClick = {
                            PicacgRouteManager.selectImageHost(host)
                            status.value = "已选择图源 $host"
                        },
                    )
                }
            } else {
                items(imageRows) { probe ->
                    val selected = routeState.imageHost.equals(probe.endpoint.domain, true) && routeState.pinnedImage == probe.endpoint
                    val result = when {
                        probe.success -> "${probe.elapsedMs}ms · 图片已解码"
                        probe.httpStatus != null -> "失败 · HTTP ${probe.httpStatus}"
                        else -> "失败"
                    }
                    EndpointRow(
                        label = "${probe.endpoint.domain} · ${probe.endpoint.ip}    $result",
                        selected = selected,
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

@Composable
private fun EndpointRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
