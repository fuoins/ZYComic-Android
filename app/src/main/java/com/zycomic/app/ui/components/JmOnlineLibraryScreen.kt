package com.zycomic.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.zycomic.app.data.repository.JmFavoriteFolder
import com.zycomic.app.data.repository.JmHistoryDisplayPolicy
import com.zycomic.app.data.repository.JmOnlineLibraryCache
import com.zycomic.app.data.repository.JmOnlineLibraryCacheEntry
import com.zycomic.app.data.repository.JmOnlineLibraryCacheKey
import com.zycomic.app.data.repository.JmOnlineLibraryCachePolicy
import com.zycomic.app.data.repository.JmOnlineLibraryContentState
import com.zycomic.app.data.repository.JmOnlineLibraryItem
import com.zycomic.app.data.repository.JmOnlineLibraryListType
import com.zycomic.app.data.repository.JmOnlineLibraryPolicy
import com.zycomic.app.data.repository.JmOnlineLibraryRepository
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmRouteManager
import com.zycomic.app.platform.ContentPlatform
import eu.kanade.presentation.components.TabbedDialog
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.foundation.lazy.grid.items as gridItems

/** JM controls and requests live in the same account-keyed composition, separate from Manwa state. */
@Composable
fun JmOnlineLibraryScreen(
    history: Boolean,
    onRequireLogin: () -> Unit,
    onOpenManga: (String) -> Unit,
    onSelectPlatform: (ContentPlatform) -> Unit,
) {
    val loggedIn by JmAuthStore.loggedInFlow.collectAsState()
    val accountName by JmAuthStore.accountNameFlow.collectAsState()
    val sessionRevision by JmAuthStore.sessionRevisionFlow.collectAsState()
    key(loggedIn, accountName, sessionRevision, history) {
        JmOnlineLibraryContent(
            history,
            loggedIn,
            accountName.orEmpty(),
            sessionRevision,
            onRequireLogin,
            onOpenManga,
            onSelectPlatform,
        )
    }
}

