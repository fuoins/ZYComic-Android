package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.ui.components.FilterChip
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

/**
 * 标签屏蔽页面：过滤屏蔽标签开关 + 添加/删除屏蔽标签入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagBlockScreen(
    vm: SettingsViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val filterEnabled by vm.filterEnabled.collectAsState()
    val blockedTags by vm.blockedTags.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadBlockedTags()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标签屏蔽") },
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
                SwitchPreferenceWidget(
                    title = "过滤屏蔽标签",
                    subtitle = "对屏蔽的标签漫画进行隐藏(正常会显示404)。建议登录后配合gay标签一键屏蔽使用。开启后部分分类会有大量屏蔽内容，为凑够布局会多获取几页，加载变慢属正常现象。建议选择[一般向]或[禁漫]或[搜索]，[排行]基本都是gay标签内容。如果你点击[BL向]只有两本属于正常，因为获取十页全屏蔽了。",
                    checked = filterEnabled,
                    onCheckedChanged = { vm.setFilterEnabled(it) },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "添加屏蔽标签",
                    subtitle = "搜索标签后多选加入屏蔽列表",
                    onPreferenceClick = { showAddDialog = true },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "删除屏蔽标签",
                    subtitle = "已屏蔽 ${blockedTags.size} 个标签",
                    onPreferenceClick = { showRemoveDialog = true },
                )
            }
        }
    }

    if (showAddDialog) {
        AddBlacklistDialog(vm = vm, onDismiss = { showAddDialog = false })
    }
    if (showRemoveDialog) {
        RemoveBlacklistDialog(vm = vm, onDismiss = { showRemoveDialog = false })
    }
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
                .verticalScroll(rememberScrollState()),
        ) {
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

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索标签") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

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
                .verticalScroll(rememberScrollState()),
        ) {
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

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索已屏蔽标签") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            val filtered = if (keyword.isBlank()) blockedTags
            else blockedTags.filter { it.contains(keyword, ignoreCase = true) }

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
                loading -> Text(
                    "加载中...",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
