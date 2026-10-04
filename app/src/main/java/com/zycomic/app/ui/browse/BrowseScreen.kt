package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
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
import androidx.compose.runtime.collectAsState
import coil3.compose.AsyncImage
import com.zycomic.app.data.AllTags
import com.zycomic.app.data.dto.Manga
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
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.components.material.TabText

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
    val pagerState = rememberPagerState(pageCount = { 3 })
    LaunchedEffect(mainTab) {
        if (pagerState.currentPage != mainTab) pagerState.animateScrollToPage(mainTab)
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

    var showFilterDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }

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
                                AppBar.Action(title = "搜索", icon = Icons.Default.Search, onClick = onOpenSearch),
                                AppBar.Action(title = "刷新", icon = Icons.Default.Refresh, onClick = { vm.refresh() }),
                                AppBar.Action(title = "筛选", icon = Icons.Outlined.FilterList, onClick = { showFilterDialog = true }),
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
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                listOf("分类", "最近更新", "排行").forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index); vm.selectMainTab(index) } },
                        text = { TabText(text = title) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
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
                    onGridColumnsChange = { vm.setGridColumns(it) },
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

    // 筛选对话框
    if (showFilterDialog) {
        BrowseFilterDialog(
            vm = vm,
            currentTab = mainTab,
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
    val mangas by vm.mangas.collectAsState()
    val loading by vm.loading.collectAsState()
    val appending by vm.appending.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val error by vm.error.collectAsState()

    // 每个tab独立的滚动状态，避免切换tab时内容重叠
    val gridStates = remember { Array(3) { LazyGridState() } }
    val gridState = gridStates[mainTab]

    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(gridState.canScrollForward, mangas.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (mangas.isNotEmpty() && lastVisible >= mangas.size - 4 && hasMore && !loading) {
            vm.loadMore()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            loading && mangas.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中...") }
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
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text("加载中...") }
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
                model = manga.picx ?: manga.pic ?: "",
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
                when (currentTab) {
                    0 -> CategoryFilter(vm, onOpenTagDialog)
                    1 -> NewestFilter(vm)
                    2 -> RankFilter(vm)
                }
            } else {
                DisplayFilter(vm)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryFilter(vm: BrowseViewModel, onOpenTagDialog: () -> Unit) {
    val gender by vm.gender.collectAsState()
    val selectedTags by vm.selectedTags.collectAsState()
    val area by vm.area.collectAsState()
    val end by vm.end.collectAsState()
    val st by vm.st.collectAsState()

    FilterSection("性向") {
        BrowseViewModel.GENDERS.forEach { (v, label) ->
            FilterChip(text = label, selected = gender == v, onClick = { vm.selectGender(v) })
        }
    }
    FilterSection("标签") {
        FilterChip(text = "全部", selected = selectedTags.isEmpty(), onClick = { vm.clearTags() })
        AllTags.PINNED_TAGS.forEach { tag ->
            FilterChip(text = tag, selected = selectedTags.contains(tag), onClick = { vm.toggleTag(tag) })
        }
        selectedTags.filter { it !in AllTags.PINNED_TAGS }.take(3).forEach { tag ->
            FilterChip(text = tag, selected = true, onClick = { vm.toggleTag(tag) })
        }
        FilterChip(text = "更多", selected = false, bold = true, onClick = onOpenTagDialog)
    }
    FilterSection("地区") {
        BrowseViewModel.AREAS.forEach { (v, label) ->
            FilterChip(text = label, selected = area == v, onClick = { vm.selectArea(v) })
        }
    }
    FilterSection("状态") {
        BrowseViewModel.ENDS.forEach { (v, label) ->
            FilterChip(text = label, selected = end == v, onClick = { vm.selectEnd(v) })
        }
    }
    FilterSection("排序方式") {
        BrowseViewModel.STS.forEach { (v, label) ->
            FilterChip(text = label, selected = st == v, onClick = { vm.selectSt(v) })
        }
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayFilter(vm: BrowseViewModel) {
    val displayMode by vm.displayMode.collectAsState()
    val gridColumns by vm.gridColumns.collectAsState()

    FilterSection("显示模式") {
        listOf(0 to "紧凑网格", 1 to "舒适网格", 2 to "仅封面网格").forEach { (v, label) ->
            FilterChip(text = label, selected = displayMode == v, onClick = { vm.setDisplayMode(v) })
        }
    }

    FilterSection("每行数量") {
        Text(if (gridColumns == 0) "自动" else gridColumns.toString(), modifier = Modifier.padding(end = 8.dp))
        androidx.compose.material3.Slider(
            value = gridColumns.toFloat(),
            onValueChange = { vm.setGridColumns(it.toInt()) },
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit, bold: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSelectDialog(vm: BrowseViewModel, onDismiss: () -> Unit) {
    val allTags = AllTags.LIST
    val current by vm.selectedTags.collectAsState()
    var keyword by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf(current) }

    val filtered = remember(keyword, allTags) {
        if (keyword.isBlank()) allTags else allTags.filter { it.contains(keyword, ignoreCase = true) }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择标签", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("已选 ${temp.size} 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.OutlinedTextField(
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { temp = emptySet() }) { Text("重置") }
                Box(modifier = Modifier.weight(1f))
                androidx.compose.material3.Button(onClick = { vm.setSelectedTags(temp); onDismiss() }) { Text("确定") }
            }
        }
    }
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
