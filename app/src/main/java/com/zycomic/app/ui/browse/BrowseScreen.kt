package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.imageLoader
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.rememberContentPlatformAccent
import com.zycomic.app.reader.JmSource
import com.zycomic.app.ui.components.DisplaySettingsSection
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.FilterSection
import com.zycomic.app.ui.components.MangaGridSkeleton
import com.zycomic.app.ui.search.SearchProvider
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
    onOpenSearch: (String, SearchProvider?) -> Unit,
    onRequireLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    pendingTag?.let { tag ->
        LaunchedEffect(tag) {
            if (com.zycomic.app.platform.StartupPlatformPolicy.checkSelection(context, ContentPlatform.MANWA)) {
                vm.applyPendingTag(tag)
            }
            onPendingTagConsumed()
        }
    }

    val mainTab by vm.mainTab.collectAsState()
    val platform by vm.currentPlatform.collectAsState()
    val tabs = vm.tabs
    val pagerState = rememberPagerState(
        initialPage = mainTab.coerceIn(0, tabs.lastIndex.coerceAtLeast(0)),
        pageCount = { tabs.size.coerceAtLeast(1) },
    )
    LaunchedEffect(platform, mainTab, tabs.size) {
        val target = mainTab.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    LaunchedEffect(Unit) { vm.initDefaultRankType(SpecialTabPrefs.getRankType(context)) }
    // 监听用户左右滑动切换页面，同步到 ViewModel 并触发请求
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .filter { it != vm.mainTab.value }
            .collect { page ->
                val targetItem = vm.tabs.getOrNull(page)
                if (targetItem != null && (targetItem.isUngatedYuzongRank() || com.zycomic.app.platform.StartupPlatformPolicy.checkSelection(context, targetItem.platformId))) {
                    vm.selectMainTab(page)
                } else {
                    pagerState.animateScrollToPage(vm.mainTab.value)
                }
            }
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
    androidx.activity.compose.BackHandler(enabled = selectionMode) { vm.exitSelection() }
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
                    titleContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppBarTitle("分类")
                            CategoryPlatformLegend()
                        }
                    },
                    searchEnabled = false,
                    searchQuery = null,
                    onChangeSearchQuery = {},
                    actions = {
                        AppBarActions(
                            if (platform == ContentPlatform.MANWA) {
                                persistentListOf(
                                    AppBar.Action(title = "搜索", icon = Icons.Default.Search, onClick = { onOpenSearch("", null) }),
                                    AppBar.Action(title = "刷新", icon = Icons.Default.Refresh, onClick = { vm.refresh() }),
                                    AppBar.Action(title = "使用说明", icon = Icons.Outlined.Info, onClick = { showGuideDialog = true }),
                                    AppBar.Action(title = "多选", icon = Icons.Outlined.Checklist, onClick = { vm.enterSelection() }),
                                )
                            } else {
                                persistentListOf(
                                    AppBar.Action(title = "搜索", icon = Icons.Default.Search, onClick = {
                                        onOpenSearch(vm.jmFiltersForTab(mainTab).value.query, null)
                                    }),
                                    AppBar.Action(title = "刷新", icon = Icons.Default.Refresh, onClick = { vm.refresh() }),
                                    AppBar.Action(title = "使用说明", icon = Icons.Outlined.Info, onClick = { showGuideDialog = true }),
                                )
                            },
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
            // 共用混合标签栏（选中 tab 后内嵌「重新筛选」）+ 平台颜色图例 + 管理
            val tabRowStateHolder = rememberSaveableStateHolder()
            tabRowStateHolder.SaveableStateProvider("category_tab_row_shared") {
                val tabRowState = rememberLazyListState()
                var recenterRequest by remember { mutableStateOf(0) }
                LaunchedEffect(pagerState.currentPage, tabs.size, recenterRequest) {
                    val target = pagerState.currentPage
                    val targetTab = tabs.getOrNull(target) ?: return@LaunchedEffect
                    val key = "tab_${targetTab.platformId.id}_${targetTab.id}"
                    if (tabRowState.layoutInfo.visibleItemsInfo.none { it.key == key }) {
                        tabRowState.animateScrollToItem(target)
                    }
                    androidx.compose.runtime.withFrameNanos { }
                    val layout = tabRowState.layoutInfo
                    val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
                    if (item != null) {
                        tabRowState.animateScrollBy(
                            BrowseTabCenteringPolicy.scrollDelta(
                                item.offset,
                                item.size,
                                layout.viewportStartOffset,
                                layout.viewportEndOffset,
                                tabRowState.canScrollBackward,
                                tabRowState.canScrollForward,
                            ),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(
                        state = tabRowState,
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, item ->
                            item(key = "tab_${item.platformId.id}_${item.id}") {
                                val selected = pagerState.currentPage == index
                                val selectTab: () -> Unit = {
                                    if (item.isUngatedYuzongRank() || com.zycomic.app.platform.StartupPlatformPolicy.checkSelection(context, item.platformId)) {
                                        if (selected) recenterRequest++
                                        scope.launch {
                                            pagerState.animateScrollToPage(index)
                                            vm.selectMainTab(index)
                                        }
                                    }
                                }
                                Box(
                                    Modifier.clip(CircleShape)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = selectTab,
                                        )
                                        .padding(end = 6.dp),
                                ) {
                                    BrowseTabCapsule(
                                        name = item.name,
                                        platform = item.platformId,
                                        isYuzongRank = item.isUngatedYuzongRank(),
                                        isJmHome = item.platformId == ContentPlatform.JM && item.id == BrowseTabStore.ID_JM_HOME,
                                        selected = selected,
                                    )
                                }
                            }
                            if (index == pagerState.currentPage) {
                                item(key = "filter_affordance") {
                                    TabBarIcon(
                                        icon = Icons.Outlined.FilterList,
                                        contentDescription = if (item.platformId == ContentPlatform.JM) "JM 筛选" else "重新筛选",
                                        tint = MaterialTheme.colorScheme.primary,
                                        onClick = { showFilterDialog = true },
                                    )
                                }
                            }
                        }
                    }
                    TabBarIcon(
                        icon = Icons.Outlined.Tune,
                        contentDescription = "管理",
                        onClick = { showManageSheet = true },
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Top,
                key = { page -> "${tabs.getOrNull(page)?.platformId?.id ?: platform.id}_${tabs.getOrNull(page)?.id ?: "empty"}" },
            ) { page ->
                BrowseTabContent(
                    page = page,
                    vm = vm,
                    platform = platform,
                    displayMode = displayMode,
                    gridColumns = gridColumns,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onOpenManga = onOpenManga,
                    onToggleSelect = { vm.toggleSelect(it) },
                    onLongClick = {
                        if (!selectionMode) {
                            vm.enterSelection()
                            vm.toggleSelect(it)
                        }
                    },
                    bottomPadding = contentPadding.calculateBottomPadding(),
                    onOpenSearch = { keyword, provider -> onOpenSearch(keyword, provider) },
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
            displayOnly = vm.tabs.getOrNull(mainTab)?.let {
                it.isUngatedYuzongRank() || (it.platformId == ContentPlatform.JM && it.id == BrowseTabStore.ID_JM_HOME)
            } ?: false,
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
                    if (ok) {
                        showFolderDialog = false
                        vm.exitSelection()
                    }
                }
            },
        )
    }
}

private fun BrowseTabItem.isUngatedYuzongRank(): Boolean =
    BrowseTabPolicy.isIndependentRanking(this)

@Composable
private fun CategoryPlatformLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
        PlatformLegendRow(color = rememberContentPlatformAccent(ContentPlatform.MANWA), label = "漫蛙")
        PlatformLegendRow(color = rememberContentPlatformAccent(ContentPlatform.JM), label = "禁漫天堂")
        PlatformLegendRow(color = MaterialTheme.colorScheme.primary, label = "宇总排行")
    }
}

