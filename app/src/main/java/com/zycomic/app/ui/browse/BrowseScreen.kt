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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.runtime.withFrameNanos
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
import com.zycomic.app.ui.components.AppBackdropState
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.JmFavoriteFolder
import com.zycomic.app.data.repository.JmOnlineLibraryRepository
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.rememberContentPlatformAccent
import com.zycomic.app.reader.JmSource
import com.zycomic.app.ui.components.BrowseScreenSessionPolicy
import com.zycomic.app.ui.components.DisplaySettingsSection
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.FilterSection
import com.zycomic.app.ui.components.JmLibrarySelectionPolicy
import com.zycomic.app.ui.components.MangaGridSkeleton
import com.zycomic.app.ui.picacg.PicacgRankingMode
import com.zycomic.app.ui.picacg.toFeedMode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun BrowseScreen(
    vm: BrowseViewModel,
    pendingTag: String?,
    onPendingTagConsumed: () -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenSearch: (String, SearchProvider?) -> Unit,
    onRequireLogin: () -> Unit,
) {
    // KMK --> Cancel delayed Manwa retries when this page leaves composition.
    DisposableEffect(vm) {
        vm.onBrowseVisible()
        onDispose { vm.onBrowseHidden() }
    }
    // KMK <--
    LegacyBrowseScreen(vm, pendingTag, onPendingTagConsumed, onOpenManga, onOpenSearch, onRequireLogin)
}

