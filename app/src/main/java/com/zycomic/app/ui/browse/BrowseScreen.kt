package com.zycomic.app.ui.browse

import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.FilterSection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import coil3.compose.AsyncImage
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.ui.components.DisplaySettingsSection
import com.zycomic.app.ui.components.MangaGridSkeleton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun BrowseScreen(
    vm: BrowseViewModel,
    pendingTag: String?,
    onPendingTagConsumed: () -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onRequireLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    pendingTag?.let { tag ->
        LaunchedEffect(tag) {
            vm.applyPendingTag(tag)
            onPendingTagConsumed()
        }
    }

    val mainTab by vm.mainTab.collectAsState()
    val tabs = vm.tabs
    val pagerState = rememberPagerState(pageCount = { tabs.size.coerceAtLeast(1) })
    LaunchedEffect(mainTab, tabs.size) {
        val target = mainTab.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    // 监听用户左右滑动切换页面，同步到 ViewModel 并触发请求
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .filter { it != vm.mainTab.value }
            .collect { page -> vm.selectMainTab(page) }
    }

    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val displayMode by vm.displayMode.collectAsState()
    val gridColumns by vm.gridColumns.collectAsState()
    val folders by vm.folders.collectAsState()
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    androidx.compose.runtime.LaunchedEffect(orientation) { vm.loadGridColumnsForOrientation(orientation) }

    var showFilterDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showManageSheet by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showFilterDialog) { showFilterDialog = false }
    androidx.activity.compose.BackHandler(enabled = showFolderDialog) { showFolderDialog = false }
    androidx.activity.compose.BackHandler(enabled = showTagDialog) { showTagDialog = false }
    androidx.activity.compose.BackHandler(enabled = showManageSheet) { showManageSheet = false }

    LaunchedEffect(selectionMode) {
        if (selectionMode) vm.loadFolders()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                AppBar(
                    titleContent = { Text("${selectedIds.size}") },
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(title = "全选", icon = Icons.Outlined.SelectAll, onClick = { vm.selectAll() }),
                                AppBar.Action(title = "反选", icon = Icons.Outlined.FlipToBack, onClick = { vm.invertSelection() }),
                            ),
                        )
                    },
                    isActionMode = true,
                    onCancelActionMode = { vm.exitSelection() },
                )
            } else {
                SearchToolbar(
                    titleContent = { AppBarTitle("分类") },
                    searchEnabled = false,
                    searchQuery = null,
                    onChangeSearchQuery = {},
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(title = "使用说明", icon = Icons.Outlined.Info, onClick = { showGuideDialog = true }),
                                AppBar.Action(title = "搜索", icon = Icons.Default.Search, onClick = onOpenSearch),
                                AppBar.Action(title = "刷新", icon = Icons.Default.Refresh, onClick = { vm.refresh() }),
                                AppBar.Action(title = "重新筛选", icon = Icons.Outlined.FilterList, onClick = { showFilterDialog = true }),
                                AppBar.Action(title = "多选", icon = Icons.Outlined.Checklist, onClick = { vm.enterSelection() }),
                            ),
                        )
                    },
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
                            .clickable {
                                vm.addToAllFolders { ok, msg ->
                                    if (ok) vm.exitSelection()
                                }
                            }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("添加到全部收藏夹", color = MaterialTheme.colorScheme.primary) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showFolderDialog = true }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("添加到其他收藏夹", color = MaterialTheme.colorScheme.primary) }
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = contentPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                ),
        ) {
            // 可横向滑动的紧凑 Tab 列表（占满剩余宽度）+ 最右固定「管理」按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceIn(0, tabs.lastIndex.coerceAtLeast(0)),
                    modifier = Modifier.weight(1f),
                    edgePadding = 0.dp,
                    divider = {},
                ) {
                    tabs.forEachIndexed { index, item ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index); vm.selectMainTab(index) } },
                            text = {
                                Text(
                                    text = item.name,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                IconButton(onClick = { showManageSheet = true }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "管理")
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Top,
            ) { page ->
                BrowseTabContent(
                    page = page,
                    vm = vm,
                    displayMode = displayMode,
                    gridColumns = gridColumns,
                    onGridColumnsChange = { vm.setGridColumns(it, orientation) },
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onOpenManga = onOpenManga,
                    onToggleSelect = { vm.toggleSelect(it) },
                    onLongClick = { if (!selectionMode) { vm.enterSelection(); vm.toggleSelect(it) } },
                    bottomPadding = contentPadding.calculateBottomPadding(),
                )
            }
        }
    }

    // 使用说明对话框
    if (showGuideDialog) {
        BrowseGuideDialog(onDismiss = { showGuideDialog = false })
    }

    // Tab 管理面板
    if (showManageSheet) {
        TabManageSheet(vm = vm, onDismiss = { showManageSheet = false })
    }

    // 筛选对话框
    if (showFilterDialog) {
        BrowseFilterDialog(
            vm = vm,
            currentTab = mainTab,
            orientation = orientation,
            onDismiss = { showFilterDialog = false },
            onOpenTagDialog = { showTagDialog = true },
        )
    }

    // 标签选择弹窗
    if (showTagDialog) {
        TagSelectDialog(
            vm = vm,
            onDismiss = { showTagDialog = false },
        )
    }

    // 收藏夹选择弹窗
    if (showFolderDialog) {
        FolderSelectDialog(
            folders = folders,
            selectedIds = selectedIds,
            onDismiss = { showFolderDialog = false },
            onConfirm = { folderId ->
                vm.addToFolder(folderId.toString()) { ok, msg ->
                    if (ok) { showFolderDialog = false; vm.exitSelection() }
                }
            },
        )
    }
}

