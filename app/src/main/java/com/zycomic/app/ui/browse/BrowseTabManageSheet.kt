package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zycomic.app.data.AllTags
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformSelector
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.FilterSection
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

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
            FilterChip(
                text = if (v == 1) stringResource(KMR.strings.content_platform_filter_jm) else label,
                selected = snapshot.gender == v,
                onClick = { onChange(snapshot.copy(gender = v)) },
            )
        }
    }
    FilterSection("标签") {
        FilterChip(text = "全部", selected = snapshot.tags.isEmpty(), onClick = { onChange(snapshot.copy(tags = emptyList())) })
        AllTags.PINNED_TAGS.forEach { tag ->
            FilterChip(text = tag, selected = snapshot.tags.contains(tag), onClick = { toggleTag(tag) })
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

/** 标签多选弹窗：搜索 + 已选区 + 分类标签与原因说明 + 底部清空/取消/确定。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPickerDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf(initial) }
    var reason by remember { mutableStateOf<Pair<String, String>?>(null) }
    val filteredGroups = remember(keyword) {
        TagCategoryData.GROUPS.mapNotNull { group ->
            val sections = group.sections.mapNotNull { section ->
                val tags = if (keyword.isBlank()) section.tags else section.tags.filter { it.contains(keyword, ignoreCase = true) }
                section.copy(tags = tags).takeIf { tags.isNotEmpty() }
            }
            group.copy(sections = sections).takeIf { sections.isNotEmpty() }
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxHeight(0.92f).widthIn(max = 460.dp).fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("选择标签（可多选）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )

                    if (temp.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("已选（${temp.size}），点击移除", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { temp = emptySet() }) { Text("清空") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            temp.toList().forEach { tag ->
                                com.zycomic.app.ui.components.FilterChip(text = tag, selected = true) { temp = temp - tag }
                            }
                        }
                        HorizontalDivider()
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                    ) {
                        if (filteredGroups.isEmpty()) {
                            Text("没有匹配的标签", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        filteredGroups.forEach { group ->
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 5.dp, bottom = 1.dp),
                            )
                            group.sections.forEach { section ->
                                Row(
                                    modifier = Modifier.padding(top = 2.dp, bottom = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 2.dp)
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(percent = 50))
                                            .clickable { reason = section.title to section.reason },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Info,
                                            contentDescription = "${section.title}分类原因",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    section.tags.forEach { tag ->
                                        FilterChip(text = tag, selected = temp.contains(tag)) {
                                            temp = if (temp.contains(tag)) temp - tag else temp + tag
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
    }
    reason?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { reason = null },
            title = { Text(title, style = MaterialTheme.typography.titleSmall) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { reason = null }) { Text("确认") } },
        )
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
    var deletingItem by remember { mutableStateOf<BrowseTabItem?>(null) }
    var tagPicker by remember { mutableStateOf<Pair<Set<String>, (Set<String>) -> Unit>?>(null) }
    var editingSpecial by remember { mutableStateOf<BrowseTabItem?>(null) }

    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    val handleZone = with(LocalDensity.current) { 44.dp.toPx() }

    // 禁用下滑关闭与遮罩关闭，避免与长按拖动排序冲突；仅右上角 ❌ 与系统返回键关闭。
    AdaptiveSheet(
        onDismissRequest = onDismiss,
        enableSwipeDismiss = false,
        dismissOnScrim = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp)) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("标签页管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
            }
            Text(
                "长按拖动右侧手柄调整顺序；可改名、改筛选、删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Text("添加标签页", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("增加自定义筛选 tab")
                }
                Spacer(Modifier.height(8.dp))

                SpecialAddButton(
                    label = if (vm.canAddLatest()) "增加最新更新" else "已添加最新更新",
                    enabled = vm.canAddLatest(),
                    onClick = { vm.addLatestTab() },
                    info = "所有漫画 7 天内的更新；可单独查看 7 天内某一天的全部更新",
                    platform = ContentPlatform.MANWA,
                )
                Spacer(Modifier.height(8.dp))

                SpecialAddButton(
                    label = if (vm.canAddRanking()) "增加 gay排行" else "gay排行已添加",
                    enabled = vm.canAddRanking(),
                    onClick = { vm.addRankingTab() },
                    info = "整体排行，但女用户较多导致 gay 漫画偏多，建议使用宇总排行",
                    platform = ContentPlatform.MANWA,
                )
                Spacer(Modifier.height(8.dp))

                SpecialAddButton(
                    label = if (vm.canAddYuzong()) "增加宇总排行" else "宇总排行已添加",
                    enabled = vm.canAddYuzong(),
                    onClick = { vm.addYuzongTab() },
                    info = "提供哔哩哔哩 腾讯 快看的排行榜，但因为没有对应的图源，所以采用搜索自行点击",
                    platform = ContentPlatform.MANWA,
                    isYuzongRank = true,
                )
                Spacer(Modifier.height(8.dp))

                SpecialAddButton(
                    label = if (vm.canAddJmAllHot()) "增加 JM 全部热门" else "JM 全部热门已添加",
                    enabled = vm.canAddJmAllHot(),
                    onClick = { vm.addJmAllHotTab() },
                    info = "禁漫天堂全部分类，默认按最多喜欢排序；保留 JM 推荐 tab",
                    platform = ContentPlatform.JM,
                )
            }

            Text("管理已有标签页（${vm.tabs.size}）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))

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
                itemsIndexed(vm.tabs, key = { _, it -> "${it.platformId.id}_${it.id}" }) { index, item ->
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
                        BrowseTabCapsule(
                            name = item.name,
                            platform = item.platformId,
                            isYuzongRank = item.pageType == BrowsePageType.YUZONG_RANK,
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                            horizontalPadding = 10.dp,
                            verticalPadding = 6.dp,
                            fontSize = 14.sp,
                        )
                        if (item.kind == TabKind.FILTER) {
                            IconButton(onClick = { renaming = item }) { Icon(Icons.Outlined.Edit, contentDescription = "改名") }
                            IconButton(onClick = { editing = item }) { Icon(Icons.Outlined.FilterList, contentDescription = "编辑默认筛选") }
                        }
                        if (item.kind == TabKind.SPECIAL) {
                            IconButton(onClick = { editingSpecial = item }) { Icon(Icons.Outlined.Edit, contentDescription = "编辑默认值") }
                        }
                        IconButton(onClick = { deletingItem = item }) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
                        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.DragHandle, contentDescription = "拖动排序", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { confirmReset = true }) { Text("恢复默认预设") }
            }
        }
    }

    // ---- 删除确认 ----
    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text("删除 tab") },
            text = { Text("删除 tab「${item.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTab(item.id)
                    deletingItem = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { deletingItem = null }) { Text("取消") } },
        )
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
                    onValueChange = {
                        name = it
                        error = null
                    },
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
            initialPlatform = item.platformId,
            allowPlatformSelect = false,
            onDismiss = { editing = null },
            checkNameTaken = { nm, platform -> vm.isNameTaken(nm, item.id, platform) },
            onOpenTagDialog = { initSet, cb -> tagPicker = initSet to cb },
            onSave = { nm, snap, _ ->
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
            initialPlatform = vm.currentPlatform.value,
            allowPlatformSelect = true,
            onDismiss = { adding = false },
            checkNameTaken = { nm, platform -> vm.isNameTaken(nm, platform = platform) },
            onOpenTagDialog = { initSet, cb -> tagPicker = initSet to cb },
            onSave = { nm, snap, platform ->
                if (vm.addFilterTab(nm, snap, platform)) {
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
            text = {
                Text(
                    "将清空共享列表中的新增、改名和修改项，恢复漫蛙默认标签；禁漫天堂暂无默认标签。确定？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetToDefaults()
                    onDismiss()
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }

    // ---- 标签选择器 ----
    tagPicker?.let { (initSet, cb) ->
        TagPickerDialog(
            initial = initSet,
            onDismiss = { tagPicker = null },
            onConfirm = { set ->
                cb(set)
                tagPicker = null
            },
        )
    }
    editingSpecial?.let { sp ->
        if (sp.pageType == BrowsePageType.YUZONG_RANK) {
            com.zycomic.app.platform.YuzongDefaultDialog(
                initialName = sp.name,
                onRename = { nm ->
                    vm.renameTab(sp.id, nm)
                    true
                },
                onDismiss = { editingSpecial = null },
            )
        } else {
            SpecialTabEditDialog(item = sp, vm = vm, onDismiss = { editingSpecial = null })
        }
    }
}

@Composable
private fun SpecialTabEditDialog(item: BrowseTabItem, vm: BrowseViewModel, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember(item.id) { mutableStateOf(item.name) }
    var rank by remember(item.id) { mutableStateOf(SpecialTabPrefs.getRankType(ctx)) }
    val trimmed = name.trim()
    val nameError = when {
        trimmed.isEmpty() -> "名称不能为空"
        vm.isNameTaken(trimmed, item.id) -> "名称已存在"
        else -> null
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxHeight(0.8f).widthIn(max = 460.dp).fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("编辑 tab", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = { nameError?.let { e -> Text(e) } },
                        label = { Text("tab 名称") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    when (item.pageType) {
                        BrowsePageType.RANKING -> {
                            Text("默认榜型", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                            FlowRow(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf(0 to "人气榜", 1 to "新番榜", 2 to "完结榜").forEach { (v, label) ->
                                    com.zycomic.app.ui.components.FilterChip(text = label, selected = rank == v) { rank = v }
                                }
                            }
                        }
                        BrowsePageType.LATEST -> Text(
                            "最新更新默认展示近 7 天总览，默认值无需修改（仅可改名）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        else -> {}
                    }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = nameError == null, onClick = {
                            if (item.pageType == BrowsePageType.RANKING) {
                                SpecialTabPrefs.setRankType(ctx, rank)
                                vm.applyDefaultRankType(rank)
                            }
                            if (trimmed != item.name) vm.renameTab(item.id, trimmed)
                            onDismiss()
                        }) { Text("保存") }
                    }
                }
            }
        }
    }
}

/** 生成筛选摘要文案。 */
@Composable
private fun FilterSnapshot.summary(): String {
    val g = if (gender == 1) {
        stringResource(KMR.strings.content_platform_filter_jm)
    } else {
        BrowseViewModel.GENDERS.firstOrNull { it.first == gender }?.second ?: "全部"
    }
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
    initialPlatform: ContentPlatform,
    allowPlatformSelect: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, FilterSnapshot, ContentPlatform) -> Unit,
    onOpenTagDialog: (Set<String>, (Set<String>) -> Unit) -> Unit,
    checkNameTaken: (String, ContentPlatform) -> Boolean,
) {
    var name by remember(title) { mutableStateOf(initialName) }
    var draft by remember(title) { mutableStateOf(initial) }
    var targetPlatform by remember(title) { mutableStateOf(initialPlatform) }

    val trimmed = name.trim()
    val nameError = when {
        trimmed.isEmpty() -> "名称不能为空"
        checkNameTaken(trimmed, targetPlatform) -> "名称已存在"
        else -> null
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxHeight(0.92f).widthIn(max = 460.dp).fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize(),
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

                    if (allowPlatformSelect) {
                        Text(stringResource(KMR.strings.content_platform_custom_tab_target), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        ContentPlatformSelector(
                            selected = targetPlatform,
                            onSelect = { targetPlatform = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        if (targetPlatform == ContentPlatform.JM) {
                            Text(
                                stringResource(KMR.strings.content_platform_custom_tab_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }

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
                        Button(enabled = nameError == null, onClick = { onSave(trimmed, draft, targetPlatform) }) { Text("保存") }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SpecialAddButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    info: String,
    platform: ContentPlatform,
    isYuzongRank: Boolean = false,
) {
    var showInfo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            BrowseTabCapsule(
                name = label,
                platform = platform,
                isYuzongRank = isYuzongRank,
                horizontalPadding = 8.dp,
                verticalPadding = 4.dp,
                fontSize = 12.sp,
            )
        }
        androidx.compose.material3.IconButton(onClick = { showInfo = true }) {
            Icon(Icons.Outlined.Info, contentDescription = "说明", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("说明") },
            text = { Text(info) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } },
        )
    }
}
