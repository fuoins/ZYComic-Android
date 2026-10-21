package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zycomic.app.data.AllTags
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformSelector
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.platform.rememberContentPlatformAccent
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.FilterSection
import com.zycomic.app.ui.picacg.PicacgBrowseFilter
import com.zycomic.app.ui.picacg.PicacgThemes
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

/** Tab 管理面板：预设快速添加，已有 tab 编辑/删除，排序通过独立草稿对话框提交。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabManageSheet(vm: BrowseViewModel, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<BrowseTabItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var deletingItem by remember { mutableStateOf<BrowseTabItem?>(null) }
    var tagPicker by remember { mutableStateOf<Pair<Set<String>, (Set<String>) -> Unit>?>(null) }
    var editingSpecial by remember { mutableStateOf<BrowseTabItem?>(null) }
    var showReorder by remember { mutableStateOf(false) }

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
                "可编辑tab名称和默认选项；通过标签胶囊管理或调整顺序。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Text("添加标签页", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(10.dp)) {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(KMR.strings.browse_tab_add_custom))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    SpecialAddButton(
                        label = "首页",
                        enabled = vm.canAddJmHome(),
                        onClick = { vm.addJmHomeTab() },
                        info = "禁漫天堂首页推荐内容。",
                        platform = ContentPlatform.JM,
                    )
                    SpecialAddButton(
                        label = "全部热门",
                        enabled = vm.canAddJmAllHot(),
                        onClick = { vm.addJmAllHotTab() },
                        info = "禁漫天堂全部分类内容，默认按最多喜欢排序。",
                        platform = ContentPlatform.JM,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    listOf(
                        BrowseTabStore.ID_GENERAL to "mw一般向热门",
                        BrowseTabStore.ID_JINMAN to "mw禁漫向热门",
                        BrowseTabStore.ID_REXUE to "mw热血热门",
                    ).forEach { (id, name) ->
                        SpecialAddButton(
                            label = name,
                            enabled = vm.canAddManwaPreset(id),
                            onClick = { vm.addManwaPreset(id) },
                            info = "$name，漫蛙热门筛选预设。",
                            platform = ContentPlatform.MANWA,
                            isAdded = vm.tabs.any { it.platformId == ContentPlatform.MANWA && it.id == id },
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    SpecialAddButton(
                        label = "哔咔排行",
                        enabled = vm.canAddPicacgRanking(),
                        onClick = { vm.addPicacgRankingTab() },
                        info = "Picacg 日榜、周榜、月榜、随机和最新漫画。",
                        platform = ContentPlatform.PICACG,
                    )
                    SpecialAddButton(
                        label = "哔咔分类",
                        enabled = vm.canAddPicacgCategory(),
                        onClick = { vm.addPicacgCategoryTab() },
                        info = "使用 Picacg 配置中的40个静态主题筛选漫画。",
                        platform = ContentPlatform.PICACG,
                    )
                    SpecialAddButton(
                        label = "增加最新更新",
                        enabled = vm.canAddLatest(),
                        onClick = { vm.addLatestTab() },
                        info = "所有漫画 7 天内的更新；可单独查看 7 天内某一天的全部更新",
                        platform = ContentPlatform.MANWA,
                    )
                    SpecialAddButton(
                        label = "增加 gay排行",
                        enabled = vm.canAddRanking(),
                        onClick = { vm.addRankingTab() },
                        info = "整体排行，但女用户较多导致 gay 漫画偏多，建议使用宇总排行",
                        platform = ContentPlatform.MANWA,
                    )
                    SpecialAddButton(
                        label = "增加宇总排行",
                        enabled = vm.canAddYuzong(),
                        onClick = { vm.addYuzongTab() },
                        info = "提供哔哩哔哩 腾讯 快看的排行榜，但因为没有对应的图源，所以采用搜索自行点击",
                        platform = ContentPlatform.MANWA,
                        isYuzongRank = true,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            ) {
                Text("管理已有标签页（${vm.tabs.size}）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { showReorder = true }) { Text("更改顺序") }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    vm.tabs.forEach { item ->
                        ManagedTabPill(
                            item = item,
                            onEdit = { if (item.kind == TabKind.FILTER) editing = item else editingSpecial = item },
                            onDelete = { deletingItem = item },
                        )
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

    // ---- 编辑 tab（名称 + 默认筛选） ----
    editing?.let { item ->
        FilterTabFormDialog(
            title = stringResource(KMR.strings.browse_tab_edit_title),
            initialName = item.name,
            initial = item.filter ?: FilterSnapshot(),
            initialPicacgFilter = item.picacgFilter ?: PicacgBrowseFilter(),
            initialJmFilters = vm.defaultJmFiltersForTab(vm.tabs.indexOfFirst { it.id == item.id }).let {
                if (item.id == JmBrowsePolicy.ALL_HOT_TAB_ID) it.copy(mode = JmBrowsePolicy.MODE_CATEGORY, query = "") else it
            },
            jmHome = item.id == BrowseTabStore.ID_JM_HOME,
            jmCategoryOnly = JmBrowsePolicy.isCustomCategoryTab(item.id),
            initialPlatform = item.platformId,
            allowPlatformSelect = false,
            onDismiss = { editing = null },
            checkNameTaken = { nm, platform -> vm.isNameTaken(nm, item.id, platform) },
            onOpenTagDialog = { initSet, cb -> tagPicker = initSet to cb },
            onSave = { nm, snap, jmFilters, platform, picacgFilter ->
                if (platform == ContentPlatform.PICACG) {
                    vm.updateTabDefaultPicacgFilter(item.id, picacgFilter)
                } else if (platform == ContentPlatform.JM) {
                    vm.updateTabDefaultJmFilters(item.id, jmFilters)
                } else if (snap != null) {
                    vm.updateTabDefaultFilter(item.id, snap)
                }
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
            initialPicacgFilter = PicacgBrowseFilter(),
            initialJmFilters = JmBrowsePolicy.customCategoryFilters(),
            jmHome = false,
            jmCategoryOnly = true,
            initialPlatform = vm.currentPlatform.value,
            allowPlatformSelect = true,
            onDismiss = { adding = false },
            checkNameTaken = { nm, platform -> vm.isNameTaken(nm, platform = platform) },
            onOpenTagDialog = { initSet, cb -> tagPicker = initSet to cb },
            onSave = { nm, snap, jmFilters, platform, picacgFilter ->
                if (vm.addFilterTab(nm, snap, platform, jmFilters, picacgFilter)) {
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
                    "将清空共享列表中的新增、改名和修改项，恢复漫蛙默认标签及禁漫天堂的首页、全部热门标签。确定？",
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
    if (showReorder) {
        TabReorderDialog(
            tabs = vm.tabs.toList(),
            onDismiss = { showReorder = false },
            onSave = { draft ->
                if (vm.reorderTabs(draft)) showReorder = false
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
                        Text(stringResource(KMR.strings.browse_tab_edit_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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

@Composable
private fun JmTabFilterEditor(
    filters: JmBrowsePolicy.Filters,
    home: Boolean,
    categoryOnly: Boolean,
    onChange: (JmBrowsePolicy.Filters) -> Unit,
) {
    if (home) {
        Text(
            stringResource(KMR.strings.jm_home_online_defaults),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
        return
    }
    val categories = remember { JmBrowsePolicy.categorySelections(com.zycomic.app.reader.JmSource.categoryOptions) }
    FilterSection("JM 分类") {
        categories.forEach { (label, value) ->
            FilterChip(text = label, selected = filters.category == value) {
                onChange(filters.copy(mode = JmBrowsePolicy.MODE_CATEGORY, query = "", category = value))
            }
        }
    }
    if (!categoryOnly) {
        val orders = remember { JmBrowsePolicy.orderOptions(com.zycomic.app.reader.JmSource.orderOptions) }
        FilterSection("JM 排序") {
            orders.forEach { (label, value) ->
                FilterChip(text = label, selected = filters.order == value) {
                    onChange(filters.copy(order = value))
                }
            }
        }
    }
}

/**
 * 新增/编辑共用的筛选 tab 表单：名称（即时校验）+ 实时摘要 + 筛选配置（标签"更多"进标签选择器）+ 取消/保存。
 */
