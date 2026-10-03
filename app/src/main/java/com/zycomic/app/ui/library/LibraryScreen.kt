package com.zycomic.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.zycomic.app.data.dto.FavoriteItem
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.LoadingFooter
import com.zycomic.app.ui.components.coverUrl
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground

@Composable
fun LibraryScreen(
    onOpenManga: (String) -> Unit,
    onRequireLogin: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val vm = remember { LibraryViewModel(mode = 0) }

    val user by vm.user.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val favs by vm.favItems.collectAsState()
    val filtered by vm.filteredFavs.collectAsState()
    val loading by vm.favLoading.collectAsState()
    val appending by vm.favAppending.collectAsState()
    val hasMore by vm.favHasMore.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val displayMode by vm.displayMode.collectAsState()
    val showUnreadBadge by vm.showUnreadBadge.collectAsState()
    val showUpdateBadge by vm.showUpdateBadge.collectAsState()

    LaunchedEffect(vm.needLogin) {
        vm.needLogin.collect { if (it) onRequireLogin() }
    }

    // 多选模式下按返回键 = 退出多选
    BackHandler(enabled = selectionMode) { vm.exitSelection() }

    var showFilterDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Folder?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(listState.canScrollForward, filtered.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (filtered.isNotEmpty() && last >= filtered.size - 3 && hasMore) vm.loadMoreFavorites()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { scrollBehavior ->
            if (selectionMode) {
                AppBar(
                    titleContent = { Text("${selectedIds.size}") },
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = "全选",
                                    icon = Icons.Outlined.SelectAll,
                                    onClick = { vm.selectAll() },
                                ),
                                AppBar.Action(
                                    title = "反选",
                                    icon = Icons.Outlined.FlipToBack,
                                    onClick = { vm.invertSelection() },
                                ),
                            ),
                        )
                    },
                    isActionMode = true,
                    onCancelActionMode = { vm.exitSelection() },
                    scrollBehavior = scrollBehavior,
                )
            } else {
                SearchToolbar(
                    titleContent = { AppBarTitle("收藏") },
                    searchQuery = searchQuery,
                    onChangeSearchQuery = { vm.updateSearchQuery(it) },
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = "筛选",
                                    icon = Icons.Outlined.FilterList,
                                    onClick = { showFilterDialog = true },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickableNoRipple { if (selectedIds.isNotEmpty()) showRemoveConfirm = true }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("取消收藏", color = MaterialTheme.colorScheme.error)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickableNoRipple { showMoveDialog = true }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("移动收藏夹", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
    ) { contentPadding ->
        when {
            user == null -> EmptyScreen(
                message = "请先登录",
                modifier = Modifier.padding(contentPadding),
            )
            loading && favs.isEmpty() -> LoadingScreen(modifier = Modifier.padding(contentPadding))
            filtered.isEmpty() -> EmptyScreen(
                message = if (!searchQuery.isNullOrBlank()) "没有搜索结果" else "暂无收藏",
                modifier = Modifier.padding(contentPadding),
            )
            else -> FastScrollLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 本地搜索词非空时，提供“全局搜索”入口
                if (!searchQuery.isNullOrBlank()) {
                    item(key = "global-search", contentType = "global-search") {
                        TextButton(
                            onClick = onOpenSearch,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "全局搜索：${searchQuery}",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                items(filtered, key = { it.bookId }, contentType = { "item" }) { item ->
                    FavItemRow(
                        item = item,
                        displayMode = displayMode,
                        selected = selectedIds.contains(item.bookId),
                        selectionMode = selectionMode,
                        showUnreadBadge = showUnreadBadge,
                        showUpdateBadge = showUpdateBadge,
                        onClick = {
                            if (selectionMode) vm.toggleSelect(item.bookId)
                            else onOpenManga(item.bookId)
                        },
                        onLongClick = { if (!selectionMode) vm.enterSelectionAndSelect(item.bookId) },
                    )
                }
                if (appending) {
                    item(key = "loading-footer", contentType = "footer") { LoadingFooter() }
                } else if (!hasMore) {
                    item(key = "no-more", contentType = "footer") {
                        Text(
                            text = "没有更多了",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    // 筛选 / 排序 / 显示 / 管理收藏夹 对话框
    if (showFilterDialog) {
        FilterDialog(
            vm = vm,
            onDismiss = { showFilterDialog = false },
            onNewFolder = { showNewFolderDialog = true },
            onRenameFolder = { renameTarget = it },
        )
    }

    // 移动收藏夹弹窗
    if (showMoveDialog) {
        val folders by vm.folders.collectAsState()
        Dialog(onDismissRequest = { showMoveDialog = false }) {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 8.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    "移动到收藏夹",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    "全部收藏夹",
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple {
                            vm.moveSelectedTo(null)
                            showMoveDialog = false
                        }
                        .padding(16.dp),
                )
                folders.forEach { f ->
                    Text(
                        f.name,
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple {
                                vm.moveSelectedTo(f.id)
                                showMoveDialog = false
                            }
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    // 新建收藏夹弹窗
    if (showNewFolderDialog) {
        FolderNameDialog(
            title = "新建收藏夹",
            initial = "",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                vm.createFolder(name)
                showNewFolderDialog = false
            },
        )
    }

    // 重命名弹窗
    renameTarget?.let { f ->
        FolderNameDialog(
            title = "重命名收藏夹",
            initial = f.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.renameFolder(f.id.toString(), name)
                renameTarget = null
            },
        )
    }

    // 取消收藏确认对话框
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("取消收藏确认") },
            text = { Text("确定取消选中的 ${selectedIds.size} 本漫画的收藏？") },
            confirmButton = {
                Text(
                    "确定取消",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(
                            onClick = {
                                showRemoveConfirm = false
                                vm.batchRemove()
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            },
            dismissButton = {
                Text(
                    "取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(onClick = { showRemoveConfirm = false })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            },
        )
    }
}

// ==================== 收藏项 ====================

/** 未读完：最新章节非空 且 读到 != 最新。 */
private fun FavoriteItem.isUnread(): Boolean =
    chapterName.isNotBlank() && readLast != chapterName

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavItemRow(
    item: FavoriteItem,
    displayMode: Int,
    selected: Boolean,
    selectionMode: Boolean,
    showUnreadBadge: Boolean,
    showUpdateBadge: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    if (displayMode == 1) {
        // 列表2（历史页样式）
        Row(
            modifier = Modifier
                .selectedBackground(selected)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .fillMaxWidth()
                .height(96.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverWithBadges(
                item = item,
                coverWidth = 48.dp,
                coverHeight = 64.dp,
                showUnreadBadge = showUnreadBadge,
                showUpdateBadge = showUpdateBadge,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 4.dp),
            ) {
                Text(
                    text = item.bookName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "上次阅读：${item.chapterName.ifBlank { "—" }}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = buildString {
                        append("上次更新：").append(item.lastTime.ifBlank { "—" })
                        if (item.end.isNotBlank()) append(" · ").append(item.end)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    } else {
        // 默认列表（komikku LibraryList 风格）
        Row(
            modifier = Modifier
                .selectedBackground(selected)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverWithBadges(
                item = item,
                coverWidth = 56.dp,
                coverHeight = 72.dp,
                showUnreadBadge = showUnreadBadge,
                showUpdateBadge = showUpdateBadge,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = item.bookName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "读到：${item.readLast.ifBlank { "未读" }}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "最新：${item.chapterName.ifBlank { "—" }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 1.dp),
                )
                Text(
                    text = buildString {
                        append("更新：").append(item.lastTime.ifBlank { "—" })
                        if (item.end.isNotBlank()) append(" · ").append(item.end)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** 封面 + 左上角未读完标记 + 右下角 NEW 标记。 */
@Composable
private fun CoverWithBadges(
    item: FavoriteItem,
    coverWidth: Dp,
    coverHeight: Dp,
    showUnreadBadge: Boolean,
    showUpdateBadge: Boolean,
) {
    Box {
        AsyncImage(
            model = coverUrl(item.bookImg),
            contentDescription = item.bookName,
            modifier = Modifier
                .size(width = coverWidth, height = coverHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        if (showUnreadBadge && item.isUnread()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    "未读完",
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (showUpdateBadge && item.isNew) {
            Text(
                "NEW",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
    }
}

// ==================== 筛选对话框 ====================

@Composable
private fun FilterDialog(
    vm: LibraryViewModel,
    onDismiss: () -> Unit,
    onNewFolder: () -> Unit,
    onRenameFolder: (Folder) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    val folders by vm.folders.collectAsState()
    val selectedFolder by vm.selectedFolderId.collectAsState()
    val isEnd by vm.isEnd.collectAsState()
    val isFull by vm.isFullVersion.collectAsState()
    val onlyUpdated by vm.showOnlyUpdated.collectAsState()
    val order by vm.order.collectAsState()
    val orderType by vm.orderType.collectAsState()
    val isUnread by vm.isUnreadFilter.collectAsState()
    val isRead by vm.isReadFilter.collectAsState()
    val displayMode by vm.displayMode.collectAsState()
    val showUnreadBadge by vm.showUnreadBadge.collectAsState()
    val showUpdateBadge by vm.showUpdateBadge.collectAsState()

    // 各分组展开状态
    var expUnread by remember { mutableStateOf(true) }
    var expRead by remember { mutableStateOf(false) }
    var expEnd by remember { mutableStateOf(false) }
    var expFolder by remember { mutableStateOf(true) }
    var expFull by remember { mutableStateOf(false) }
    var expUpdated by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxWidth(0.95f),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("筛选") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("排序") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("显示") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("管理") })
            }

            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    0 -> {
                        FilterGroup(
                            title = "未读完",
                            expanded = expUnread,
                            onToggle = { expUnread = !expUnread },
                            options = listOf(-1 to "全部", 1 to "未读完", 0 to "已读完"),
                            selected = isUnread,
                            onSelect = { vm.selectIsUnread(it) },
                        )
                        FilterGroup(
                            title = "阅读过",
                            expanded = expRead,
                            onToggle = { expRead = !expRead },
                            options = listOf(-1 to "全部", 1 to "阅读过", 0 to "未阅读过"),
                            selected = isRead,
                            onSelect = { vm.selectIsRead(it) },
                        )
                        FilterGroup(
                            title = "完结",
                            expanded = expEnd,
                            onToggle = { expEnd = !expEnd },
                            options = listOf(-1 to "全部", 0 to "连载中", 1 to "已完结"),
                            selected = isEnd,
                            onSelect = { vm.selectIsEnd(it) },
                        )
                        FilterGroup(
                            title = "收藏夹",
                            expanded = expFolder,
                            onToggle = { expFolder = !expFolder },
                            options = buildList {
                                add(0 to "全部收藏夹")
                                folders.forEach { add(it.id to it.name) }
                            },
                            selected = selectedFolder,
                            onSelect = { vm.selectFolder(it) },
                        )
                        FilterGroup(
                            title = "画质",
                            expanded = expFull,
                            onToggle = { expFull = !expFull },
                            options = listOf(-1 to "全部", 1 to "高清", 2 to "清水版", 3 to "未删减", 4 to "完整版"),
                            selected = isFull,
                            onSelect = { vm.selectIsFull(it) },
                        )
                        FilterGroup(
                            title = "只显示更新",
                            expanded = expUpdated,
                            onToggle = { expUpdated = !expUpdated },
                            options = listOf(-1 to "关", 1 to "开"),
                            selected = onlyUpdated,
                            onSelect = { vm.selectShowOnlyUpdated(it) },
                        )
                    }
                    1 -> {
                        SortChipRow(
                            order = order,
                            orderType = orderType,
                            onSelect = { o, t -> vm.selectSort(o, t) },
                        )
                    }
                    2 -> {
                        SwitchRow(
                            title = "列表2",
                            checked = displayMode == 1,
                            onChange = { vm.selectDisplayMode(if (it) 1 else 0) },
                        )
                        SwitchRow(
                            title = "未读完标记",
                            checked = showUnreadBadge,
                            onChange = { vm.toggleShowUnreadBadge() },
                        )
                        SwitchRow(
                            title = "更新标记",
                            checked = showUpdateBadge,
                            onChange = { vm.toggleShowUpdateBadge() },
                        )
                    }
                    3 -> {
                        Text(
                            "＋ 新建收藏夹",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableNoRipple(onNewFolder)
                                .padding(16.dp),
                        )
                        if (folders.isEmpty()) {
                            Text(
                                "暂无收藏夹",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        folders.forEach { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${f.count} 本",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "重命名",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .clickableNoRipple { onRenameFolder(f) }
                                        .padding(8.dp),
                                )
                                Text(
                                    "删除",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .clickableNoRipple { vm.deleteFolder(f.id.toString()) }
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一个可展开/收起的筛选分组。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableNoRipple(onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (expanded) "收起" else "展开",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (expanded) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { (v, l) ->
                    FilterChip(l, selected = selected == v, onClick = { onSelect(v) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortChipRow(
    order: Int,
    orderType: Int,
    onSelect: (Int, Int) -> Unit,
) {
    val options = listOf(
        Quad(2, 0, "收藏降序"),
        Quad(2, 1, "收藏升序"),
        Quad(1, 0, "更新降序"),
        Quad(1, 1, "更新升序"),
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { q ->
            FilterChip(
                q.label,
                selected = order == q.order && orderType == q.orderType,
                onClick = { onSelect(q.order, q.orderType) },
            )
        }
    }
}

private data class Quad(val order: Int, val orderType: Int, val label: String)

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ==================== 通用 ====================

/** 无 ripple 点击（列表行内文本按钮用）。 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val source = androidx.compose.foundation.interaction.MutableInteractionSource()
    return this.clickable(
        interactionSource = source,
        indication = null,
        onClick = onClick,
    )
}

/** 收藏夹命名输入弹窗（新建 / 重命名共用）。 */
@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onConfirm(text.trim()) }) { Text("确定") }
            }
        }
    }
}
