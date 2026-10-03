package com.zycomic.app.ui.history

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
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
    onOpenManga: (String) -> Unit,
    onRequireLogin: () -> Unit,
) {
    val vm = remember { HistoryViewModel() }
    val context = LocalContext.current

    val user by vm.user.collectAsState()
    val history by vm.history.collectAsState()
    val filtered by vm.filteredHistory.collectAsState()
    val loading by vm.historyLoading.collectAsState()
    val hasMore by vm.historyHasMore.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()

    LaunchedEffect(vm.needLogin) {
        vm.needLogin.collect { if (it) onRequireLogin() }
    }

    // 多选模式下按返回键 = 退出多选
    BackHandler(enabled = selectionMode) { vm.exitSelection() }

    var showBatchDelete by remember { mutableStateOf(false) }
    var singleDeleteTarget by remember { mutableStateOf<HistoryItem?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(listState.canScrollForward, filtered.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (filtered.isNotEmpty() && last >= filtered.size - 3 && hasMore) {
            vm.loadMoreHistory()
        }
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
                                AppBar.Action(
                                    title = "删除",
                                    icon = Icons.Outlined.DeleteSweep,
                                    onClick = { showBatchDelete = true },
                                    enabled = selectedIds.isNotEmpty(),
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
                                    title = "筛选",
                                    icon = Icons.Outlined.FilterList,
                                    onClick = {
                                        Toast.makeText(context, "筛选功能即将上线", Toast.LENGTH_SHORT).show()
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
            else -> FastScrollLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                filtered.forEach { item ->
                    item(
                        key = "history-${item.id}",
                        contentType = "item",
                    ) {
                        HistoryItemRow(
                            item = item,
                            selected = item.id in selectedIds,
                            selectionMode = selectionMode,
                            onClickRow = {
                                if (selectionMode) vm.toggleSelect(item.id)
                                else onOpenManga(item.bookId)
                            },
                            onLongClickRow = {
                                if (selectionMode) vm.toggleSelect(item.id)
                                else vm.enterSelectionAndSelect(item.id)
                            },
                            onClickCover = { onOpenManga(item.bookId) },
                            onClickFavorite = {
                                Toast.makeText(context, "收藏功能即将上线", Toast.LENGTH_SHORT).show()
                            },
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

    // 单条删除确认
    singleDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { singleDeleteTarget = null },
            title = { Text("删除确认") },
            text = { Text("确定删除这条阅读历史？") },
            confirmButton = {
                Text(
                    text = "确定删除",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(
                            onClick = {
                                vm.deleteSingle(target.id)
                                singleDeleteTarget = null
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(onClick = { singleDeleteTarget = null })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
                Text(
                    text = "确定删除",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(
                            onClick = {
                                vm.deleteSelected()
                                showBatchDelete = false
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(onClick = { showBatchDelete = false })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            },
        )
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
        IconButton(onClick = onClickFavorite) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = "收藏",
                tint = MaterialTheme.colorScheme.onSurface,
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

