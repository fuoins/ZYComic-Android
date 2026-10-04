package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

/**
 * 高级设置页面：开发者配置、本地代理、更新网络配置、测速日志入口、崩溃日志入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    vm: SettingsViewModel,
    onClose: () -> Unit,
    onOpenSpeedTest: () -> Unit,
) {
    val context = LocalContext.current
    val proxyEnabled by vm.proxyEnabled.collectAsState()
    val devJson by vm.devConfigJson.collectAsState()

    var showDevConfig by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.toast.collect { msg ->
            if (msg != null) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                vm.toast.value = null
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("高级") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding) {
            item {
                TextPreferenceWidget(
                    title = "开发者设置(JSON配置)",
                    subtitle = "自定义代理规则与SNI域名",
                    onPreferenceClick = { showDevConfig = true },
                )
            }
            item {
                SwitchPreferenceWidget(
                    title = "本地代理（SNI绕过）",
                    subtitle = "开启=本地代理MITM（稳定），关闭=自定义DNS+SSLSocketFactory（抓包时用）。切换后需重启App生效。",
                    checked = proxyEnabled,
                    onCheckedChanged = { vm.setProxyEnabled(it) },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "更新网络配置(阿里云DoH)",
                    subtitle = "通过阿里云 DNS over HTTPS 刷新 IP 列表",
                    onPreferenceClick = { vm.updateNetworkConfig("alidns") },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "更新网络配置(腾讯DoH)",
                    subtitle = "通过腾讯 DNS over HTTPS 刷新 IP 列表",
                    onPreferenceClick = { vm.updateNetworkConfig("tencent") },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "测速日志",
                    onPreferenceClick = onOpenSpeedTest,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "崩溃日志",
                    onPreferenceClick = {
                        Toast.makeText(context, "即将上线", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    if (showDevConfig) {
        Dialog(
            onDismissRequest = { showDevConfig = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding(),
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "开发者配置",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showDevConfig = false }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider()

                // 格式说明
                Text(
                    "格式: {\"port\":7891,\"rule\":{\"域名\":[\"ip1\",\"ip2\"]},\"sni\":[\"域名1\",\"域名2\"]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                // JSON 编辑区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    OutlinedTextField(
                        value = devJson,
                        onValueChange = { vm.devConfigJson.value = it },
                        label = { Text("开发者配置 (JSON)") },
                        modifier = Modifier.fillMaxSize(),
                        minLines = 8,
                        maxLines = 20,
                    )
                }

                // 底部按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { showDevConfig = false },
                        modifier = Modifier.weight(1f),
                    ) { Text("关闭") }
                    Button(
                        onClick = { vm.saveDevConfig(devJson) },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存配置") }
                }
            }
        }
    }
}