@Composable
private fun PlatformLegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.width(10.dp).height(6.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrowseTabContent(
    page: Int,
    vm: BrowseViewModel,
    platform: ContentPlatform,
    displayMode: Int,
    gridColumns: Int,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onOpenManga: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onLongClick: (String) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenSearch: (String, SearchProvider?) -> Unit,
) {
    val item = vm.tabs.getOrNull(page)
    val tabPlatform = item?.platformId ?: platform
    if (item?.kind == TabKind.SPECIAL && item.pageType == BrowsePageType.YUZONG_RANK) {
        com.zycomic.app.platform.YuzongRankContent(
            onSearchTitle = { title -> onOpenSearch(title, null) },
            modifier = Modifier.fillMaxSize(),
            gridColumns = if (gridColumns > 0) gridColumns else 3,
            displayMode = displayMode,
        )
        return
    }
    val mangas by vm.mangasForTab(page).collectAsState()
    val loading by vm.loadingForTab(page).collectAsState()
    val appending by vm.appendingForTab(page).collectAsState()
    val hasMore by vm.hasMoreForTab(page).collectAsState()
    val error by vm.errorForTab(page).collectAsState()
    val jmFilters by vm.jmFiltersForTab(page).collectAsState()
    val isJmHome = item?.id == BrowseTabStore.ID_JM_HOME && tabPlatform == ContentPlatform.JM
    LaunchedEffect(isJmHome) {
        if (isJmHome) vm.loadJmPromoteSections()
    }

    // Pager 使用稳定的 Tab ID 作为 key，因此可保存的网格状态会随 Tab 排序保留在原 Tab。
    val gridState = rememberLazyGridState()
    val gridItems = remember(mangas) {
        val occurrences = mutableMapOf<String, Int>()
        mangas.map { manga ->
            val occurrence = occurrences.getOrDefault(manga.id, 0)
            occurrences[manga.id] = occurrence + 1
            manga to "manga-${manga.id}-$occurrence"
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(gridState.canScrollForward, mangas.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (page == vm.mainTab.value && mangas.isNotEmpty() && lastVisible >= mangas.size - 4 && hasMore && !loading) {
            vm.loadMore(page)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (item?.id == BrowseTabStore.ID_JM_HOME && tabPlatform == ContentPlatform.JM) {
                JmBrowseControls(vm, page)
            }
            if (isJmHome && jmFilters.mode == JmBrowsePolicy.MODE_RECOMMENDATIONS) {
                JmPromoteHomeContent(
                    vm = vm,
                    page = page,
                    onOpenManga = onOpenManga,
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isJmHome && jmFilters.mode == JmBrowsePolicy.MODE_UNAVAILABLE_SECTION -> Box(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${jmFilters.promoteTitle} 暂未接入对应内容 API；当前不会将其伪装为漫画列表。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                                    contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, if (displayMode == 2) 4.dp else bottomPadding + 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(gridItems, key = { it.second }) { (m, _) ->
                                        MangaGridItem(
                                            manga = m,
                                            isJm = tabPlatform == ContentPlatform.JM,
                                            displayMode = displayMode,
                                            selected = selectionMode && m.id in selectedIds,
                                            selectionMode = selectionMode,
                                            onClick = {
                                                if (selectionMode && tabPlatform == ContentPlatform.MANWA) {
                                                    onToggleSelect(m.id)
                                                } else {
                                                    onOpenManga(if (tabPlatform == ContentPlatform.JM) "jm:${m.id}" else m.id)
                                                }
                                            },
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
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JmPromoteHomeContent(
    vm: BrowseViewModel,
    page: Int,
    onOpenManga: (String) -> Unit,
) {
    val sections by vm.jmPromoteSections.collectAsState()
    val loading by vm.jmPromoteSectionsLoading.collectAsState()
    val error by vm.jmPromoteSectionsError.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
    ) {
        when {
            loading && sections.isEmpty() -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && sections.isEmpty() -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.loadJmPromoteSections(retry = true) }) { Text("重试") }
            }
            sections.isEmpty() -> Text("暂无首页板块", modifier = Modifier.padding(20.dp))
            else -> sections.filter { JmBrowsePolicy.isVisibleHomeSection(it.id, it.type, includeSerialization = true) }.forEach { section ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.openJmPromoteSection(section, page) }) { Text("更多") }
                }
                if (section.type == "library" || section.type == "novels") {
                    Text(
                        "该板块需要专用 ${if (section.type == "library") "creator_author/creator_work" else "/novels"} API 接入；不会将预览项伪装为漫画。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(section.items, key = { "${section.id}_${it.id}" }) { manga ->
                            Box(Modifier.width(118.dp)) {
                                MangaGridItem(
                                    manga = manga,
                                    isJm = true,
                                    displayMode = 2,
                                    selected = false,
                                    selectionMode = false,
                                    onClick = { onOpenManga("jm:${manga.id}") },
                                    onLongClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JmBrowseControls(vm: BrowseViewModel, page: Int) {
    val filters by vm.jmFiltersForTab(page).collectAsState()
    val tab = vm.tabs.getOrNull(page)
    val tabId = tab?.id.orEmpty()
    val isHome = tabId == BrowseTabStore.ID_JM_HOME
    val isAllHot = tabId == JmBrowsePolicy.ALL_HOT_TAB_ID
    val mode = if (isAllHot) JmBrowsePolicy.MODE_CATEGORY else filters.mode
    val categoryOptions = remember { JmBrowsePolicy.categorySelections(JmSource.categoryOptions) }
    val orderOptions = remember { JmBrowsePolicy.orderOptions(JmSource.orderOptions) }
    val weekIssues by vm.jmWeekIssues.collectAsState()
    val weekIssuesLoading by vm.jmWeekIssuesLoading.collectAsState()
    val weekIssuesError by vm.jmWeekIssuesError.collectAsState()

    LaunchedEffect(page, mode) {
        if (mode == JmBrowsePolicy.MODE_WEEK) vm.loadJmWeekIssues()
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (isHome) 0.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (isHome) 0.dp else 4.dp),
    ) {
        if (isHome) {
            val sections by vm.jmPromoteSections.collectAsState()
            val choices = buildList {
                add(JmHomeChoice("每周必看", mode == JmBrowsePolicy.MODE_WEEK) { vm.selectJmMode(JmBrowsePolicy.MODE_WEEK, page) })
                add(JmHomeChoice("每周连载更新", mode == JmBrowsePolicy.MODE_SERIALIZATION) { vm.selectJmMode(JmBrowsePolicy.MODE_SERIALIZATION, page) })
                sections.filter { JmBrowsePolicy.isVisibleHomeSection(it.id, it.type) }.forEach { section ->
                    add(
                        JmHomeChoice(
                            label = section.title,
                            selected = JmBrowsePolicy.isSelectedHomeSection(mode, filters.promoteId, section.id),
                            onClick = { vm.openJmPromoteSection(section, page) },
                        ),
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                choices.forEach { choice ->
                    key(choice.label) {
                        androidx.compose.material3.FilterChip(
                            selected = choice.selected,
                            onClick = choice.onClick,
                            label = { Text(choice.label) },
                        )
                    }
                }
            }
        } else if (JmBrowsePolicy.showsModePicker(tabId)) {
            FilterSection("浏览方式") {
                listOf(
                    "推荐" to JmBrowsePolicy.MODE_RECOMMENDATIONS,
                    "分类" to JmBrowsePolicy.MODE_CATEGORY,
                    "每周必看" to JmBrowsePolicy.MODE_WEEK,
                    "每周连载更新" to JmBrowsePolicy.MODE_SERIALIZATION,
                ).forEach { (label, value) ->
                    FilterChip(text = label, selected = mode == value, onClick = { vm.selectJmMode(value, page) })
                }
                if (mode == JmBrowsePolicy.MODE_SEARCH) Text("搜索：${filters.query}", style = MaterialTheme.typography.bodySmall)
            }
        }
        when (mode) {
            JmBrowsePolicy.MODE_CATEGORY -> if (!isHome || filters.promoteId.isBlank()) {
                FilterSection("分类") {
                    categoryOptions.forEach { (label, value) ->
                        FilterChip(text = label, selected = filters.category == value, onClick = { vm.updateJmFilters(page, category = value) })
                    }
                }
                FilterSection("排序方式") {
                    orderOptions.forEach { (label, value) ->
                        FilterChip(text = label, selected = filters.order == value, onClick = { vm.updateJmFilters(page, order = value) })
                    }
                }
            }
            JmBrowsePolicy.MODE_WEEK -> {
                if (weekIssuesError != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(weekIssuesError ?: "周榜期数加载失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.loadJmWeekIssues(retry = true) }) { Text("重试") }
                    }
                } else if (weekIssuesLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("加载周榜期数…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (weekIssues.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("暂无有效周榜期数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.loadJmWeekIssues(retry = true) }) { Text("重试") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JmBrowseSelector(
                            title = "期数",
                            selected = weekIssues.firstOrNull { it.id == filters.weekId }?.title
                                ?: if (filters.weekId.isBlank()) weekIssues.firstOrNull()?.title ?: "选择期数" else "选择期数",
                            options = weekIssues.map { it.title to it.id },
                            onSelected = { vm.updateJmFilters(page, weekId = it) },
                            modifier = Modifier.weight(2.3f),
                        )
                        JmBrowseSelector(
                            title = "类型",
                            selected = JmWeekTypes.firstOrNull { it.second == filters.weekType }?.first ?: "选择类型",
                            options = JmWeekTypes,
                            onSelected = { vm.updateJmFilters(page, weekType = it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            JmBrowsePolicy.MODE_SERIALIZATION -> {
                val serializationTypes = listOf("全部" to "all", "日漫" to "manga", "韩漫" to "hanman")
                val serializationDates = listOf("周一" to 1, "周二" to 2, "周三" to 3, "周四" to 4, "周五" to 5, "周六" to 6, "周日" to 7, "已完结" to 0)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    serializationTypes.forEach { (label, value) ->
                        JmChildCapsule(label, filters.serializationType == value) { vm.updateJmFilters(page, serializationType = value) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    serializationDates.forEach { (label, value) ->
                        JmChildCapsule(label, filters.serializationDate == value) { vm.updateJmFilters(page, serializationDate = value) }
                    }
                }
            }
            JmBrowsePolicy.MODE_PROMOTE_LIST, JmBrowsePolicy.MODE_UNAVAILABLE_SECTION -> Unit
            JmBrowsePolicy.MODE_SEARCH -> Text("搜索结果支持继续加载", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class JmHomeChoice(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

private val JmWeekTypes = listOf("日漫" to "manga", "韩漫" to "hanman", "其他" to "another")

@Composable
private fun JmChildCapsule(label: String, selected: Boolean, onClick: () -> Unit) {
    BrowseTabCapsule(
        name = label,
        platform = ContentPlatform.JM,
        selected = selected,
        horizontalPadding = 9.dp,
        verticalPadding = 4.dp,
        fontSize = 11.sp,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun JmBrowseSelector(
    title: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(title) { mutableStateOf(false) }
    Box(modifier = modifier) {
        BrowseTabCapsule(
            name = "$title：$selected",
            platform = ContentPlatform.JM,
            modifier = Modifier.fillMaxWidth().clickable(enabled = options.isNotEmpty()) { expanded = true },
            horizontalPadding = 8.dp,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun MangaGridItem(
    manga: Manga,
    isJm: Boolean,
    displayMode: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val imageLoader = if (isJm) {
        com.zycomic.app.ui.components.JmCoverImageLoader.get(androidx.compose.ui.platform.LocalContext.current)
    } else {
        androidx.compose.ui.platform.LocalContext.current.imageLoader
    }
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
            SubcomposeAsyncImage(
                model = com.zycomic.app.ui.components.coverUrl(manga.picx ?: manga.pic ?: ""),
                contentDescription = manga.name,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = { com.zycomic.app.ui.components.CoverError(Modifier.fillMaxSize()) },
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
    displayOnly: Boolean = false,
) {
    var showInfo by remember { mutableStateOf(false) }
    val titles = if (displayOnly) persistentListOf("显示") else persistentListOf("重新筛选", "显示")
    TabbedDialog(
        onDismissRequest = onDismiss,
        tabTitles = titles,
        tabHeaderAction = if (displayOnly) {
            null
        } else {
            {
                IconButton(onClick = { showInfo = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "筛选说明",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { page ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(TabbedDialogPaddings.Horizontal),
        ) {
            if (!displayOnly && page == 0) {
                val item = vm.tabs.getOrNull(currentTab)
                when {
                    item?.platformId == ContentPlatform.JM -> JmBrowseControls(vm, currentTab)
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
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("说明") },
            text = {
                Text(
                    if (vm.tabs.getOrNull(currentTab)?.platformId == ContentPlatform.JM) {
                        "重新筛选只对本次有效，不会修改默认值；切换标签页后本次设置仍保留，重启软件后恢复标签页默认值。"
                    } else {
                        "重新筛选只对本次有效，不会修改默认值；切换标签页后本次设置仍保留，重启软件后恢复标签页默认值。若要修改标签页默认筛选，请使用标签页管理。"
                    },
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("确认") } },
        )
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
        onConfirm = { set ->
            vm.setSelectedTags(set)
            onDismiss()
        },
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

@Composable
private fun TabBarIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}
