package com.zycomic.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zycomic.app.data.dto.FavoriteItem
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.data.dto.HistoryItem
import com.zycomic.app.ui.components.EmptyView
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.LoadingFooter
import com.zycomic.app.ui.components.coverUrl
import com.zycomic.app.ui.theme.BluePrimary
import com.zycomic.app.ui.theme.ErrorRed
import com.zycomic.app.ui.theme.OffWhite
import com.zycomic.app.ui.theme.TextSecondary

@Composable
fun LibraryScreen(
    onOpenManga: (String) -> Unit,
    onRequireLogin: () -> Unit,
) {
    val vm = remember { LibraryViewModel(mode = 0) }
    val user by vm.user.collectAsState()

    LaunchedEffect(vm.needLogin) {
        vm.needLogin.collect { if (it) onRequireLogin() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("收藏", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        if (user == null) {
            EmptyView("请先登录", modifier = Modifier.fillMaxSize())
            return@Column
        }

        FavContent(vm, onOpenManga)
    }
}

/**
 * 独立的阅读历史页面（底部导航"历史"tab）。
 */
@Composable
fun HistoryScreen(
    onOpenManga: (String) -> Unit,
    onRequireLogin: () -> Unit,
) {
    val vm = remember { LibraryViewModel(mode = 1) }
    val user by vm.user.collectAsState()

    LaunchedEffect(vm.needLogin) {
        vm.needLogin.collect { if (it) onRequireLogin() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("阅读历史", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        if (user == null) {
            EmptyView("请先登录", modifier = Modifier.fillMaxSize())
            return@Column
        }

        HistoryContent(vm, onOpenManga)
    }
}

// ==================== 收藏 ====================

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FavContent(vm: LibraryViewModel, onOpenManga: (String) -> Unit) {
    val folders by vm.folders.collectAsState()
    val selectedFolder by vm.selectedFolderId.collectAsState()
    val isEnd by vm.isEnd.collectAsState()
    val isFull by vm.isFullVersion.collectAsState()
    val onlyUpdated by vm.showOnlyUpdated.collectAsState()
    val order by vm.order.collectAsState()
    val orderType by vm.orderType.collectAsState()
    val favs by vm.favItems.collectAsState()
    val loading by vm.favLoading.collectAsState()
    val appending by vm.favAppending.collectAsState()
    val hasMore by vm.favHasMore.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val listState = rememberLazyListState()

    var filterExpanded by remember { mutableStateOf(true) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var actionFolder by remember { mutableStateOf<Folder?>(null) }
    var renameTarget by remember { mutableStateOf<Folder?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(listState.canScrollForward, favs.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (favs.isNotEmpty() && last >= favs.size - 3 && hasMore) vm.loadMoreFavorites()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!selectionMode) {
                Text("收藏", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text("管理", color = BluePrimary, modifier = Modifier
                    .clickableNoRipple { vm.enterSelection() }
                    .padding(8.dp))
            } else {
                Text("已选(${selectedIds.size})", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                val allSelected = favs.isNotEmpty() && selectedIds.size == favs.size
                Text(
                    if (allSelected) "取消" else "全选",
                    color = BluePrimary,
                    modifier = Modifier.clickableNoRipple {
                        if (allSelected) vm.exitSelection() else vm.toggleSelectAllLoadedFavorites()
                    }.padding(8.dp),
                )
            }
        }

        // 5 行筛选（多选模式下隐藏）
        if (!selectionMode) {
            // 第1行：收藏夹（始终显示）
            FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("全部收藏夹", selected = selectedFolder == 0, onClick = { vm.selectFolder(0) })
                folders.forEach { f ->
                    FilterChip(
                        text = f.name,
                        selected = selectedFolder == f.id,
                        onClick = { vm.selectFolder(f.id) },
                        onLongClick = { actionFolder = f },
                    )
                }
                FilterChip("＋新建", selected = false, onClick = { showNewFolderDialog = true })
            }

            if (filterExpanded) {
                // 第2行：状态
                FilterRow(label = "状态", options = listOf(-1 to "全部", 0 to "连载中", 1 to "完结"), selected = isEnd, onSelect = { vm.selectIsEnd(it) })
                // 第3行：版本
                FilterRow(label = "版本", options = listOf(-1 to "全部", 1 to "高清", 2 to "清水版", 3 to "未删减", 4 to "完整版"), selected = isFull, onSelect = { vm.selectIsFull(it) })
                // 第4行：更新
                FilterRow(label = "更新", options = listOf(-1 to "全部", 1 to "只显示更新"), selected = onlyUpdated, onSelect = { vm.selectShowOnlyUpdated(it) })
                // 第5行：排序
                SortRow(order = order, orderType = orderType, onSelect = { o, t -> vm.selectSort(o, t) })

                Text("收起筛选", color = TextSecondary, modifier = Modifier
                    .clickableNoRipple { filterExpanded = false }
                    .padding(horizontal = 16.dp, vertical = 4.dp))
            } else {
                Text("展开筛选", color = BluePrimary, modifier = Modifier
                    .clickableNoRipple { filterExpanded = true }
                    .padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        // 列表
        Box(modifier = Modifier.weight(1f)) {
            when {
                loading && favs.isEmpty() -> LoadingFooter()
                favs.isEmpty() -> EmptyView("暂无收藏")
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(favs, key = { it.bookId }) { item ->
                        FavRow(
                            item = item,
                            selected = selectedIds.contains(item.bookId),
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) vm.toggleSelect(item.bookId)
                                else onOpenManga(item.bookId)
                            },
                            onLongClick = { if (!selectionMode) vm.enterSelection() },
                        )
                    }
                    if (appending) item { LoadingFooter() }
                    else if (!hasMore) item {
                        Text("没有更多了", modifier = Modifier.fillMaxWidth().padding(16.dp), color = TextSecondary)
                    }
                }
            }
        }
    }

    // 多选底部栏
    if (selectionMode) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F2F5)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f).clickableNoRipple {
                    if (selectedIds.isNotEmpty()) showRemoveConfirm = true
                }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("取消收藏", color = ErrorRed) }
            Box(
                modifier = Modifier.weight(1f).clickableNoRipple { showMoveDialog = true }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("移动收藏夹", color = BluePrimary) }
        }
    }

    // 移动收藏夹弹窗
    if (showMoveDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showMoveDialog = false }) {
            Column(Modifier.background(Color.White).padding(16.dp).fillMaxWidth()) {
                Text("移动到收藏夹", style = MaterialTheme.typography.titleMedium)
                Text("全部收藏夹", Modifier.fillMaxWidth().clickableNoRipple {
                    vm.moveSelectedTo(null); showMoveDialog = false
                }.padding(12.dp))
                folders.forEach { f ->
                    Text(f.name, Modifier.fillMaxWidth().clickableNoRipple {
                        vm.moveSelectedTo(f.id); showMoveDialog = false
                    }.padding(12.dp))
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
            onConfirm = { name -> vm.createFolder(name); showNewFolderDialog = false },
        )
    }

    // 收藏夹长按：重命名 / 删除
    actionFolder?.let { f ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { actionFolder = null }) {
            Column(Modifier.background(Color.White).padding(16.dp).fillMaxWidth()) {
                Text(f.name, style = MaterialTheme.typography.titleMedium)
                Text("重命名", Modifier.fillMaxWidth().clickableNoRipple {
                    renameTarget = f; actionFolder = null
                }.padding(12.dp))
                Text("删除", Modifier.fillMaxWidth().clickableNoRipple {
                    vm.deleteFolder(f.id.toString()); actionFolder = null
                }.padding(12.dp), color = ErrorRed)
            }
        }
    }

    // 重命名弹窗
    renameTarget?.let { f ->
        FolderNameDialog(
            title = "重命名收藏夹",
            initial = f.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name -> vm.renameFolder(f.id.toString(), name); renameTarget = null },
        )
    }

    // 取消收藏确认对话框
    if (showRemoveConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("取消收藏确认") },
            text = { Text("确定取消选中的 ${selectedIds.size} 本漫画的收藏？") },
            confirmButton = {
                Text("确定取消", color = ErrorRed, modifier = Modifier
                    .clickableNoRipple {
                        showRemoveConfirm = false
                        vm.batchRemove()
                    }
                    .padding(8.dp))
            },
            dismissButton = {
                Text("取消", color = TextSecondary, modifier = Modifier
                    .clickableNoRipple { showRemoveConfirm = false }
                    .padding(8.dp))
            },
        )
    }
}