@Composable
private fun BrowseTabContent(
    page: Int,
    vm: BrowseViewModel,
    displayMode: Int,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onOpenManga: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongClick: (String) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val mangas by vm.mangasForTab(page).collectAsState()
    val loading by vm.loadingForTab(page).collectAsState()
    val appending by vm.appendingForTab(page).collectAsState()
    val hasMore by vm.hasMoreForTab(page).collectAsState()
    val error by vm.errorForTab(page).collectAsState()

    // 每个 tab 页独立的滚动状态（动态 tab 数量，按 page 记忆）
    val gridState = remember(page) { LazyGridState() }

    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(gridState.canScrollForward, mangas.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (page == vm.mainTab.value && mangas.isNotEmpty() && lastVisible >= mangas.size - 4 && hasMore && !loading) {
            vm.loadMore(page)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            loading && mangas.isEmpty() -> MangaGridSkeleton(
                columns = if (gridColumns > 0) gridColumns else 3,
                displayMode = displayMode,
            )
            error != null && mangas.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.refresh() }) { Text("重试") }
                }
            }
            mangas.isEmpty() && !loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有漫画") }
            else -> Column(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        vm.refresh()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    LaunchedEffect(loading) {
                        if (!loading) isRefreshing = false
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (gridColumns > 0) gridColumns else 3),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, if (displayMode == 2) 4.dp else bottomPadding + 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                    items(mangas.size, key = { mangas[it].id + "_$it" }) { i ->
                        val m = mangas[i]
                        MangaGridItem(
                            manga = m,
                            displayMode = displayMode,
                            selected = selectionMode && m.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = { if (selectionMode) onToggleSelect(m.id) else onOpenManga(m.id) },
                            onLongClick = { onLongClick(m.id) },
                        )
                    }
                    if (appending) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(modifier = Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("加载中...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (!hasMore && mangas.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("没有更多了", modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                }
                // 仅封面网格模式：固定底部每行数量选择
                if (displayMode == 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (gridColumns == 0) "自动" else gridColumns.toString(),
                            modifier = Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        androidx.compose.material3.Slider(
                            value = gridColumns.toFloat(),
                            onValueChange = { onGridColumnsChange(it.toInt()) },
                            valueRange = 0f..10f,
                            steps = 9,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaGridItem(
    manga: Manga,
    displayMode: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) else Modifier)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = com.zycomic.app.ui.components.coverUrl(manga.picx ?: manga.pic ?: ""),
                contentDescription = manga.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 紧凑网格：标题叠加封面底部
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
                    text = manga.name ?: "",
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
        // 舒适网格显示标题；紧凑网格标题已叠加；仅封面网格不显示
        if (displayMode == 1) {
            Text(
                text = manga.name ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseFilterDialog(
    vm: BrowseViewModel,
    currentTab: Int,
    orientation: Int,
    onDismiss: () -> Unit,
    onOpenTagDialog: () -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismiss,
        tabTitles = persistentListOf("筛选", "显示"),
    ) { page ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(TabbedDialogPaddings.Horizontal),
        ) {
            if (page == 0) {
                val item = vm.tabs.getOrNull(currentTab)
                when {
                    item == null -> CategoryFilter(vm, onOpenTagDialog)
                    item.kind == TabKind.FILTER -> CategoryFilter(vm, onOpenTagDialog)
                    item.pageType == BrowsePageType.RANKING -> RankFilter(vm)
                    item.pageType == BrowsePageType.LATEST -> NewestFilter(vm)
                    else -> CategoryFilter(vm, onOpenTagDialog)
                }
            } else {
                DisplayFilter(vm, orientation)
            }
        }
    }
}

@Composable
private fun CategoryFilter(vm: BrowseViewModel, onOpenTagDialog: () -> Unit) {
    // 「重新筛选」绑定当前 tab 的会话临时筛选，改动当次有效、不回存预设
    val f by vm.curFilter.collectAsState()
    FilterSnapshotEditor(
        snapshot = f,
        onChange = { vm.applyTempFilter(it) },
        onOpenTagDialog = onOpenTagDialog,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewestFilter(vm: BrowseViewModel) {
    val date by vm.newestDate.collectAsState()
    val newestNums by vm.newestNums.collectAsState()

    FilterSection("日期") {
        vm.dateOptions.forEach { (label, key) ->
            FilterChip(text = label, selected = date == key, onClick = { vm.selectDate(key) })
        }
    }
    Text(
        "更新了${newestNums}本，持续更新中",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RankFilter(vm: BrowseViewModel) {
    val rankType by vm.rankType.collectAsState()
    FilterSection("排行类型") {
        listOf(0 to "人气榜", 1 to "新番榜", 2 to "完结榜").forEach { (v, label) ->
            FilterChip(text = label, selected = rankType == v, onClick = { vm.selectRankType(v) })
        }
    }
}

@Composable
private fun DisplayFilter(vm: BrowseViewModel, orientation: Int) {
    val displayMode by vm.displayMode.collectAsState()
    val gridColumns by vm.gridColumns.collectAsState()

    DisplaySettingsSection(
        displayMode = displayMode,
        gridColumns = gridColumns,
        orientation = orientation,
        onSetDisplayMode = vm::setDisplayMode,
        onSetGridColumns = { vm.setGridColumns(it, orientation) },
    )
}

@Composable
private fun TagSelectDialog(vm: BrowseViewModel, onDismiss: () -> Unit) {
    // 委托给通用受控标签选择器；确定结果写入当前 tab 的会话临时筛选
    val f by vm.curFilter.collectAsState()
    TagPickerDialog(
        initial = f.tags.toSet(),
        onDismiss = onDismiss,
        onConfirm = { set -> vm.setSelectedTags(set); onDismiss() },
    )
}

@Composable
private fun FolderSelectDialog(
    folders: List<com.zycomic.app.data.dto.Folder>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selectedFolder by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择收藏夹") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("已选 ${selectedIds.size} 本漫画", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                folders.forEach { folder ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedFolder = folder.id }
                            .padding(vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .background(if (selectedFolder == folder.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(4.dp),
                            )
                            Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (folders.isEmpty()) {
                    Text("暂无收藏夹", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedFolder?.let { onConfirm(it) } },
                enabled = selectedFolder != null,
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
