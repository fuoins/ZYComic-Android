package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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

// ==================== C. 标签选择器（重设计） ====================

/** 标签选择 chip：选中 primary 高亮带 ✓，未选 surfaceVariant。仅用于标签选择器，不改全局 FilterChip。 */
@Composable
private fun TagPickChip(
    text: String,
    selected: Boolean,
    showCheck: Boolean = true,
    onClick: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected && showCheck) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 标签多选弹窗（重设计）：搜索 + 已选区 + 自适应懒加载网格 + 底部清空/取消/确定（已选 N）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPickerDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf(initial) }

    val ordered = remember { AllTags.PINNED_TAGS + AllTags.LIST.filterNot { it in AllTags.PINNED_TAGS } }
    val filtered = remember(keyword, ordered) {
        if (keyword.isBlank()) ordered else ordered.filter { it.contains(keyword, ignoreCase = true) }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择标签", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("已选 ${temp.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
            }

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { keyword = "" }) { Icon(Icons.Outlined.Close, contentDescription = "清除") }
                    }
                },
                placeholder = { Text("搜索标签") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (temp.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选（${temp.size}），点击可移除", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { temp = emptySet() }) { Text("清空") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        temp.toList().forEach { tag ->
                            TagPickChip(text = tag, selected = true, showCheck = false) {
                                temp = temp - tag
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 92.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered) { tag ->
                    TagPickChip(text = tag, selected = temp.contains(tag)) {
                        temp = if (temp.contains(tag)) temp - tag else temp + tag
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { temp = emptySet() }) { Text("清空") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConfirm(temp) }) {
                    Text(if (temp.isEmpty()) "确定" else "确定（已选 ${temp.size}）")
                }
            }
        }
    }
}

// ==================== Tab 管理面板 ====================

