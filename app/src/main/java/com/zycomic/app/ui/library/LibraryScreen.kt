package com.zycomic.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import eu.kanade.presentation.components.TabbedDialog
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground

@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenManga: (String) -> Unit,
    onRequireLogin: () -> Unit,
    onOpenSearch: (String) -> Unit,
) {
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
    val gridColumns by vm.gridColumns.collectAsState()
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    androidx.compose.runtime.LaunchedEffect(orientation) { vm.loadGridColumnsForOrientation(orientation) }
    val showUnreadBadge by vm.showUnreadBadge.collectAsState()
    val showUpdateBadge by vm.showUpdateBadge.collectAsState()

    LaunchedEffect(vm.needLogin) {
        vm.needLogin.collect { if (it) onRequireLogin() }
    }

    // 页面首次可见时：收藏夹为空且已登录则请求一次（缓存）
    LaunchedEffect(Unit) {
        vm.ensureFoldersLoaded()
    }

    // 多选模式下按返回键 = 退出多选
    BackHandler(enabled = selectionMode) { vm.exitSelection() }

    var showFilterDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showFilterDialog) { showFilterDialog = false }
    androidx.activity.compose.BackHandler(enabled = showMoveDialog) { showMoveDialog = false }
    androidx.activity.compose.BackHandler(enabled = showRemoveConfirm) { showRemoveConfirm = false }
    androidx.activity.compose.BackHandler(enabled = showNewFolderDialog) { showNewFolderDialog = false }
    var renameTarget by remember { mutableStateOf<Folder?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(listState.canScrollForward, filtered.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (filtered.isNotEmpty() && last >= filtered.size - 3 && hasMore) vm.loadMoreFavorites()
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
                val isSearching = searchQuery != null
                val focusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current
                val focusManager = LocalFocusManager.current
                LaunchedEffect(isSearching) {
                    if (isSearching) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }
                AppBar(
                    navigationIcon = if (isSearching) Icons.AutoMirrored.Filled.ArrowBack else null,
                    navigateUp = if (isSearching) {
                        {
                            vm.updateSearchQuery(null)
                            focusManager.clearFocus()
                        }
                    } else null,
                    titleContent = {
                        if (isSearching) {
                            BasicTextField(
                                value = searchQuery ?: "",
                                onValueChange = { vm.updateSearchQuery(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.Normal,
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                singleLine = true,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppBarTitle("收藏")
                                val user by com.zycomic.app.data.repository.UserRepository.userFlow.collectAsState()
                                user?.let {
                                    val max = when { it.level >= 5 -> 3500; it.level == 4 -> 2500; it.level == 3 -> 1500; it.level == 2 -> 800; it.level == 1 -> 300; else -> 0 }
                                    Spacer(Modifier.width(8.dp))
                                    Text("已收藏 ${it.favoriteCount}/$max", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    actions = {
                        AppBarActions(
                            if (!isSearching) {
                                persistentListOf(
                                    AppBar.Action(title = "搜索", icon = Icons.Default.Search, onClick = { vm.updateSearchQuery("") }),
                                    AppBar.Action(title = "刷新", icon = Icons.Default.Refresh, onClick = { vm.refreshFavorites() }),
                                    AppBar.Action(title = "筛选", icon = Icons.Outlined.FilterList, onClick = { showFilterDialog = true }),
                                    AppBar.Action(title = "多选", icon = Icons.Outlined.Checklist, onClick = { vm.enterSelection() }),
                                )
                            } else {
                                persistentListOf(
                                    AppBar.Action(title = "清除", icon = Icons.Default.Close, onClick = { vm.updateSearchQuery("") }),
                                    AppBar.Action(title = "筛选", icon = Icons.Outlined.FilterList, onClick = { showFilterDialog = true }),
                                )
                            },
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
            else -> {
                Box(modifier = Modifier.fillMaxSize()) {
                if (displayMode <= 2) {
                // 网格模式：0=紧凑 1=舒适 2=仅封面
                val columns = if (gridColumns > 0) gridColumns else if (displayMode == 1) 2 else 3
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!searchQuery.isNullOrBlank()) {
                        item(key = "global-search", contentType = "global-search", span = { GridItemSpan(columns) }) {
                            TextButton(
                                onClick = { onOpenSearch(searchQuery ?: "") },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Text(text = "全局搜索：${searchQuery}", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    gridItems(filtered, key = { it.id }, contentType = { "grid" }) { item ->
                        FavGridItem(
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
                        item(key = "loading-footer", contentType = "footer", span = { GridItemSpan(columns) }) { LoadingFooter() }
                    } else if (!hasMore) {
                        item(key = "no-more", contentType = "footer", span = { GridItemSpan(columns) }) {
                            Text(text = "没有更多了", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                }
            } else {
                FastScrollLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 本地搜索词非空时，提供“全局搜索”入口
                if (!searchQuery.isNullOrBlank()) {
                    item(key = "global-search", contentType = "global-search") {
                        TextButton(
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
                items(filtered, key = { it.id }, contentType = { "item" }) { item ->
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

/** 网格项：封面+漫画名（仅封面网格只有封面）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavGridItem(
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
    Column(
        modifier = Modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 封面用 AspectRatio 保持 3:4 比例
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = coverUrl(item.bookImg),
                contentDescription = item.bookName,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            // 未读完标记（左上角）
            if (showUnreadBadge && item.isUnread()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text("未读", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
            // 更新标记（紧凑网格放右上角，避免和底部标题重合；其他网格放右下角）
            if (showUpdateBadge && item.isNew) {
                Box(
                    modifier = Modifier
                        .align(if (displayMode == 0) Alignment.TopEnd else Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text("NEW", color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            // 紧凑网格：标题叠加在封面底部，带渐变背景
            if (displayMode == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.35f)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color(0xAA000000),
                            ),
                        ),
                )
                Text(
                    text = item.bookName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(
                        shadow = Shadow(color = Color.Black, blurRadius = 4f),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
            }
        }
        // 舒适网格显示标题在封面下方；紧凑网格标题已叠加在封面里；仅封面网格不显示标题
        if (displayMode == 1) {
            Text(
                text = item.bookName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

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
    if (displayMode == 4) {
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
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
            ItemBadges(
                item = item,
                showUnreadBadge = showUnreadBadge,
                showUpdateBadge = showUpdateBadge,
            )
        }
    } else if (displayMode == 3) {
        // 列表1（只有封面+标题）
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
                .height(56.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverWithBadges(
                item = item,
                coverWidth = 40.dp,
                coverHeight = 48.dp,
                showUnreadBadge = showUnreadBadge,
                showUpdateBadge = showUpdateBadge,
            )
            Text(
                text = item.bookName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    } else {
        // 列表3（当前详细样式）
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
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
            ItemBadges(
                item = item,
                showUnreadBadge = showUnreadBadge,
                showUpdateBadge = showUpdateBadge,
            )
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
    showBadges: Boolean = true,
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
        if (showBadges) {
            if (showUnreadBadge && item.isUnread()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        "未读完",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (showUpdateBadge && item.isNew) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        "NEW",
                        color = MaterialTheme.colorScheme.onError,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** 列表项右侧的标记组：未读完（蓝色）+ NEW（红色）。 */
@Composable
private fun ItemBadges(
    item: FavoriteItem,
    showUnreadBadge: Boolean,
    showUpdateBadge: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showUnreadBadge && item.isUnread()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    "未读完",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (showUpdateBadge && item.isNew) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    "NEW",
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
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
    val folders by vm.folders.collectAsState()
    val selectedFolder by vm.selectedFolderId.collectAsState()
    val isFull by vm.isFullVersion.collectAsState()
    val order by vm.order.collectAsState()
    val orderType by vm.orderType.collectAsState()
    val isUnread by vm.isUnreadFilter.collectAsState()
    val isRead by vm.isReadFilter.collectAsState()
    val isEnd by vm.isEndFilter.collectAsState()
    val onlyUpdated by vm.onlyUpdatedFilter.collectAsState()
    val displayMode by vm.displayMode.collectAsState()
    val gridColumns by vm.gridColumns.collectAsState()
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    androidx.compose.runtime.LaunchedEffect(orientation) { vm.loadGridColumnsForOrientation(orientation) }
    val showUnreadBadge by vm.showUnreadBadge.collectAsState()
    val showUpdateBadge by vm.showUpdateBadge.collectAsState()

    TabbedDialog(
        onDismissRequest = onDismiss,
        tabTitles = persistentListOf("筛选", "排序", "显示", "管理"),
    ) { page ->
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            when (page) {
                0 -> {
                    // 未读完（客户端筛选）
                    SingleChoiceChipRow(
                        title = "未读完",
                        options = listOf(-1 to "全部", 1 to "未读完", 0 to "已读完"),
                        selected = isUnread,
                        onSelect = { vm.selectIsUnread(it) },
                    )
                    HorizontalDivider()
                    // 阅读过（客户端筛选）
                    SingleChoiceChipRow(
                        title = "阅读过",
                        options = listOf(-1 to "全部", 1 to "阅读过", 0 to "未读过"),
                        selected = isRead,
                        onSelect = { vm.selectIsRead(it) },
                    )
                    HorizontalDivider()
                    // 完结（客户端筛选）
                    SingleChoiceChipRow(
                        title = "完结",
                        options = listOf(-1 to "全部", 1 to "完结", 0 to "连载"),
                        selected = isEnd,
                        onSelect = { vm.selectIsEnd(it) },
                    )
                    HorizontalDivider()
                    // 收藏夹（服务端筛选）
                    SingleChoiceChipRow(
                        title = "收藏夹",
                        options = buildList {
                            add(0 to "全部收藏夹")
                            folders.forEach { add(it.id to it.name) }
                        },
                        selected = selectedFolder,
                        onSelect = { vm.selectFolder(it) },
                    )
                    HorizontalDivider()
                    // 画质（服务端筛选）
                    SingleChoiceChipRow(
                        title = "画质",
                        options = listOf(-1 to "全部", 1 to "高清", 2 to "清水版", 3 to "未删减", 4 to "完整版"),
                        selected = isFull,
                        onSelect = { vm.selectIsFull(it) },
                    )
                    HorizontalDivider()
                    // 只显示更新（客户端筛选）
                    SingleChoiceChipRow(
                        title = "只显示更新",
                        options = listOf(0 to "全部", 1 to "只显示更新"),
                        selected = if (onlyUpdated) 1 else 0,
                        onSelect = { vm.selectOnlyUpdated(it == 1) },
                    )
                }
                1 -> {
                    // 排序（客户端本地排序，请求时也带参数）
                    SingleChoiceChipRow(
                        title = "排序",
                        options = listOf(
                            0 to "收藏降序",
                            1 to "收藏升序",
                            2 to "更新降序",
                            3 to "更新升序",
                        ),
                        selected = when {
                            order == 2 && orderType == 0 -> 0
                            order == 2 && orderType == 1 -> 1
                            order == 1 && orderType == 0 -> 2
                            else -> 3
                        },
                        onSelect = {
                            when (it) {
                                0 -> vm.selectSort(2, 0)
                                1 -> vm.selectSort(2, 1)
                                2 -> vm.selectSort(1, 0)
                                else -> vm.selectSort(1, 1)
                            }
                        },
                    )
                }
                2 -> {
                    // 显示模式
                    SingleChoiceChipRow(
                        title = "显示模式",
                        options = listOf(
                            0 to "紧凑网格",
                            1 to "舒适网格",
                            2 to "仅封面网格",
                            3 to "列表1",
                            4 to "列表2",
                            5 to "列表3",
                        ),
                        selected = displayMode,
                        onSelect = { vm.selectDisplayMode(it) },
                    )
                    HorizontalDivider()
                    if (displayMode <= 2) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("每行数量", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (gridColumns == 0) "自动" else gridColumns.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Slider(
                                value = gridColumns.toFloat(),
                                onValueChange = { vm.setGridColumns(it.toInt(), orientation) },
                                valueRange = 0f..10f,
                                steps = 9,
                            )
                        }
                        HorizontalDivider()
                    }
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

/** 单选芯片行：标题 + FlowRow 自动换行，选中态高亮，只能选一个。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleChoiceChipRow(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    text = label,
                    selected = selected == value,
                    onClick = { onSelect(value) },
                )
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
