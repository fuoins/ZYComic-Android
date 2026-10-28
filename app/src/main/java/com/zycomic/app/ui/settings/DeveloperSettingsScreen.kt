package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.net.PlatformSpeedTestCoordinator
import com.zycomic.app.net.RouteManager
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.ui.components.AppSwitch
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    vm: SettingsViewModel,
    onClose: () -> Unit,
    onOpenManwaSpeedTest: () -> Unit,
    onOpenJmSpeedTest: () -> Unit,
    onOpenPicacgSpeedTest: () -> Unit,
    onOpenMaccmsSpeedTest: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val platformReports by PlatformSpeedTestCoordinator.reports.collectAsState()
    val platformTestsRunning by PlatformSpeedTestCoordinator.running.collectAsState()
    val platformExportSuccess = stringResource(KMR.strings.platform_speed_log_export_success)
    val platformExportFailed = stringResource(KMR.strings.platform_speed_log_export_failed)
    val pendingPlatformExport = remember { mutableStateOf<String?>(null) }
    val orderedPlatformReports = remember(platformReports) {
        platformReports.sortedBy { report ->
            when (report.platform) {
                "漫蛙" -> 0
                "哔咔" -> 1
                "禁漫天堂" -> 2
                else -> 3
            }
        }
    }
    val platformExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingPlatformExport.value
        pendingPlatformExport.value = null
        if (uri != null && json != null) {
            scope.launch {
                val success = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                            ?: error("无法打开导出文件")
                    }
                }.isSuccess
                Toast.makeText(
                    context,
                    if (success) platformExportSuccess else platformExportFailed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val snapshot by RouteManager.speedTestSnapshot.collectAsState()
    val picacgSnapshot by com.zycomic.app.net.PicacgSpeedTester.snapshot.collectAsState()
    val picacgRoutes by com.zycomic.app.net.PicacgRouteManager.state.collectAsState()
    val jmSnapshot by JmRouteManager.speedTestSnapshot.collectAsState()
    val manwaDomains = buildAvailableDomainText(
        lineCandidates = RouteManager.lineHosts,
        imageCandidates = RouteManager.imgDomains,
        lineResults = snapshot.lineDelays,
        imageResults = snapshot.imgDelays,
        imageVerdicts = snapshot.imgVerdicts,
    )
    val jmDomains = buildJmAvailableDomainText(jmSnapshot, JmRouteManager.lineHosts, JmRouteManager.imageDomains)
    val validatedPicacgSnapshot = picacgSnapshot?.takeIf { it.revision == com.zycomic.app.net.PicacgRouteManager.configRevision }
    val picacgDomains = buildPicacgAvailableDomainText(
        lineCandidates = picacgRoutes.apiUrls,
        imageCandidates = picacgRoutes.imageHosts,
        successfulLineDomains = validatedPicacgSnapshot?.apiProbes.orEmpty().filter { it.success }.map { it.endpoint.domain }.toSet(),
        successfulImageDomains = validatedPicacgSnapshot?.imageProbes.orEmpty().filter { it.success }.map { it.endpoint.domain }.toSet(),
    )
    val allDomains = selectAvailableDomainText(manwaDomains, jmDomains, AvailableDomainGroup.ALL, picacgDomains)
    val allDomainCount = allDomains.lineSequence().count(String::isNotBlank)
    val pendingExport = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<String, Int>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val export = pendingExport.value ?: return@rememberLauncherForActivityResult
        pendingExport.value = null
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(export.first)
            } ?: error("无法打开导出文件")
        }.onSuccess {
            Toast.makeText(context, "已导出 ${export.second} 个可用域名", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导出失败：${it.message ?: "请重试"}", Toast.LENGTH_LONG).show()
        }
    }

    fun exportAvailableDomains(group: AvailableDomainGroup, filename: String) {
        val text = selectAvailableDomainText(manwaDomains, jmDomains, group, picacgDomains)
        val count = text.lineSequence().count(String::isNotBlank)
        if (text.isBlank()) {
            Toast.makeText(context, "暂无当前配置的测速成功域名，请先完成测速", Toast.LENGTH_SHORT).show()
        } else {
            pendingExport.value = text to count
            exportLauncher.launch(filename)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("开发者设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "下列工具集中展示和操作测速诊断；域名导出只取各平台最近一次测速成功的域名，不会重新测速。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(KMR.strings.platform_speed_log_title), style = MaterialTheme.typography.titleMedium)
                    orderedPlatformReports.forEach { report ->
                        Text(report.summary(), modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        Text("实际线路：${report.selectedLine}", style = MaterialTheme.typography.bodySmall)
                        Text("实际图源：${report.selectedImage}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "线路候选 ${report.lineCandidates.size} 项 · 图源候选 ${report.imageCandidates.size} 项 · 配置 ${report.remoteVersion?.let { "v$it" } ?: "未知"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        enabled = orderedPlatformReports.size == 3 && !platformTestsRunning,
                        onClick = {
                            pendingPlatformExport.value = PlatformSpeedTestCoordinator.reportsJson(orderedPlatformReports).toString(2)
                            platformExportLauncher.launch("zycomic-platform-speed-test.json")
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(KMR.strings.platform_speed_log_export)) }
                }
            }
            item {
                val startupPolicy by StartupPlatformPolicy.state.collectAsState()
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(stringResource(KMR.strings.startup_history_controls_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(KMR.strings.startup_history_controls_summary),
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StartupHistoryPreference(
                        title = stringResource(KMR.strings.startup_use_last_speed_test),
                        checked = startupPolicy.useLastResult,
                        onCheckedChange = { StartupPlatformPolicy.setUseLastResult(it) },
                    )
                    StartupHistoryPreference(
                        title = stringResource(KMR.strings.startup_use_last_line_speed_test),
                        checked = startupPolicy.useLastLineResult,
                        onCheckedChange = { StartupPlatformPolicy.setUseLastLineResult(it) },
                    )
                }
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.manwa_speed_log_title),
                    subtitle = "查看线路与图源域名/IP、导出诊断或重新测速",
                    icon = Icons.Outlined.Speed,
                    onPreferenceClick = onOpenManwaSpeedTest,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.picacg_speed_log_title),
                    subtitle = "查看哔咔独立线路与图源域名/IP、测速结果并导出诊断",
                    icon = Icons.Outlined.Speed,
                    onPreferenceClick = onOpenPicacgSpeedTest,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.maccms_speed_log_title),
                    subtitle = stringResource(KMR.strings.maccms_speed_log_summary),
                    icon = Icons.Outlined.Speed,
                    onPreferenceClick = onOpenMaccmsSpeedTest,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.jm_speed_log_title),
                    subtitle = "查看禁漫天堂线路与图源域名/IP、导出诊断或重新测速",
                    icon = Icons.Outlined.Speed,
                    onPreferenceClick = onOpenJmSpeedTest,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "检查配置更新",
                    subtitle = "检查线路与图源远程配置",
                    icon = Icons.Outlined.Refresh,
                    onPreferenceClick = onCheckUpdates,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "导出可用域名 · 全部",
                    subtitle = if (allDomainCount == 0) "暂无可用域名" else "漫蛙、哔咔与禁漫天堂（$allDomainCount 行域名）",
                    icon = Icons.Outlined.Download,
                    onPreferenceClick = { exportAvailableDomains(AvailableDomainGroup.ALL, "available_domains_all.txt") },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "导出可用域名 · 漫蛙",
                    subtitle = "保存漫蛙测速成功的线路与图源域名",
                    icon = Icons.Outlined.Download,
                    onPreferenceClick = { exportAvailableDomains(AvailableDomainGroup.MANWA, "available_domains_manwa.txt") },
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(KMR.strings.picacg_export_domains),
                    subtitle = stringResource(KMR.strings.picacg_export_domains_hint),
                    icon = Icons.Outlined.Download,
                    onPreferenceClick = { exportAvailableDomains(AvailableDomainGroup.PICACG, "available_domains_picacg.txt") },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "导出可用域名 · 禁漫天堂",
                    subtitle = "保存禁漫天堂测速成功的线路与图源域名",
                    icon = Icons.Outlined.Download,
                    onPreferenceClick = { exportAvailableDomains(AvailableDomainGroup.JM, "available_domains_jm.txt") },
                )
            }
            item { ManwaDeveloperControls(vm) }
            item {
                TextPreferenceWidget(
                    title = "复制全部可用域名",
                    subtitle = if (allDomainCount == 0) "暂无可用域名" else "复制漫蛙、哔咔与禁漫天堂测速成功域名",
                    icon = Icons.Outlined.ContentCopy,
                    onPreferenceClick = {
                        if (allDomains.isBlank()) {
                            Toast.makeText(context, "暂无当前配置的测速成功域名，请先完成测速", Toast.LENGTH_SHORT).show()
                        } else {
                            context.copyToClipboard("可用域名", allDomains)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StartupHistoryPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