@Composable
private fun JmOnlineLibraryContent(
    history: Boolean,
    loggedIn: Boolean,
    identity: String,
    sessionRevision: Long,
    onRequireLogin: () -> Unit,
    onOpenManga: (String) -> Unit,
    onSelectPlatform: (ContentPlatform) -> Unit,
) {
    var order by remember { mutableStateOf(JmFavoriteLibraryUiPolicy.DEFAULT_SORT_ORDER) }
    var folderId by remember { mutableStateOf("") }
    val cacheKey = JmOnlineLibraryCacheKey(
        sessionRevision = sessionRevision,
        identity = identity,
        listType = if (history) JmOnlineLibraryListType.HISTORY else JmOnlineLibraryListType.FAVORITES,
        folderId = folderId,
        sortOrder = order,
    )
    val currentCacheKey by rememberUpdatedState(cacheKey)
    val cachedEntry = remember(cacheKey, loggedIn) {
        if (loggedIn) JmOnlineLibraryCache.get(cacheKey) else null
    }
    var folders by remember(cacheKey) { mutableStateOf(cachedEntry?.folders.orEmpty()) }
    var items by remember(cacheKey) { mutableStateOf(cachedEntry?.items.orEmpty()) }
    var total by remember(cacheKey) { mutableStateOf(cachedEntry?.total) }
    var page by remember(cacheKey) { mutableIntStateOf(cachedEntry?.page ?: 0) }
    var historyLoadedIds by remember(cacheKey) { mutableStateOf(cachedEntry?.historyLoadedIds.orEmpty()) }
    var loadState by remember(cacheKey, loggedIn) {
        mutableStateOf(JmOnlineLibraryLoadState(cachedEntry, loading = loggedIn))
    }
    var hasMore by remember(cacheKey) { mutableStateOf(cachedEntry?.hasMore ?: true) }
    val loading = loadState.loading
    val error = loadState.error
    var operationError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refreshRequest by remember { mutableIntStateOf(0) }
    var loadGeneration by remember { mutableIntStateOf(0) }
    var pageRequestInFlight by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val context = LocalContext.current
    val jmDisplayPreferences = remember(context) {
        context.getSharedPreferences("zycomic_display", android.content.Context.MODE_PRIVATE)
    }
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val gridColumnsPreferenceKey = JmFavoriteLibraryUiPolicy.gridColumnsPreferenceKey(isLandscape)
    var configuredGridColumns by remember(jmDisplayPreferences, gridColumnsPreferenceKey) {
        mutableIntStateOf(
            JmFavoriteLibraryUiPolicy.normalizeGridColumns(
                jmDisplayPreferences.getInt(gridColumnsPreferenceKey, 0),
            ),
        )
    }
    var displayMode by remember(jmDisplayPreferences) {
        mutableIntStateOf(
            JmFavoriteLibraryUiPolicy.normalizeDisplayMode(
                jmDisplayPreferences.getInt(
                    JmFavoriteLibraryUiPolicy.DISPLAY_MODE_PREFERENCE_KEY,
                    JmFavoriteLibraryUiPolicy.DEFAULT_DISPLAY_MODE,
                ),
            ),
        )
    }
    val grid = JmFavoriteLibraryUiPolicy.isGrid(displayMode)
    var showFilter by remember { mutableStateOf(false) }
    var moveIds by remember { mutableStateOf<Set<String>?>(null) }
    var favoriteIds by remember { mutableStateOf<Set<String>?>(null) }
    var removeIds by remember { mutableStateOf<Set<String>?>(null) }
    var editFolder by remember { mutableStateOf<JmFavoriteFolder?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf<JmFavoriteFolder?>(null) }
    var folderName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var listLayoutToken by remember { mutableStateOf<JmLibraryLayoutToken?>(null) }
    val activeGrid = grid && !history
    val visibleItems = remember(items, query) {
        items.filter { JmFavoriteLibraryUiPolicy.matchesLocalSearch(query.orEmpty(), it.name, it.author) }
    }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }
    fun refresh() {
        if (!busy) {
            exitSelection()
            refreshRequest++
        }
    }
    fun toggleSelection(id: String) {
        if (!busy) selected = JmLibrarySelectionPolicy.toggle(selected, id)
    }
    fun updateDisplayMode(mode: Int) {
        displayMode = JmFavoriteLibraryUiPolicy.normalizeDisplayMode(mode)
        jmDisplayPreferences.edit().putInt(JmFavoriteLibraryUiPolicy.DISPLAY_MODE_PREFERENCE_KEY, displayMode).apply()
    }
    fun updateGridColumns(columns: Int) {
        configuredGridColumns = JmFavoriteLibraryUiPolicy.normalizeGridColumns(columns)
        jmDisplayPreferences.edit().putInt(gridColumnsPreferenceKey, configuredGridColumns).apply()
    }
    fun mutate(action: suspend () -> Unit) {
        if (busy || !loggedIn) return
        busy = true
        showFilter = false
        operationError = null
        scope.launch {
            try {
                action()
                exitSelection()
                refreshRequest++
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                operationError = failure.message ?: "JM 操作失败，请刷新列表核对后再试"
                // A batch can partially succeed. Reload before allowing another toggle.
                exitSelection()
                refreshRequest++
            } finally {
                busy = false
            }
        }
    }
    fun openFolderPicker(ids: Set<String>, favorite: Boolean) {
        if (busy || ids.isEmpty() || !loggedIn) return
        busy = true
        operationError = null
        scope.launch {
            try {
                // folder_list is returned by /favorite, never by a made-up folder-list endpoint.
                folders = JmOnlineLibraryRepository.favorites(1, "mr").folders
                if (favorite) favoriteIds = ids else moveIds = ids
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                operationError = failure.message ?: "JM 收藏夹加载失败"
            } finally {
                busy = false
            }
        }
    }
    suspend fun loadPage(generation: Int, requestKey: JmOnlineLibraryCacheKey, replaceFirstPage: Boolean = false) {
        if (
            pageRequestInFlight ||
            !JmOnlineLibraryPolicy.shouldRequestPage(
                loggedIn,
                hasMore || replaceFirstPage,
                generation == loadGeneration,
            )
        ) {
            return
        }
        pageRequestInFlight = true
        loadState = loadState.beginLoad()
        try {
            if (history) {
                val result = JmHistoryDisplayPolicy.loadVisiblePage(
                    if (replaceFirstPage) 0 else page,
                    if (replaceFirstPage) emptySet() else historyLoadedIds,
                ) { requestedPage ->
                    JmOnlineLibraryRepository.history(requestedPage)
                }
                if (generation != loadGeneration || requestKey != currentCacheKey) return
                items = if (replaceFirstPage) {
                    result.items
                } else {
                    JmOnlineLibraryCachePolicy.appendPage(items, result.items)
                }
                historyLoadedIds = result.loadedIds
                hasMore = result.hasMore
                total = result.total
                page = result.lastPage
                val entry = JmOnlineLibraryCacheEntry(
                    items,
                    result.total,
                    emptyList(),
                    result.lastPage,
                    result.hasMore,
                    result.loadedIds,
                )
                JmOnlineLibraryCache.put(requestKey, entry)
                loadState = loadState.complete(entry)
            } else {
                val requestedPage = if (replaceFirstPage) 1 else page + 1
                val result = JmOnlineLibraryRepository.favorites(
                    requestedPage,
                    requestKey.sortOrder,
                    requestKey.folderId,
                )
                if (generation != loadGeneration || requestKey != currentCacheKey) return
                val nextItems = if (replaceFirstPage) {
                    JmOnlineLibraryCachePolicy.replaceFirstPage(result)
                } else {
                    JmOnlineLibraryCachePolicy.appendPage(items, result.items)
                }
                val existingIds = if (replaceFirstPage) emptyList() else items.map { it.id }
                hasMore = JmOnlineLibraryPolicy.hasMore(result.total, existingIds, result.items.map { it.id })
                items = nextItems
                folders = result.folders
                total = result.total
                page = requestedPage
                val entry = JmOnlineLibraryCacheEntry(nextItems, result.total, result.folders, requestedPage, hasMore)
                JmOnlineLibraryCache.put(requestKey, entry)
                loadState = loadState.complete(entry)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (generation != loadGeneration || requestKey != currentCacheKey) return
            loadState = loadState.fail(
                if (!JmAuthStore.isLoggedIn) "请重新登录 JM 账号" else failure.message ?: "JM 列表加载失败",
            )
        } finally {
            if (generation == loadGeneration && requestKey == currentCacheKey) loadState = loadState.stopLoading()
            if (generation == loadGeneration) pageRequestInFlight = false
        }
    }

    // Favorites and online history hydrate from process memory then revalidate from their remote endpoints.
    LaunchedEffect(cacheKey, refreshRequest, loggedIn) {
        val generation = ++loadGeneration
        pageRequestInFlight = false
        JmOnlineLibraryCache.activateSession(sessionRevision, identity, loggedIn)
        if (!loggedIn) return@LaunchedEffect
        // A cached page remains visible while this request replaces its first page in the background.
        loadPage(generation, cacheKey, replaceFirstPage = true)
        snapshotFlow {
            val expectedLayout = JmLibraryLayoutToken(generation, grid && !history)
            val last = if (listLayoutToken != expectedLayout) {
                -1
            } else if (grid && !history) {
                gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            } else {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            }
            Triple(last, visibleItems.size, loadState.error == null && !busy && !loadState.loading)
        }.collect { (last, size, healthy) ->
            if (healthy && query.isNullOrBlank() && size > 0 && last >= (size - 3).coerceAtLeast(0) && hasMore) {
                loadPage(generation, cacheKey)
            }
        }
    }
    // Scroll independently of loading, and only after the active lazy container has actually laid out.
    LaunchedEffect(loadGeneration, activeGrid) {
        val expectedLayout = JmLibraryLayoutToken(loadGeneration, activeGrid)
        snapshotFlow {
            JmOnlineLibraryPolicy.shouldScrollToTop(
                hasVisibleItems = visibleItems.isNotEmpty(),
                loading = loadState.loading,
                hasError = loadState.error != null,
                listLaidOut = listLayoutToken == expectedLayout,
            )
        }.first { it }
        if (activeGrid) gridState.scrollToItem(0) else listState.scrollToItem(0)
    }
    BackHandler(enabled = selectionMode) { exitSelection() }
    BackHandler(enabled = query != null && !selectionMode) { query = null }

    Scaffold(
        topBar = { scrollBehavior ->
            LibraryHistoryToolbar(
                platform = ContentPlatform.JM,
                onSelectPlatform = onSelectPlatform,
                searchQuery = query,
                onChangeSearchQuery = { query = it },
                titleContent = {
                    Column {
                        Text(if (history) "历史" else "收藏", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!history) total?.let { Text("${items.size}/$it", style = MaterialTheme.typography.bodyMedium) }
                    }
                },
                searchSupported = true,
                refreshSupported = true,
                filterSupported = !history,
                onRefresh = ::refresh,
                onFilter = { showFilter = true },
                onEnterSelection = { if (loggedIn && !busy) selectionMode = true },
                selectionMode = selectionMode,
                selectionCount = selected.size,
                onExitSelection = ::exitSelection,
                onSelectAll = { if (!busy) selected = JmLibrarySelectionPolicy.selectAll(visibleItems.map { it.id }) },
                onInvertSelection = { if (!busy) selected = JmLibrarySelectionPolicy.invert(selected, visibleItems.map { it.id }) },
                scrollBehavior = scrollBehavior,
                history = history,
            )
        },
        bottomBar = {
            if (selectionMode) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (history) {
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = {
                            val ids = selected
                            mutate { JmOnlineLibraryRepository.ensureFavorites(ids) }
                        }) { Text("全部收藏夹") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { openFolderPicker(selected, true) }) { Text("其他收藏夹") }
                    } else {
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { openFolderPicker(selected, false) }) { Text("移动收藏夹") }
                    }
                    TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { removeIds = selected }) { Text(if (history) "删除" else "取消收藏", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(24.dp))
            operationError?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { operationError = null }) { Text("关闭") }
                }
            }
            if (!history && folderId.isNotEmpty()) {
                TextButton(onClick = {
                    if (!busy) {
                        folderId = ""
                        exitSelection()
                    }
                }) { Text("${folders.find { it.id == folderId }?.name.orEmpty()} · 返回全部") }
            }
            val state = JmOnlineLibraryPolicy.contentState(items.isNotEmpty(), loading, error)
            when {
                !loggedIn -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("登录 JM 账号后查看${if (history) "网络观看历史" else "收藏"}")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequireLogin) { Text("JM 登录") }
                }
                state == JmOnlineLibraryContentState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state == JmOnlineLibraryContentState.ERROR -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = ::refresh) { Text("重试") }
                }
                state == JmOnlineLibraryContentState.EMPTY || visibleItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (!query.isNullOrBlank()) {
                            "没有搜索结果（仅搜索已加载内容）"
                        } else if (history) {
                            "暂无 JM 网络观看历史"
                        } else {
                            "暂无 JM 收藏"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                activeGrid -> LazyVerticalGrid(
                    columns = GridCells.Fixed(JmFavoriteLibraryUiPolicy.gridColumns(configuredGridColumns)),
                    state = gridState,
                    modifier = Modifier.weight(1f).onGloballyPositioned {
                        val token = JmLibraryLayoutToken(loadGeneration, activeGrid)
                        if (listLayoutToken != token) listLayoutToken = token
                    },
                ) {
                    gridItems(visibleItems, key = { it.id }) { item ->
                        JmLibraryCard(item, displayMode, item.id in selected, selectionMode, onClick = { if (selectionMode) toggleSelection(item.id) else onOpenManga("jm:${item.id}") }, onLongClick = {
                            selectionMode = true
                            toggleSelection(item.id)
                        })
                    }
                    item { JmPageFooter(loading, error, hasMore, items.size, ::refresh) }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).onGloballyPositioned {
                        val token = JmLibraryLayoutToken(loadGeneration, activeGrid)
                        if (listLayoutToken != token) listLayoutToken = token
                    },
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        JmLibraryRow(
                            item = item,
                            displayMode = if (history) 5 else displayMode,
                            selected = item.id in selected,
                            selectionMode = selectionMode,
                            onClick = { if (selectionMode) toggleSelection(item.id) else onOpenManga("jm:${item.id}") },
                            onLongClick = {
                                selectionMode = true
                                toggleSelection(item.id)
                            },
                            actions = {
                                if (history && !selectionMode) {
                                    IconButton(enabled = !busy, onClick = { openFolderPicker(setOf(item.id), true) }) { Icon(Icons.Outlined.FavoriteBorder, contentDescription = "收藏") }
                                    IconButton(enabled = !busy, onClick = { removeIds = setOf(item.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
                                }
                            },
                        )
                    }
                    item { JmPageFooter(loading, error, hasMore, items.size, ::refresh) }
                }
            }
        }
    }

    if (showFilter) {
        TabbedDialog(
            onDismissRequest = { showFilter = false },
            tabTitles = persistentListOf("筛选", "排序", "显示", "管理"),
        ) { tab ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                when (tab) {
                    0 -> {
                        JmSingleChoiceChipRow(
                            title = "收藏夹",
                            options = listOf("" to "全部收藏夹") + folders.map { it.id to it.name },
                            selected = folderId,
                            enabled = !busy,
                            onSelect = {
                                folderId = it
                                exitSelection()
                            },
                        )
                    }
                    1 -> {
                        JmSingleChoiceChipRow(
                            title = "排序",
                            options = JmFavoriteLibraryUiPolicy.sortModes.map { it.order to it.label },
                            selected = order,
                            enabled = !busy,
                            onSelect = {
                                order = it
                                exitSelection()
                            },
                        )
                    }
                    2 -> {
                        JmSingleChoiceChipRow(
                            title = "显示模式",
                            options = JmFavoriteLibraryUiPolicy.displayModes.map { it.value to it.label },
                            selected = displayMode,
                            onSelect = ::updateDisplayMode,
                        )
                        if (displayMode <= 2) {
                            JmGridColumnsControl(configuredGridColumns, ::updateGridColumns)
                        }
                    }
                    3 -> {
                        TextButton(
                            enabled = loggedIn && !busy,
                            onClick = {
                                folderName = ""
                                newFolder = true
                            },
                        ) { Text("添加收藏夹") }
                        folders.forEach { folder ->
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(folder.name, modifier = Modifier.weight(1f))
                                TextButton(enabled = !busy, onClick = {
                                    editFolder = folder
                                    folderName = folder.name
                                }) { Text("改名") }
                                TextButton(enabled = !busy, onClick = { deleteFolder = folder }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
    val pendingFolderIds = moveIds ?: favoriteIds
    if (pendingFolderIds != null) {
        val adding = favoriteIds != null
        AlertDialog(
            onDismissRequest = {
                moveIds = null
                favoriteIds = null
            },
            title = { Text(if (adding) "添加到收藏夹" else "移动收藏夹") },
            text = {
                LazyColumn(Modifier.height(300.dp)) {
                    if (adding) {
                        item {
                            TextButton(onClick = {
                                favoriteIds = null
                                mutate { JmOnlineLibraryRepository.ensureFavorites(pendingFolderIds) }
                            }) { Text("全部收藏夹（不移动已有收藏）") }
                        }
                    }
                    items(folders, key = { it.id }) { folder ->
                        TextButton(onClick = {
                            moveIds = null
                            favoriteIds = null
                            mutate {
                                if (adding) JmOnlineLibraryRepository.ensureFavorites(pendingFolderIds, folder) else JmOnlineLibraryRepository.moveFavorites(pendingFolderIds, folder)
                            }
                        }) { Text(folder.name) }
                    }
                    if (folders.isEmpty()) item { Text("暂无其他收藏夹，可在收藏页的筛选 → 管理中添加") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    moveIds = null
                    favoriteIds = null
                }) { Text("取消") }
            },
        )
    }
    removeIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { removeIds = null },
            title = { Text(if (history) "删除网络观看记录" else "取消收藏") },
            text = { Text("确定${if (history) "删除" else "取消收藏"}选中的 ${ids.size} 项？") },
            confirmButton = {
                TextButton(onClick = {
                    removeIds = null
                    mutate {
                        if (history) JmOnlineLibraryRepository.deleteHistory(ids) else JmOnlineLibraryRepository.toggleFavorites(ids)
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { removeIds = null }) { Text("取消") } },
        )
    }
    if (newFolder || editFolder != null) {
        AlertDialog(
            onDismissRequest = {
                newFolder = false
                editFolder = null
            },
            title = { Text(if (newFolder) "添加收藏夹" else "重命名收藏夹") },
            text = { OutlinedTextField(value = folderName, onValueChange = { folderName = it }, singleLine = true, label = { Text("收藏夹名称") }) },
            confirmButton = {
                TextButton(enabled = folderName.isNotBlank(), onClick = {
                    val target = editFolder
                    val name = folderName.trim()
                    newFolder = false
                    editFolder = null
                    mutate { if (target == null) JmOnlineLibraryRepository.addFolder(name) else JmOnlineLibraryRepository.renameFolder(target, name) }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    newFolder = false
                    editFolder = null
                }) { Text("取消") }
            },
        )
    }
    deleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolder = null },
            title = { Text("删除收藏夹") },
            text = { Text("确定删除「${folder.name}」？非空收藏夹需先移走漫画，服务器不会直接删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFolder = null
                    mutate {
                        JmOnlineLibraryRepository.deleteFolder(folder)
                        if (folderId == folder.id) folderId = ""
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text("取消") } },
        )
    }
}

private data class JmLibraryLayoutToken(val generation: Int, val grid: Boolean)

@Composable
private fun JmPageFooter(loading: Boolean, error: String?, hasMore: Boolean, count: Int, refresh: () -> Unit) {
    when {
        loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
        error != null -> TextButton(onClick = refresh) { Text(error, color = MaterialTheme.colorScheme.error) }
        !hasMore -> Text("已加载 $count 条", modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun JmLibraryCover(item: JmOnlineLibraryItem, modifier: Modifier) {
    val imageUrl = item.image.ifBlank { "/media/albums/${item.id}_3x4.jpg" }.let { raw ->
        if (raw.startsWith("https://") || raw.startsWith("http://")) raw else "https://${JmRouteManager.activeImageDomain}/${raw.trimStart('/')}"
    }
    SubcomposeAsyncImage(
        model = imageUrl,
        imageLoader = JmCoverImageLoader.get(LocalContext.current),
        contentDescription = item.name,
        modifier = modifier.aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)),
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("封面") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> JmSingleChoiceChipRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun JmGridColumnsControl(columns: Int, onSetColumns: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(KMR.strings.jm_library_columns_per_row),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (columns == 0) stringResource(KMR.strings.jm_library_columns_auto) else columns.toString(),
                modifier = Modifier.padding(end = 8.dp),
            )
            Slider(
                value = columns.toFloat(),
                onValueChange = { onSetColumns(it.toInt()) },
                valueRange = 0f..JmFavoriteLibraryUiPolicy.MAX_GRID_COLUMNS.toFloat(),
                steps = JmFavoriteLibraryUiPolicy.MAX_GRID_COLUMNS - 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JmLibraryRow(
    item: JmOnlineLibraryItem,
    displayMode: Int,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val coverWidth = when (displayMode) {
        3 -> 40.dp
        4 -> 52.dp
        else -> 60.dp
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onClick() })
        JmLibraryCover(item, Modifier.width(coverWidth))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            if (displayMode >= 4 && item.author.isNotBlank()) Text(item.author, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (displayMode >= 5 && item.description.isNotBlank()) Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        actions()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JmLibraryCard(item: JmOnlineLibraryItem, displayMode: Int, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.padding(6.dp).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(8.dp))) {
            JmLibraryCover(item, Modifier.fillMaxSize())
            if (displayMode == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.35f)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xAA000000))),
                )
                Text(
                    text = item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(shadow = Shadow(color = Color.Black, blurRadius = 4f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
        }
        if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onClick() })
        if (displayMode == 1) Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}