@Composable
private fun LegacyBrowseScreen(
    vm: BrowseViewModel,
    pendingTag: String?,
    onPendingTagConsumed: () -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenSearch: (String, SearchProvider?) -> Unit,
    onRequireLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val platform by vm.currentPlatform.collectAsState()

    pendingTag?.let { tag ->
        LaunchedEffect(tag) {
            if (com.zycomic.app.platform.StartupPlatformPolicy.checkSelection(context, ContentPlatform.MANWA)) {
                PendingTagPolicy.applyAndConsume(tag, vm::applyPendingTag, onPendingTagConsumed)
            }
        }
    }

    val mainTab by vm.mainTab.collectAsState()
    val tabs = vm.tabs
    var programmaticTabSelection by remember { mutableStateOf<Int?>(null) }
    val pagerState = rememberPagerState(
        initialPage = mainTab.coerceIn(0, tabs.lastIndex.coerceAtLeast(0)),
        pageCount = { tabs.size.coerceAtLeast(1) },
    )
    LaunchedEffect(platform, mainTab, tabs.size, programmaticTabSelection) {
        val target = (programmaticTabSelection ?: mainTab).coerceIn(0, tabs.lastIndex.coerceAtLeast(0))
        if (pagerState.currentPage != target || pagerState.settledPage != target) {
            if (programmaticTabSelection == target) {
                // 新增 tab 时 tabs.size 先变化、pager 的 pageCount 后生效；直接 scrollToPage
                // 会被旧上限截断到前一个 tab，表现成“新增后停在同一平台的旧标签页”。
                // 先等 pageCount 覆盖目标页，再落位并补一次校验。
                snapshotFlow { pagerState.pageCount }.first { it > target }
                pagerState.scrollToPage(target)
                withFrameNanos { }
                if (pagerState.settledPage != target) pagerState.scrollToPage(target)
            } else {
                pagerState.animateScrollToPage(target)
            }
        }
    }
    LaunchedEffect(Unit) { vm.initDefaultRankType(SpecialTabPrefs.getRankType(context)) }
    // 监听用户左右滑动切换页面，同步到 ViewModel 并触发请求
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (programmaticTabSelection != null) {
                    if (page == programmaticTabSelection) programmaticTabSelection = null
                    return@collect
                }
                if (page == vm.mainTab.value) return@collect
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
    val jmSessionRevision by JmAuthStore.sessionRevisionFlow.collectAsState()
    var jmSelectionMode by remember { mutableStateOf(false) }
    var jmSelectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var jmFolders by remember { mutableStateOf<List<JmFavoriteFolder>>(emptyList()) }
    var jmPendingFolderIds by remember { mutableStateOf<Set<String>?>(null) }
    var jmFavoriteBusy by remember { mutableStateOf(false) }
    var jmFavoriteError by remember { mutableStateOf<String?>(null) }
    var jmSelectionGeneration by remember { mutableStateOf(0L) }
    var jmFavoriteOperationGeneration by remember { mutableStateOf(0L) }
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    androidx.compose.runtime.LaunchedEffect(orientation) { vm.loadGridColumnsForOrientation(orientation) }

    var showFilterDialog by remember { mutableStateOf(false) }
    var picacgFilterDialogRequest by remember { mutableStateOf(0) }
    LaunchedEffect(mainTab) { picacgFilterDialogRequest = 0 }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showManageSheet by remember { mutableStateOf(false) }
    var showJmAlbumJumpDialog by remember { mutableStateOf(false) }
    val jmCategoryActive = platform == ContentPlatform.JM
    val activeSelectionMode = if (jmCategoryActive) jmSelectionMode else selectionMode
    val activeSelectionCount = if (jmCategoryActive) jmSelectedIds.size else selectedIds.size

    fun jmTabKey(index: Int): String? =
        vm.tabs.getOrNull(index)
            ?.takeIf { it.platformId == ContentPlatform.JM }
            ?.let { "${it.platformId.id}_${it.id}" }

    fun isCurrentJmContext(startedRevision: Long, startedTabKey: String?): Boolean {
        val currentTabKey = jmTabKey(vm.mainTab.value)
        return BrowseScreenSessionPolicy.acceptsResult(
            startedRevision = startedRevision,
            currentRevision = JmAuthStore.sessionRevision,
            startedTabKey = startedTabKey,
            currentTabKey = currentTabKey,
            startedOnJm = startedTabKey != null,
            currentlyOnJm = vm.currentPlatform.value == ContentPlatform.JM && currentTabKey != null,
        )
    }

    androidx.activity.compose.BackHandler(enabled = activeSelectionMode) {
        if (jmCategoryActive) {
            if (isCurrentJmContext(jmSessionRevision, jmTabKey(mainTab))) {
                jmSelectionGeneration++
                jmSelectionMode = false
                jmSelectedIds = emptySet()
            }
        } else {
            vm.exitSelection()
        }
    }
    androidx.activity.compose.BackHandler(enabled = showFilterDialog) { showFilterDialog = false }
    androidx.activity.compose.BackHandler(enabled = showFolderDialog) { showFolderDialog = false }
    androidx.activity.compose.BackHandler(enabled = showTagDialog) { showTagDialog = false }
    androidx.activity.compose.BackHandler(enabled = showManageSheet) { showManageSheet = false }

    LaunchedEffect(mainTab, platform, jmSessionRevision) {
        jmSelectionGeneration++
        jmFavoriteOperationGeneration++
        jmSelectionMode = false
        jmSelectedIds = emptySet()
        jmFolders = emptyList()
        jmPendingFolderIds = null
        jmFavoriteBusy = false
        jmFavoriteError = null
        showJmAlbumJumpDialog = false
    }

    fun exitJmSelection(
        startedRevision: Long = jmSessionRevision,
        startedTabKey: String? = jmTabKey(mainTab),
    ) {
        if (!isCurrentJmContext(startedRevision, startedTabKey)) return
        jmSelectionGeneration++
        jmSelectionMode = false
        jmSelectedIds = emptySet()
    }

    fun openJmFolderPicker(ids: Set<String>) {
        val startedRevision = jmSessionRevision
        val startedTabKey = jmTabKey(mainTab)
        if (ids.isEmpty() || jmFavoriteBusy || jmSelectedIds != ids || !isCurrentJmContext(startedRevision, startedTabKey)) return
        val selectionGeneration = jmSelectionGeneration
        val operationGeneration = ++jmFavoriteOperationGeneration
        jmFavoriteBusy = true
        jmFavoriteError = null
        scope.launch {
            try {
                // Read folders from JM's /favorite response; never use Manwa's folder repository.
                val loadedFolders = JmOnlineLibraryRepository.favorites(1, "mr").folders
                if (isCurrentJmContext(startedRevision, startedTabKey) &&
                    operationGeneration == jmFavoriteOperationGeneration &&
                    selectionGeneration == jmSelectionGeneration && jmSelectedIds == ids
                ) {
                    jmFolders = loadedFolders
                    jmPendingFolderIds = ids
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (isCurrentJmContext(startedRevision, startedTabKey) &&
                    operationGeneration == jmFavoriteOperationGeneration && selectionGeneration == jmSelectionGeneration
                ) {
                    jmFavoriteError = failure.message ?: "JM 收藏夹加载失败"
                }
            } finally {
                if (operationGeneration == jmFavoriteOperationGeneration && JmAuthStore.sessionRevision == startedRevision) {
                    jmFavoriteBusy = false
                }
            }
        }
    }

    fun addJmFavorites(ids: Set<String> = jmSelectedIds, folder: JmFavoriteFolder? = null) {
        val startedRevision = jmSessionRevision
        val startedTabKey = jmTabKey(mainTab)
        if (ids.isEmpty() || ids != jmSelectedIds || jmFavoriteBusy || !isCurrentJmContext(startedRevision, startedTabKey)) return
        val selectionGeneration = jmSelectionGeneration
        val operationGeneration = ++jmFavoriteOperationGeneration
        jmFavoriteBusy = true
        jmFavoriteError = null
        scope.launch {
            try {
                JmOnlineLibraryRepository.ensureFavorites(ids, folder)
                if (isCurrentJmContext(startedRevision, startedTabKey) &&
                    operationGeneration == jmFavoriteOperationGeneration &&
                    selectionGeneration == jmSelectionGeneration && jmSelectedIds == ids
                ) {
                    exitJmSelection()
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (isCurrentJmContext(startedRevision, startedTabKey) &&
                    operationGeneration == jmFavoriteOperationGeneration && selectionGeneration == jmSelectionGeneration
                ) {
                    jmFavoriteError = "JM 收藏可能部分成功，请刷新 JM 收藏列表核对后再试：${failure.message ?: "操作失败"}"
                }
            } finally {
                if (operationGeneration == jmFavoriteOperationGeneration && JmAuthStore.sessionRevision == startedRevision) {
                    jmFavoriteBusy = false
                }
            }
        }
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode) vm.loadFolders()
    }

    Scaffold(
        topBar = {
            if (activeSelectionMode) {
                AppBar(
                    titleContent = { Text(activeSelectionCount.toString()) },
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(title = "全选", icon = Icons.Outlined.SelectAll, onClick = {
                                    if (jmCategoryActive) {
                                        if (isCurrentJmContext(jmSessionRevision, jmTabKey(mainTab))) {
                                            jmSelectionGeneration++
                                            jmSelectedIds = JmLibrarySelectionPolicy.selectAll(vm.mangasForTab(mainTab).value.map { it.id })
                                        }
                                    } else {
                                        vm.selectAll()
                                    }
                                }),
                                AppBar.Action(title = "反选", icon = Icons.Outlined.FlipToBack, onClick = {
                                    if (jmCategoryActive) {
                                        if (isCurrentJmContext(jmSessionRevision, jmTabKey(mainTab))) {
                                            jmSelectionGeneration++
                                            jmSelectedIds = JmLibrarySelectionPolicy.invertLoaded(jmSelectedIds, vm.mangasForTab(mainTab).value.map { it.id })
                                        }
                                    } else {
                                        vm.invertSelection()
                                    }
                                }),
                            ),
                        )
                    },
                    isActionMode = true,
                    onCancelActionMode = { if (jmCategoryActive) exitJmSelection() else vm.exitSelection() },
                )
            } else {
                SearchToolbar(
                    titleContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CategoryPlatformLegend()
                        }
                    },
                    searchEnabled = false,
                    searchQuery = null,
                    onChangeSearchQuery = {},
                    actions = {
                        // Keep the compact direct-ID entry immediately beside search.
                        FilledTonalButton(
                            onClick = {
                                if (com.zycomic.app.platform.StartupPlatformPolicy.checkSelection(context, ContentPlatform.JM)) {
                                    showJmAlbumJumpDialog = true
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(stringResource(KMR.strings.jm_album_jump_button), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        AppBarActions(
                            if (platform == ContentPlatform.MANWA) {
                                persistentListOf(
                                    AppBar.Action(title = "搜索", icon = Icons.Default.Search, onClick = { onOpenSearch("", null) }),
                                    AppBar.Action(title = "刷新", icon = Icons.Outlined.Refresh, onClick = { vm.refresh() }),
                                    AppBar.Action(title = "多选", icon = Icons.Outlined.SelectAll, onClick = { vm.enterSelection() }),
                                )
                            } else {
                                persistentListOf(
                                    AppBar.Action(title = "搜索", icon = Icons.Default.Search, onClick = {
                                        onOpenSearch(vm.jmFiltersForTab(mainTab).value.query, null)
                                    }),
                                    AppBar.Action(title = "刷新", icon = Icons.Outlined.Refresh, onClick = { vm.refresh() }),
                                    AppBar.Action(title = "多选", icon = Icons.Outlined.SelectAll, onClick = {
                                        if (isCurrentJmContext(jmSessionRevision, jmTabKey(mainTab))) {
                                            jmSelectionGeneration++
                                            jmSelectedIds = emptySet()
                                            jmSelectionMode = true
                                        }
                                    }),
                                )
                            },
                        )
                    },
                )
            }
        },
        bottomBar = {
            if (activeSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !jmFavoriteBusy) {
                                if (jmCategoryActive) {
                                    addJmFavorites()
                                } else {
                                    vm.addToAllFolders { ok, _ ->
                                        if (ok) vm.exitSelection()
                                    }
                                }
                            }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (jmCategoryActive) "添加到收藏" else "添加到全部收藏夹", color = MaterialTheme.colorScheme.primary) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !jmFavoriteBusy) {
                                if (jmCategoryActive) openJmFolderPicker(jmSelectedIds) else showFolderDialog = true
                            }
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
            jmFavoriteError?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { jmFavoriteError = null }) { Text("关闭") }
                }
            }
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
                            if (index == pagerState.currentPage && (item.platformId != ContentPlatform.PICACG || item.isPicacgRanking() || item.isPicacgCategory() || (item.kind == TabKind.FILTER && !item.builtin))) {
                                item(key = "filter_affordance") {
                                    TabBarIcon(
                                        icon = Icons.Outlined.FilterList,
                                        contentDescription = when {
                                            item.platformId == ContentPlatform.JM -> "JM 筛选"
                                            item.isPicacgRanking() -> "显示"
                                            else -> "重新筛选"
                                        },
                                        tint = MaterialTheme.colorScheme.primary,
                                        onClick = {
                                            if (item.platformId == ContentPlatform.PICACG && !item.isPicacgRanking()) {
                                                picacgFilterDialogRequest++
                                            } else {
                                                showFilterDialog = true
                                            }
                                        },
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
                    isActive = page == mainTab,
                    vm = vm,
                    platform = platform,
                    displayMode = displayMode,
                    gridColumns = gridColumns,
                    selectionMode = if (page == mainTab && vm.tabs.getOrNull(page)?.platformId == ContentPlatform.JM) jmSelectionMode else selectionMode,
                    selectedIds = if (page == mainTab && vm.tabs.getOrNull(page)?.platformId == ContentPlatform.JM) jmSelectedIds else selectedIds,
                    onOpenManga = onOpenManga,
                    onToggleSelect = { id ->
                        if (page == mainTab && isCurrentJmContext(jmSessionRevision, jmTabKey(page))) {
                            jmSelectionGeneration++
                            jmSelectedIds = JmLibrarySelectionPolicy.toggle(jmSelectedIds, id)
                        } else {
                            vm.toggleSelect(id)
                        }
                    },
                    onLongClick = { id ->
                        if (page == mainTab && isCurrentJmContext(jmSessionRevision, jmTabKey(page))) {
                            if (!jmSelectionMode) {
                                jmSelectionGeneration++
                                jmSelectionMode = true
                                jmSelectedIds = emptySet()
                            }
                            jmSelectionGeneration++
                            jmSelectedIds = JmLibrarySelectionPolicy.toggle(jmSelectedIds, id)
                        } else if (!selectionMode) {
                            vm.enterSelection()
                            vm.toggleSelect(id)
                        }
                    },
                    bottomPadding = contentPadding.calculateBottomPadding(),
                    onOpenSearch = { keyword, provider -> onOpenSearch(keyword, provider) },
                    onRequireLogin = onRequireLogin,
                    picacgFilterDialogRequest = picacgFilterDialogRequest,
                )
            }
        }
    }

    if (showJmAlbumJumpDialog) {
        JmAlbumJumpDialog(
            onDismiss = { showJmAlbumJumpDialog = false },
            onOpenManga = onOpenManga,
        )
    }

    // Tab 管理面板
    if (showManageSheet) {
        TabManageSheet(
            vm = vm,
            onDismiss = { showManageSheet = false },
            onTabAdded = { index ->
                programmaticTabSelection = index.takeUnless { it == pagerState.settledPage }
            },
        )
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
                it.isUngatedYuzongRank() || (it.platformId == ContentPlatform.JM && it.id == BrowseTabStore.ID_JM_HOME) ||
                    it.isPicacgRanking()
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
    if (showFolderDialog && !jmCategoryActive) {
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

    val pendingJmFolderIds = jmPendingFolderIds
    if (pendingJmFolderIds != null && isCurrentJmContext(jmSessionRevision, jmTabKey(mainTab))) {
        AlertDialog(
            onDismissRequest = { jmPendingFolderIds = null },
            title = { Text("添加到 JM 收藏夹") },
            text = {
                Column {
                    if (jmFavoriteBusy) {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(24.dp))
                    } else if (jmFolders.isEmpty()) {
                        Text("暂无 JM 收藏夹；可先在 JM 收藏页的筛选 → 管理中添加")
                    } else {
                        jmFolders.forEach { folder ->
                            TextButton(
                                enabled = !jmFavoriteBusy,
                                onClick = {
                                    jmPendingFolderIds = null
                                    addJmFavorites(pendingJmFolderIds, folder)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(folder.name) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { jmPendingFolderIds = null }) { Text("取消") } },
        )
    }
}

private fun BrowseTabItem.isUngatedYuzongRank(): Boolean =
    BrowseTabPolicy.isIndependentRanking(this)

@Composable
private fun CategoryPlatformLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
        PlatformLegendRow(color = rememberContentPlatformAccent(ContentPlatform.MANWA), label = stringResource(KMR.strings.content_platform_manwa))
        PlatformLegendRow(color = rememberContentPlatformAccent(ContentPlatform.PICACG), label = stringResource(KMR.strings.content_platform_picacg))
        PlatformLegendRow(color = rememberContentPlatformAccent(ContentPlatform.JM), label = stringResource(KMR.strings.content_platform_jm))
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
    isActive: Boolean,
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
    onRequireLogin: () -> Unit,
    picacgFilterDialogRequest: Int,
) {
    val item = vm.tabs.getOrNull(page)
    val tabPlatform = item?.platformId ?: platform
    val picacgOrientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    if (tabPlatform == ContentPlatform.PICACG) {
        val pageMode = if (item?.isPicacgRanking() != false) {
            com.zycomic.app.ui.picacg.PicacgBrowsePageMode.RANKING
        } else {
            com.zycomic.app.ui.picacg.PicacgBrowsePageMode.CATEGORY
        }
        if (isActive) {
            com.zycomic.app.ui.picacg.PicacgBrowseScreen(
                onOpenManga = onOpenManga,
                onRequireLogin = onRequireLogin,
                tabId = item?.id.orEmpty(),
                initialFilter = item?.picacgFilter,
                initialRankingMode = item?.picacgRankingMode ?: PicacgRankingMode.H24,
                pageMode = pageMode,
                displayMode = displayMode,
                gridColumns = gridColumns,
                orientation = picacgOrientation,
                filterDialogRequest = picacgFilterDialogRequest,
                onSetDisplayMode = vm::setDisplayMode,
                onSetGridColumns = { vm.setGridColumns(it, picacgOrientation) },
            )
        } else if (pageMode == com.zycomic.app.ui.picacg.PicacgBrowsePageMode.RANKING) {
            // Compose only the static header during the swipe; keep data effects in the selected page.
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                com.zycomic.app.ui.picacg.PicacgRankingChips(selectedMode = (item?.picacgRankingMode ?: PicacgRankingMode.H24).toFeedMode())
            }
        } else {
            Box(Modifier.fillMaxSize())
        }
        return
    }
    if (item?.kind == TabKind.SPECIAL && item.pageType == BrowsePageType.YUZONG_RANK) {
        com.zycomic.app.platform.YuzongRankContent(
            onSearchTitle = { title -> onOpenSearch(title, null) },
            modifier = Modifier.fillMaxSize(),
            gridColumns = gridColumns,
            displayMode = displayMode,
        )
        return
    }
    val mangas by vm.mangasForTab(page).collectAsState()
    // 把这一页的封面写进本 tab 自己的候选缓存；背景从所有开启背景开关的 tab 的缓存里随机取。
    LaunchedEffect(page, mangas) {
        AppBackdropState.registerCovers(item?.id ?: page.toString(), mangas.mapNotNull { com.zycomic.app.ui.components.coverUrl(it).takeIf(String::isNotBlank) })
    }
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
                                columns = if (displayMode == 3) {
                                    GridCells.Fixed(1)
                                } else if (gridColumns == 0) {
                                    GridCells.Adaptive(128.dp)
                                } else {
                                    GridCells.Fixed(gridColumns)
                                },
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
                                            if (selectionMode && (tabPlatform == ContentPlatform.MANWA || tabPlatform == ContentPlatform.JM)) {
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
                JmBrowsePolicy.jmHomeModes().forEach { (label, value) ->
                    add(JmHomeChoice(label, mode == value) { vm.selectJmMode(value, page) })
                }
                JmBrowsePolicy.homeSectionOptions(sections).forEach { section ->
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                choices.forEach { choice ->
                    key(choice.label) {
                        CompactBrowseFilterChip(
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
                if (JmBrowsePolicy.showsCategoryPicker(tabId)) {
                    FilterSection("分类") {
                        categoryOptions.forEach { (label, value) ->
                            FilterChip(text = label, selected = filters.category == value, onClick = { vm.updateJmFilters(page, category = value) })
                        }
                    }
                }
                FilterSection("排序方式") {
                    orderOptions.forEach { (label, value) ->
                        FilterChip(text = label, selected = filters.order == value, onClick = { vm.updateJmFilters(page, order = value) })
                    }
                }
            }
            JmBrowsePolicy.MODE_WEEK -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JmBrowseSelector(
                        title = "期数",
                        selected = weekIssues.firstOrNull { it.id == filters.weekId }?.title
                            ?: weekIssues.firstOrNull()?.title
                            ?: when {
                                weekIssuesLoading -> "加载中…"
                                weekIssuesError != null -> "加载失败"
                                else -> "选择期数"
                            },
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
    if (displayMode == 3) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) else Modifier)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SubcomposeAsyncImage(
                model = com.zycomic.app.ui.components.coverUrl(manga),
                contentDescription = manga.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(68.dp).aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(manga.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(manga.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
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
        modes = listOf(0 to "紧凑网格", 1 to "舒适网格", 2 to "仅封面网格", 3 to "列表"),
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