/** 一行文字型筛选（label + 若干胶囊，FlowRow 自动换行）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(label: String, options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, l) ->
            FilterChip(l, selected = selected == v, onClick = { onSelect(v) })
        }
    }
}

/** 排序行：收藏降序/收藏升序/更新降序/更新升序。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SortRow(order: Int, orderType: Int, onSelect: (Int, Int) -> Unit) {
    val options = listOf(
        Quad(2, 0, "收藏降序"),
        Quad(2, 1, "收藏升序"),
        Quad(1, 0, "更新降序"),
        Quad(1, 1, "更新升序"),
    )
    FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { q ->
            FilterChip(q.label, selected = order == q.order && orderType == q.orderType, onClick = { onSelect(q.order, q.orderType) })
        }
    }
}

private data class Quad(val order: Int, val orderType: Int, val label: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavRow(
    item: FavoriteItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) Color(0xFFE3F0FF) else Color.White)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverUrl(item.bookImg),
            contentDescription = item.bookName,
            modifier = Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(OffWhite),
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.bookName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("最新：${item.chapterName.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if (item.readLast.isNotBlank()) {
                Text("读到：${item.readLast}", style = MaterialTheme.typography.bodySmall, color = BluePrimary, modifier = Modifier.padding(top = 2.dp))
            }
            Text(updateLine("更新", item.lastTime, item.end), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        if (item.isNew) {
            Text("NEW", color = ErrorRed, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ==================== 阅读历史 ====================

@Composable
private fun HistoryContent(vm: LibraryViewModel, onOpenManga: (String) -> Unit) {
    val history by vm.history.collectAsState()
    val loading by vm.historyLoading.collectAsState()
    val hasMore by vm.historyHasMore.collectAsState()
    val selMode by vm.historySelectionMode.collectAsState()
    val selIds by vm.historySelectedIds.collectAsState()
    val listState = rememberLazyListState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(listState.canScrollForward, history.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (history.isNotEmpty() && last >= history.size - 3 && hasMore) vm.loadMoreHistory()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f))
            if (!selMode) {
                Text("删除", color = ErrorRed, modifier = Modifier.clickableNoRipple { vm.enterHistorySelection() }.padding(8.dp))
            } else {
                Text("取消", color = TextSecondary, modifier = Modifier.clickableNoRipple { vm.exitHistorySelection() }.padding(8.dp))
            }
        }
        // 多选顶部栏（与列表平行）
        if (selMode) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选(${selIds.size})", modifier = Modifier.weight(1f))
                val allSelected = history.isNotEmpty() && selIds.size == history.size
                Text(
                    if (allSelected) "取消" else "全选",
                    color = BluePrimary,
                    modifier = Modifier.clickableNoRipple {
                        if (allSelected) vm.exitHistorySelection() else vm.toggleSelectAllLoadedHistory()
                    }.padding(horizontal = 12.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(history, key = { it.id }) { h ->
                    HistoryRow(
                        item = h,
                        selected = selIds.contains(h.id),
                        selectionMode = selMode,
                        onClick = {
                            if (selMode) vm.toggleHistorySelect(h.id)
                            else onOpenManga(h.bookId)
                        },
                    )
                }
                if (loading) item { LoadingFooter() }
                else if (!hasMore && history.isNotEmpty()) item {
                    Text("没有更多了", modifier = Modifier.fillMaxWidth().padding(16.dp), color = TextSecondary)
                }
            }
        }

        // 多选底部栏
        if (selMode) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F2F5)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f).clickableNoRipple {
                        if (selIds.isNotEmpty()) showDeleteConfirm = true
                    }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("删除", color = ErrorRed) }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除确认") },
            text = { Text("确定删除选中的 ${selIds.size} 条阅读历史？") },
            confirmButton = {
                Text("确定删除", color = ErrorRed, modifier = Modifier
                    .clickableNoRipple {
                        showDeleteConfirm = false
                        vm.deleteSelectedHistory()
                    }
                    .padding(8.dp))
            },
            dismissButton = {
                Text("取消", color = TextSecondary, modifier = Modifier
                    .clickableNoRipple { showDeleteConfirm = false }
                    .padding(8.dp))
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(item: HistoryItem, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .background(if (selected) Color(0xFFE3F0FF) else Color.White)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverUrl(item.bookImg),
            contentDescription = item.bookName,
            modifier = Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(OffWhite),
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.bookName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("上次阅读：${item.chapterName.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = BluePrimary)
            Text(updateLine("上次更新", item.lastTime, item.end), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ==================== 通用 ====================

/** 拼“更新/上次更新：time · end”一行。 */
private fun updateLine(prefix: String, time: String, end: String): String {
    val sb = StringBuilder("$prefix：").append(time.ifBlank { "—" })
    if (end.isNotBlank()) sb.append(" · ").append(end)
    return sb.toString()
}

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
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Color.White).padding(16.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
                androidx.compose.material3.TextButton(onClick = { onConfirm(text.trim()) }) { Text("确定") }
            }
        }
    }
}