/** Tab 管理面板（仿筛选面板的 AdaptiveSheet）：LazyColumn + 拖动手柄排序 + 增删改 + 特殊预设添加。 */
@Composable
fun TabManageSheet(vm: BrowseViewModel, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<BrowseTabItem?>(null) }
    var editing by remember { mutableStateOf<BrowseTabItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var tagPicker by remember { mutableStateOf<Pair<Set<String>, (Set<String>) -> Unit>?>(null) }

    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    val handleZone = with(LocalDensity.current) { 44.dp.toPx() }

    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp)) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Tab 管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
            }
            Text(
                "长按拖动右侧手柄可调整顺序，即顶部标签顺序；可改名、改默认筛选、删除，恢复默认回到三个热门。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .pointerInput(handleZone) {
                        fun rowAt(y: Float): Int? =
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { y >= it.offset && y <= it.offset + it.size }
                                ?.index

                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                if (offset.x > size.width - handleZone) {
                                    draggingIndex = rowAt(offset.y)
                                }
                            },
                            onDragEnd = { draggingIndex = null },
                            onDragCancel = { draggingIndex = null },
                            onDrag = { change, _ ->
                                change.consume()
                                val from = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                val target = rowAt(change.position.y) ?: return@detectDragGesturesAfterLongPress
                                if (target != from) {
                                    vm.moveTab(from, target)
                                    draggingIndex = target
                                }
                            },
                        )
                    },
            ) {
                itemsIndexed(vm.tabs, key = { _, it -> it.id }) { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .then(
                                if (draggingIndex == index) {
                                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        if (item.kind == TabKind.FILTER) {
                            IconButton(onClick = { renaming = item }) { Icon(Icons.Outlined.Edit, contentDescription = "改名") }
                            IconButton(onClick = { editing = item }) { Icon(Icons.Outlined.FilterList, contentDescription = "编辑默认筛选") }
                        }
                        IconButton(onClick = { vm.deleteTab(item.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
                        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.DragHandle, contentDescription = "拖动排序", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("增加自定义筛选 tab")
                }
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { vm.addLatestTab() },
                    enabled = vm.canAddLatest(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.canAddLatest()) "增加最新更新" else "已添加最新更新")
                }
                Text(
                    "所有漫画 7 天内的更新；可单独查看 7 天内某一天的全部更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { vm.addRankingTab() },
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

    // ---- 编辑 tab（名称 + 默认筛选） ----
    editing?.let { item ->
        FilterTabFormDialog(
            title = "编辑 tab",
            initialName = item.name,
            initial = item.filter ?: FilterSnapshot(),
            onDismiss = { editing = null },
            checkNameTaken = { nm -> vm.isNameTaken(nm, item.id) },
            onOpenTagDialog = { initSet, cb -> tagPicker = initSet to cb },
            onSave = { nm, snap ->
                vm.updateTabDefaultFilter(item.id, snap)
                if (nm != item.name) vm.renameTab(item.id, nm)
                editing = null
            },
        )
    }

    // ---- 新增自定义筛选 tab ----
    if (adding) {
        FilterTabFormDialog(
            title = "新增筛选 tab",
            initialName = "",
            initial = FilterSnapshot(),
            onDismiss = { adding = false },
            checkNameTaken = { nm -> vm.isNameTaken(nm) },
            onOpenTagDialog = { initSet, cb -> tagPicker = initSet to cb },
            onSave = { nm, snap ->
                if (vm.addFilterTab(nm, snap)) {
                    adding = false
                    onDismiss()
                }
            },
        )
    }

    // ---- 恢复默认确认 ----
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认预设") },
            text = { Text("将清空你新增/改名/修改的 tab，恢复为：一般向热门、禁漫热门、热血热门。确定？") },
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

// ==================== D. 自定义 tab 表单（重设计） ====================

/** 生成筛选摘要文案。 */
private fun FilterSnapshot.summary(): String {
    val g = BrowseViewModel.GENDERS.firstOrNull { it.first == gender }?.second ?: "全部"
    val a = BrowseViewModel.AREAS.firstOrNull { it.first == area }?.second ?: "全部"
    val e = BrowseViewModel.ENDS.firstOrNull { it.first == end }?.second ?: "全部"
    val s = BrowseViewModel.STS.firstOrNull { it.first == st }?.second ?: "收藏"
    val t = if (tags.isEmpty()) "无标签" else tags.joinToString("、")
    return "$g · 标签：$t · $a · $e · ${s}排序"
}

/**
 * 新增/编辑共用的筛选 tab 表单：名称（即时校验）+ 实时摘要 + 筛选配置（标签"更多"进标签选择器）+ 取消/保存。
 */
@Composable
private fun FilterTabFormDialog(
    title: String,
    initialName: String,
    initial: FilterSnapshot,
    onDismiss: () -> Unit,
    onSave: (String, FilterSnapshot) -> Unit,
    onOpenTagDialog: (Set<String>, (Set<String>) -> Unit) -> Unit,
    checkNameTaken: (String) -> Boolean,
) {
    var name by remember(title) { mutableStateOf(initialName) }
    var draft by remember(title) { mutableStateOf(initial) }

    val trimmed = name.trim()
    val nameError = when {
        trimmed.isEmpty() -> "名称不能为空"
        checkNameTaken(trimmed) -> "名称已存在"
        else -> null
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp),
            ) {
                Icon(Icons.Outlined.Label, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Label, contentDescription = null) },
                isError = nameError != null,
                supportingText = { nameError?.let { Text(it) } },
                label = { Text("tab 名称") },
                placeholder = { Text("例如：热血收藏") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    draft.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                FilterSnapshotEditor(
                    snapshot = draft,
                    onChange = { draft = it },
                    onOpenTagDialog = {
                        onOpenTagDialog(draft.tags.toSet()) { set -> draft = draft.copy(tags = set.toList()) }
                    },
                )
                Text(
                    "新 tab 默认按收藏（热门）排序",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = nameError == null, onClick = { onSave(trimmed, draft) }) { Text("保存") }
            }
        }
    }
}
