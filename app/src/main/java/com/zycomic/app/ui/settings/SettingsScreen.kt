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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.net.RouteManager
import com.zycomic.app.ui.components.FilterChip

@Composable
fun SettingsScreen() {
    val vm = remember { SettingsViewModel() }
    val context = LocalContext.current
    val filterEnabled by vm.filterEnabled.collectAsState()
    val allTags by vm.allTags.collectAsState()
    val blockedTags by vm.blockedTags.collectAsState()
    val testing by vm.testing.collectAsState()
    val lineDelays by vm.lineDelays.collectAsState()
    val imgDelays by vm.imgDelays.collectAsState()
    val currentLineIdx by vm.currentLineIndex.collectAsState()
    val currentImgIdx by vm.currentImgIndex.collectAsState()
    val updateTime by vm.configUpdateTime.collectAsState()

    var showGayConfirm by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

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
            Text("网络配置更新时间：$updateTime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 展开状态：key = "line_0" / "img_0"
            var expandedHosts by remember { mutableStateOf<Set<String>>(emptySet()) }

            // 线路列表
            Text("线路（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            RouteManager.lineHosts.forEachIndexed { index, url ->
                val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/')
                val delay = lineDelays[index]
                val hostIpMap: Map<String, Long>? = null
                val fastest = hostIpMap?.filterValues { it < Long.MAX_VALUE }?.minByOrNull { it.value }
                val expandKey = "line_$index"
                val isExpanded = expandedHosts.contains(expandKey)

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { vm.selectLine(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}. $host",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == currentLineIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // HTTP/域名延迟
                        Text(
                            text = " HTTP:${formatDelay(delay)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                delay == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                delay == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                                delay < 300 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        // 最快IP + TCP延迟
                        if (fastest != null) {
                            Text(
                                text = " 最快IP:${fastest.key}(${fastest.value}ms)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index == currentLineIdx) {
                            Text(" ←", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        // 展开/收起箭头
                        if (!hostIpMap.isNullOrEmpty()) {
                            Text(
                                text = if (isExpanded) " ▲" else " ▼",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable {
                                    expandedHosts = if (isExpanded) expandedHosts - expandKey else expandedHosts + expandKey
                                },
                            )
                        }
                    }
                    // 展开：所有IP的TCP延迟
                    if (isExpanded && !hostIpMap.isNullOrEmpty()) {
                        hostIpMap.forEach { (ip, tcpDelay) ->
                            Text(
                                text = "  $ip: ${formatDelay(tcpDelay)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    tcpDelay == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                                    tcpDelay < 300 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(start = 24.dp, top = 1.dp),
                            )
                        }
                    }
                }
            }

            // 图源列表
            Text("图源（点击切换）：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            RouteManager.imgDomains.forEachIndexed { index, domain ->
                val delay = imgDelays[index]
                val hostIpMap: Map<String, Long>? = null
                val fastest = hostIpMap?.filterValues { it < Long.MAX_VALUE }?.minByOrNull { it.value }
                val expandKey = "img_$index"
                val isExpanded = expandedHosts.contains(expandKey)

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { vm.selectImgHost(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}. $domain",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == currentImgIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = " HTTP:${formatDelay(delay)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                delay == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                delay == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                                delay < 300 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (fastest != null) {
                            Text(
                                text = " 最快IP:${fastest.key}(${fastest.value}ms)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index == currentImgIdx) {
                            Text(" ←", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (!hostIpMap.isNullOrEmpty()) {
                            Text(
                                text = if (isExpanded) " ▲" else " ▼",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable {
                                    expandedHosts = if (isExpanded) expandedHosts - expandKey else expandedHosts + expandKey
                                },
                            )
                        }
                    }
                    if (isExpanded && !hostIpMap.isNullOrEmpty()) {
                        hostIpMap.forEach { (ip, tcpDelay) ->
                            Text(
                                text = "  $ip: ${formatDelay(tcpDelay)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    tcpDelay == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                                    tcpDelay < 300 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(start = 24.dp, top = 1.dp),
                            )
                        }
                    }
                }
            }

            Button(onClick = { vm.runSpeedTest() }, enabled = !testing, modifier = Modifier.padding(top = 8.dp)) {
                Text(if (testing) "测速中..." else "重新测速")
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
            vm = vm,
            onDismiss = { showAddDialog = false },
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

/** 延迟显示：未测="--"，失败="超时"，否则="123ms" */
private fun formatDelay(delay: Long?): String = when {
    delay == null -> "--"
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

// ==================== 添加屏蔽标签弹窗 ====================

@Composable
private fun AddBlacklistDialog(
    vm: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val allTags by vm.allTags.collectAsState()
    val adding by vm.addingBlacklist.collectAsState()

    var keyword by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

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
                    "添加屏蔽标签（已选 ${selected.size}）",
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
                placeholder = { Text("搜索标签") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            // 标签流
            val filtered = if (keyword.isBlank()) allTags
            else allTags.filter { it.contains(keyword, ignoreCase = true) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                filtered.forEach { tag ->
                    FilterChip(
                        text = tag,
                        selected = selected.contains(tag),
                        onClick = {
                            selected = if (selected.contains(tag)) selected - tag else selected + tag
                        },
                    )
                }
            }

            // 底部按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {
                    selected = emptySet()
                    keyword = ""
                }) { Text("重置") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !adding,
                    onClick = {
                        if (selected.isEmpty()) {
                            Toast.makeText(context, "请先选择标签", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        vm.submitAddBlacklist(selected.toList()) {
                            onDismiss()
                        }
                    },
                ) { Text(if (adding) "添加中..." else "确认添加 (${selected.size})") }
            }
        }
    }
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

            val filtered = if (keyword.isBlank()) blockedTags
            else blockedTags.filter { it.contains(keyword, ignoreCase = true) }

            // 全选按钮（搜索框下方）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        allSelected = !allSelected
                        selected = if (allSelected) filtered.toSet()
                        else selected - filtered.toSet()
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
