package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.AllTags
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.FilterSection
import eu.kanade.presentation.components.AdaptiveSheet

/**
 * 通用筛选快照编辑器（性向/标签/地区/状态/排序），纯受控：
 * 既用于「重新筛选」（绑定当前 tab 临时态），也用于管理面板的"编辑默认筛选 / 新增 tab"。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSnapshotEditor(
    snapshot: FilterSnapshot,
    onChange: (FilterSnapshot) -> Unit,
    onOpenTagDialog: () -> Unit,
) {
    fun toggleTag(tag: String) {
        val set = snapshot.tags.toMutableSet()
        if (!set.add(tag)) set.remove(tag)
        onChange(snapshot.copy(tags = set.toList()))
    }

    FilterSection("性向") {
        BrowseViewModel.GENDERS.forEach { (v, label) ->
            FilterChip(text = label, selected = snapshot.gender == v, onClick = { onChange(snapshot.copy(gender = v)) })
        }
    }
    FilterSection("标签") {
        FilterChip(text = "全部", selected = snapshot.tags.isEmpty(), onClick = { onChange(snapshot.copy(tags = emptyList())) })
        AllTags.PINNED_TAGS.forEach { tag ->
            FilterChip(text = tag, selected = snapshot.tags.contains(tag), onClick = { toggleTag(tag) })
        }
        snapshot.tags.filter { it !in AllTags.PINNED_TAGS }.take(3).forEach { tag ->
            FilterChip(text = tag, selected = true, onClick = { toggleTag(tag) })
        }
        FilterChip(text = "更多", selected = false, bold = true, onClick = onOpenTagDialog)
    }
    FilterSection("地区") {
        BrowseViewModel.AREAS.forEach { (v, label) ->
            FilterChip(text = label, selected = snapshot.area == v, onClick = { onChange(snapshot.copy(area = v)) })
        }
    }
    FilterSection("状态") {
        BrowseViewModel.ENDS.forEach { (v, label) ->
            FilterChip(text = label, selected = snapshot.end == v, onClick = { onChange(snapshot.copy(end = v)) })
        }
    }
    FilterSection("排序方式") {
        BrowseViewModel.STS.forEach { (v, label) ->
            FilterChip(text = label, selected = snapshot.st == v, onClick = { onChange(snapshot.copy(st = v)) })
        }
    }
}

/** 标签多选弹窗（受控，初始集合 + 确定回调）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPickerDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf(initial) }
    val allTags = AllTags.LIST
    val filtered = remember(keyword, allTags) {
        if (keyword.isBlank()) allTags else allTags.filter { it.contains(keyword, ignoreCase = true) }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择标签", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("已选 ${temp.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                singleLine = true,
                placeholder = { Text("搜索标签") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    filtered.forEach { tag ->
                        FilterChip(
                            text = tag,
                            selected = temp.contains(tag),
                            onClick = { temp = if (temp.contains(tag)) temp - tag else temp + tag },
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { temp = emptySet() }) { Text("重置") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConfirm(temp) }) { Text("确定") }
            }
        }
    }
}

/** Tab 管理面板（仿筛选面板的 AdaptiveSheet）。 */
@Composable
fun TabManageSheet(vm: BrowseViewModel, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<BrowseTabItem?>(null) }
    var editingFilter by remember { mutableStateOf<BrowseTabItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // 标签选择器状态：初始已选集合 + 确定回调；非空时弹出选择器。
    var tagPicker by remember { mutableStateOf<Pair<Set<String>, (Set<String>) -> Unit>?>(null) }

    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Tab 管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
            }
            Text(
                "可改名、修改默认筛选、删除筛选 tab；内置「最近更新」不可删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            vm.tabs.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (vm.isProtected(item)) {
                        Text(
                            "内置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    } else {
                        if (item.kind == TabKind.FILTER) {
                            IconButton(onClick = { renaming = item }) { Icon(Icons.Outlined.Edit, contentDescription = "改名") }
                            IconButton(onClick = { editingFilter = item }) { Icon(Icons.Outlined.FilterList, contentDescription = "编辑默认筛选") }
                        }
                        IconButton(onClick = { vm.deleteTab(item.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Button(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("增加自定义筛选 tab")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { if (vm.canAddRanking()) { vm.addRankingTab(); onDismiss() } },
                enabled = vm.canAddRanking(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (vm.canAddRanking()) "增加 gay排行" else "gay排行已添加")
            }
            Text(
                "整体排行，但女用户较多导致 gay 漫画偏多，建议使用宇总排行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { confirmReset = true }) { Text("恢复默认预设") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ---- 改名 ----
    renaming?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        var error by remember(item.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名 tab") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    label = { Text("tab 名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (vm.renameTab(item.id, name)) renaming = null else error = "名称为空或已存在"
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    // ---- 编辑默认筛选 ----
    editingFilter?.let { item ->
        FilterEditDialog(
            title = "编辑默认筛选 · ${item.name}",
            initial = item.filter ?: FilterSnapshot(),
            onOpenTagDialog = { initSet, cb -> tagPicker = initSet to cb },
            onDismiss = { editingFilter = null },
            onSave = { snap ->
                vm.updateTabDefaultFilter(item.id, snap)
                editingFilter = null
            },
        )
    }

    // ---- 新增自定义筛选 tab ----
    if (adding) {
        var name by remember { mutableStateOf("") }
        var draft by remember { mutableStateOf(FilterSnapshot()) }
        var error by remember { mutableStateOf<String?>(null) }
        FilterEditDialog(
            title = "增加自定义筛选 tab",
            initial = draft,
            nameField = name,
            onNameChange = { name = it; error = null },
            nameError = error,
            onOpenTagDialog = { initSet, cb -> tagPicker = initSet to cb },
            onDismiss = { adding = false },
            onSave = { snap ->
                if (vm.addFilterTab(name, snap)) {
                    adding = false
                    onDismiss()
                } else {
                    error = "名称为空或已存在"
                    draft = snap
                }
            },
        )
    }

    // ---- 恢复默认确认 ----
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认预设") },
            text = { Text("将清空你新增/改名/修改的 tab，恢复为：一般向热门、禁漫热门、热血热门、最近更新。确定？") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.resetToDefaults(); onDismiss() }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }

    // ---- 标签选择器 ----
    tagPicker?.let { (initSet, cb) ->
        TagPickerDialog(
            initial = initSet,
            onDismiss = { tagPicker = null },
            onConfirm = { set -> cb(set); tagPicker = null },
        )
    }
}

/**
 * 筛选编辑对话框：标题 + 可选名称输入框 + 受控筛选编辑器 + 取消/保存。
 * 标签"更多"通过 [onOpenTagDialog] 抛出一个回传集合的回调，由宿主统一弹出 [TagPickerDialog]。
 */
@Composable
private fun FilterEditDialog(
    title: String,
    initial: FilterSnapshot,
    onDismiss: () -> Unit,
    onSave: (FilterSnapshot) -> Unit,
    onOpenTagDialog: (Set<String>, (Set<String>) -> Unit) -> Unit,
    nameField: String? = null,
    onNameChange: ((String) -> Unit)? = null,
    nameError: String? = null,
) {
    var draft by remember(title) { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (nameField != null && onNameChange != null) {
                OutlinedTextField(
                    value = nameField,
                    onValueChange = onNameChange,
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = { nameError?.let { Text(it) } },
                    label = { Text("tab 名称") },
                    placeholder = { Text("例如：热血收藏") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                FilterSnapshotEditor(
                    snapshot = draft,
                    onChange = { draft = it },
                    onOpenTagDialog = {
                        onOpenTagDialog(draft.tags.toSet()) { set -> draft = draft.copy(tags = set.toList()) }
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(draft) }) { Text("保存") }
            }
        }
    }
}