@Composable
private fun FilterTabFormDialog(
    title: String,
    initialName: String,
    initial: FilterSnapshot,
    initialPicacgFilter: PicacgBrowseFilter,
    initialJmFilters: JmBrowsePolicy.Filters,
    jmHome: Boolean,
    jmCategoryOnly: Boolean,
    initialPlatform: ContentPlatform,
    allowPlatformSelect: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, FilterSnapshot?, JmBrowsePolicy.Filters, ContentPlatform, PicacgBrowseFilter) -> Unit,
    onOpenTagDialog: (Set<String>, (Set<String>) -> Unit) -> Unit,
    checkNameTaken: (String, ContentPlatform) -> Boolean,
) {
    val context = LocalContext.current
    var name by remember(title) { mutableStateOf(initialName) }
    var draft by remember(title) { mutableStateOf(initial) }
    var jmDraft by remember(title) { mutableStateOf(initialJmFilters) }
    var picacgDraft by remember(title) { mutableStateOf(initialPicacgFilter.normalized()) }
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
                            onSelect = {
                                if (StartupPlatformPolicy.checkSelection(context, it)) {
                                    targetPlatform = it
                                }
                            },
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
                            if (targetPlatform == ContentPlatform.PICACG) {
                                "${picacgDraft.category.ifBlank { "全部主题" }} · ${picacgSortLabel(picacgDraft.sort)}"
                            } else if (targetPlatform == ContentPlatform.JM) {
                                when {
                                    jmHome -> BrowseTabStore.JM_HOME_NAME
                                    jmDraft.mode == JmBrowsePolicy.MODE_RECOMMENDATIONS -> "首页推荐"
                                    jmDraft.mode == JmBrowsePolicy.MODE_WEEK -> "每周必看 · ${jmDraft.weekType.ifBlank { "manga" }} · ${jmDraft.weekId.ifBlank { "最新期" }}"
                                    jmDraft.mode == JmBrowsePolicy.MODE_SERIALIZATION -> "每周连载更新 · ${jmDraft.serializationType} · ${if (jmDraft.serializationDate == 0) "已完结" else "周${jmDraft.serializationDate}"}"
                                    else -> {
                                        val category = JmBrowsePolicy.categorySelections(com.zycomic.app.reader.JmSource.categoryOptions)
                                            .firstOrNull { it.second == jmDraft.category }?.first ?: "全部分类"
                                        val order = JmBrowsePolicy.orderOptions(com.zycomic.app.reader.JmSource.orderOptions)
                                            .firstOrNull { it.second == jmDraft.order }?.first ?: "最新"
                                        "$category · $order"
                                    }
                                }
                            } else {
                                draft.summary()
                            },
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
                        if (targetPlatform == ContentPlatform.PICACG) {
                            PicacgTabFilterEditor(picacgDraft) { picacgDraft = it }
                        } else if (targetPlatform == ContentPlatform.JM) {
                            JmTabFilterEditor(
                                filters = jmDraft,
                                home = jmHome,
                                categoryOnly = jmCategoryOnly,
                                onChange = { jmDraft = it },
                            )
                        } else {
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
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = nameError == null,
                            onClick = {
                                onSave(
                                    trimmed,
                                    draft.takeIf { targetPlatform == ContentPlatform.MANWA },
                                    when {
                                        jmHome -> JmBrowsePolicy.initialFilters(BrowseTabStore.ID_JM_HOME)
                                        jmCategoryOnly -> JmBrowsePolicy.customCategoryFilters(jmDraft)
                                        else -> JmBrowsePolicy.effectiveFilters(JmBrowsePolicy.ALL_HOT_TAB_ID, jmDraft)
                                    },
                                    targetPlatform,
                                    picacgDraft.normalized(),
                                )
                            },
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}

private fun picacgSortLabel(code: String): String = when (code) {
    "dd" -> "新到旧"
    "da" -> "旧到新"
    "ld" -> "最多喜欢"
    "vd" -> "最多浏览"
    else -> "新到旧"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PicacgTabFilterEditor(filter: PicacgBrowseFilter, onChange: (PicacgBrowseFilter) -> Unit) {
    FilterSection("主题") {
        FilterChip(text = "全部", selected = filter.category.isBlank(), onClick = { onChange(filter.copy(category = "")) })
        PicacgThemes.titles.forEach { theme ->
            FilterChip(text = theme, selected = filter.category == theme, onClick = { onChange(filter.copy(category = theme)) })
        }
    }
    FilterSection("排序") {
        listOf("dd" to "新到旧", "da" to "旧到新", "ld" to "最多喜欢", "vd" to "最多浏览").forEach { (code, label) ->
            FilterChip(text = label, selected = filter.sort == code, onClick = { onChange(filter.copy(sort = code)) })
        }
    }
}

@Composable
private fun TabReorderDialog(
    tabs: List<BrowseTabItem>,
    onDismiss: () -> Unit,
    onSave: (List<BrowseTabItem>) -> Unit,
) {
    var draft by remember(tabs) { mutableStateOf(tabs) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxHeight(0.82f).widthIn(max = 460.dp).fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Text("更改标签页顺序", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp))
                Text("使用上下箭头调整；取消或返回不会保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(draft, key = { _, item -> "${item.platformId.id}_${item.id}" }) { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            BrowseTabCapsule(
                                name = item.name,
                                platform = item.platformId,
                                isYuzongRank = item.pageType == BrowsePageType.YUZONG_RANK,
                                modifier = Modifier.weight(1f),
                                horizontalPadding = 8.dp,
                                verticalPadding = 5.dp,
                                fontSize = 13.sp,
                            )
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    val updated = draft.toMutableList()
                                    val previous = updated[index - 1]
                                    updated[index - 1] = updated[index]
                                    updated[index] = previous
                                    draft = updated
                                },
                            ) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上移 ${item.name}") }
                            IconButton(
                                enabled = index < draft.lastIndex,
                                onClick = {
                                    val updated = draft.toMutableList()
                                    val next = updated[index + 1]
                                    updated[index + 1] = updated[index]
                                    updated[index] = next
                                    draft = updated
                                },
                            ) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下移 ${item.name}") }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(draft) }) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun SpecialAddButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    info: String,
    platform: ContentPlatform,
    isYuzongRank: Boolean = false,
    isAdded: Boolean = !enabled,
) {
    var showInfo by remember { mutableStateOf(false) }
    val presentation = BrowseTabPresetPresentationPolicy.present(label, isAdded = isAdded)
    val accent = if (isYuzongRank) MaterialTheme.colorScheme.primary else rememberContentPlatformAccent(platform)
    Surface(
        shape = CircleShape,
        color = accent.copy(alpha = 0.13f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Icon(
                    if (presentation.isAdded) Icons.Outlined.Check else Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accent,
                )
                Spacer(Modifier.width(4.dp))
                Text(presentation.label, fontSize = 12.sp, color = accent, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = "${presentation.label}说明", tint = accent, modifier = Modifier.size(16.dp))
            }
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

@Composable
private fun ManagedTabPill(item: BrowseTabItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    val accent = if (item.pageType == BrowsePageType.YUZONG_RANK) {
        MaterialTheme.colorScheme.primary
    } else {
        rememberContentPlatformAccent(item.platformId)
    }
    Surface(shape = CircleShape, color = accent.copy(alpha = 0.13f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 11.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(
                item.name,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                maxLines = 1,
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑 ${item.name}", tint = accent, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除 ${item.name}", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            }
        }
    }
}
