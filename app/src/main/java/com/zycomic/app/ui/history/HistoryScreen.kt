package com.zycomic.app.ui.history

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zycomic.app.data.dto.HistoryItem
import com.zycomic.app.ui.components.LoadingFooter
import com.zycomic.app.ui.components.coverUrl
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.animateItemFastScroll
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground

@Composable
fun HistoryScreen(
    vm: HistoryViewModel,
    onOpenManga: (String) -> Unit,
    onRequireLogin: () -> Unit,
    onOpenSearch: (String) -> Unit,
) {
    val context = LocalContext.current

    val user by vm.user.collectAsState()
    val history by vm.history.collectAsState()
    val filtered by vm.filteredHistory.collectAsState()
    val loading by vm.historyLoading.collectAsState()
    val hasMore by vm.historyHasMore.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val deleting by vm.deleting.collectAsState()
    val toast by vm.toast.collectAsState()

    LaunchedEffect(vm.needLogin) {
        vm.needLogin.collect { if (it) onRequireLogin() }
    }

    // 一次性 Toast 提示（删除/收藏成败）
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    // 多选模式下按返回键 = 退出多选
    BackHandler(enabled = selectionMode) { vm.exitSelection() }

    var showBatchDelete by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showFavoriteConfirm by remember { mutableStateOf<HistoryItem?>(null) }
    androidx.activity.compose.BackHandler(enabled = showBatchDelete) { showBatchDelete = false }
    androidx.activity.compose.BackHandler(enabled = showFolderDialog) { showFolderDialog = false }
    androidx.activity.compose.BackHandler(enabled = showFavoriteConfirm != null) { showFavoriteConfirm = null }
    var singleDeleteTarget by remember { mutableStateOf<HistoryItem?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState.canScrollForward, filtered.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (filtered.isNotEmpty() && last >= filtered.size - 3 && hasMore) {
            vm.loadMoreHistory()
        }
    }

    Scaffold(
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
                    titleContent = { AppBarTitle("历史") },
                    searchQuery = searchQuery,
                    onChangeSearchQuery = { vm.updateSearchQuery(it) },
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = "刷新",
                                    icon = Icons.Default.Refresh,
                                    onClick = {
                                        vm.refreshHistory()
                                        scope.launch { listState.scrollToItem(0) }
                                    },
                                ),
                                AppBar.Action(
                                    title = "多选",
                                    icon = Icons.Outlined.Checklist,
                                    onClick = { vm.enterSelection() },
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
                    // 全部收藏夹
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = selectedIds.isNotEmpty()) { vm.favoriteSelected(0) }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "全部收藏夹",
                            color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 其他收藏夹
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = selectedIds.isNotEmpty()) {
                                vm.loadFolders()
                                showFolderDialog = true
                            }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "其他收藏夹",
                            color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 删除
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = selectedIds.isNotEmpty() && !deleting) { showBatchDelete = true }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "删除",
                            color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        when {
            // 未登录
            user == null -> EmptyScreen(
                message = "请先登录",
                modifier = Modifier.padding(contentPadding),
            )
            // 首次加载中
            loading && history.isEmpty() -> LoadingScreen(modifier = Modifier.padding(contentPadding))
            // 列表为空
            filtered.isEmpty() -> EmptyScreen(
                message = if (!searchQuery.isNullOrBlank()) "没有搜索结果" else "暂无阅读历史",
                modifier = Modifier.padding(contentPadding),
            )
            // 列表
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    vm.refreshHistory()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LaunchedEffect(loading) {
                    if (!loading) isRefreshing = false
                }
                FastScrollLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 本地搜索词非空时，提供"全局搜索"入口
                if (!searchQuery.isNullOrBlank()) {
                    item(key = "global-search", contentType = "global-search") {
                        androidx.compose.material3.TextButton(
                            onClick = { onOpenSearch(searchQuery ?: "") },
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
                filtered.forEach { item ->
                    item(
                        key = "history-${item.id}",
                        contentType = "item",
                    ) {
                        HistoryItemRow(
                            item = item,
                            selected = item.bookId in selectedIds,
                            selectionMode = selectionMode,
                            onClickRow = {
                                if (selectionMode) vm.toggleSelect(item.bookId)
                                else onOpenManga(item.bookId)
                            },
                            onLongClickRow = {
                                if (selectionMode) vm.toggleSelect(item.bookId)
                                else vm.enterSelectionAndSelect(item.bookId)
                            },
                            onClickCover = { onOpenManga(item.bookId) },
                            onClickFavorite = { showFavoriteConfirm = item },
                            onClickDelete = { singleDeleteTarget = item },
                        )
                    }
                }
                // 底部分页
                if (loading) {
                    item(key = "loading-footer", contentType = "footer") { LoadingFooter() }
                } else if (!hasMore) {
                    item(key = "no-more", contentType = "footer") {
                        Text(
                            text = "没有更多了",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
                }
            }
        }
    }

    // 单条删除确认
    singleDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { singleDeleteTarget = null },
            title = { Text("删除确认") },
            text = { Text("确定删除这条阅读历史？") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !deleting,
                    onClick = {
                        vm.deleteSingle(target.bookId)
                        singleDeleteTarget = null
                    },
                ) {
                    Text("确定删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { singleDeleteTarget = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    // 收藏确认
    showFavoriteConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { showFavoriteConfirm = null },
            title = { Text("收藏确认") },
            text = { Text("确定收藏《${target.bookName}》到全部收藏夹？") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.favoriteSingle(target.bookId)
                        showFavoriteConfirm = null
                    },
                ) {
                    Text("确定收藏", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showFavoriteConfirm = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    // 批量删除确认
    if (showBatchDelete) {
        AlertDialog(
            onDismissRequest = { showBatchDelete = false },
            title = { Text("删除确认") },
            text = { Text("确定删除选中的 ${selectedIds.size} 条阅读历史？") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !deleting,
                    onClick = {
                        vm.deleteSelected()
                        showBatchDelete = false
                    },
                ) {
                    Text("确定删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showBatchDelete = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    // 收藏夹选择对话框
    if (showFolderDialog) {
        val folders by vm.folders.collectAsState()
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showFolderDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "选择收藏夹",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showFolderDialog = false }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider()
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.favoriteSelected(0)
                                    showFolderDialog = false
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Text("默认收藏夹", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                    items(folders) { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.favoriteSelected(f.id)
                                    showFolderDialog = false
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("${f.count}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemRow(
    item: HistoryItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClickRow: () -> Unit,
    onLongClickRow: () -> Unit,
    onClickCover: () -> Unit,
    onClickFavorite: () -> Unit,
    onClickDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClickRow,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClickRow()
                },
            )
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverUrl(item.bookImg),
            contentDescription = item.bookName,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(3f / 4f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(
                    onClick = onClickCover,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClickRow()
                    },
                ),
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
        // 多选模式下隐藏行内"收藏/删除"，避免误触单条操作（统一走底部批量栏）
        if (!selectionMode) {
            IconButton(onClick = onClickFavorite) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onClickDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

