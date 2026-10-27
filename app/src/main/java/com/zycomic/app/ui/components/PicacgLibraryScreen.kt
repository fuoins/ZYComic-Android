package com.zycomic.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgLibraryCache
import com.zycomic.app.picacg.PicacgLibraryCacheEntry
import com.zycomic.app.picacg.PicacgLibraryCacheKey
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import androidx.compose.foundation.lazy.grid.items as gridItems

/** Picacg's online collection is never backed by Manwa/JM account repositories. */
@Composable
fun PicacgLibraryScreen(
    history: Boolean,
    onRequireLogin: () -> Unit,
    onOpenManga: (String) -> Unit,
    onSelectPlatform: (ContentPlatform) -> Unit,
) {
    if (history) {
        PicacgLocalHistoryScreen(
            onOpenManga = onOpenManga,
            onSelectPlatform = onSelectPlatform,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("picacg_collection_ui", android.content.Context.MODE_PRIVATE)
    }
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    val session by PicacgAuthStore.sessionRevision.collectAsState()
    val favouritesRevision by PicacgRepository.favouritesRevision.collectAsState()
    var comics by remember(session) { mutableStateOf<List<Manga>>(emptyList()) }
    var page by remember(session) { mutableIntStateOf(1) }
    var loadedPage by remember(session) { mutableIntStateOf(0) }
    var hasMore by remember(session) { mutableStateOf(false) }
    var loading by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var query by remember(session) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loadedFavouritesRevision by remember(session) { mutableLongStateOf(favouritesRevision) }
    var loadGeneration by remember(session) { mutableLongStateOf(0L) }
    var sort by remember(session) {
        mutableStateOf(
            if (history) {
                PicacgCollectionPolicy.DEFAULT_SORT
            } else {
                PicacgCollectionPolicy.normalizeSort(preferences.getString("picacg_favorites_sort", PicacgCollectionPolicy.DEFAULT_SORT).orEmpty())
            },
        )
    }
    var displayMode by remember(history) {
        mutableIntStateOf(
            PicacgCollectionPolicy.normalizeDisplayMode(
                if (history) {
                    PicacgCollectionPolicy.DEFAULT_FAVORITES_DISPLAY
                } else {
                    preferences.getInt(PicacgCollectionPolicy.displayPreferenceKey(), PicacgCollectionPolicy.DEFAULT_FAVORITES_DISPLAY)
                },
            ),
        )
    }
    var gridColumns by remember(history) {
        mutableIntStateOf(
            PicacgCollectionPolicy.normalizeGridColumns(
                if (history) {
                    PicacgCollectionPolicy.DEFAULT_GRID_COLUMNS
                } else {
                    preferences.getInt(PicacgCollectionPolicy.gridColumnsPreferenceKey(), PicacgCollectionPolicy.DEFAULT_GRID_COLUMNS)
                },
            ),
        )
    }
    var showFilter by remember { mutableStateOf(false) }
    var draftSort by remember { mutableStateOf(sort) }
    var draftDisplayMode by remember { mutableIntStateOf(displayMode) }
    var draftGridColumns by remember { mutableIntStateOf(gridColumns) }
    var selectionMode by remember(session, history) { mutableStateOf(false) }
    var selectedIds by remember(session, history) { mutableStateOf<Set<String>>(emptySet()) }
    var removingSelected by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadFailed = stringResource(KMR.strings.picacg_library_load_failed)

    val cacheKey = PicacgLibraryCacheKey(
        sessionRevision = session,
        favouritesRevision = favouritesRevision,
        sort = PicacgCollectionPolicy.normalizeSort(sort),
        query = query?.trim()?.lowercase().orEmpty(),
    )
    val latestCacheKey by rememberUpdatedState(cacheKey)
    val latestPage by rememberUpdatedState(page)
    LaunchedEffect(session, loggedIn, page, refresh, history, favouritesRevision, sort, query, removingSelected) {
        PicacgLibraryCache.activateSession(session, favouritesRevision, loggedIn)
        if (!loggedIn || history || removingSelected) return@LaunchedEffect
        if (loadedFavouritesRevision != favouritesRevision) {
            loadedFavouritesRevision = favouritesRevision
            comics = emptyList()
            hasMore = false
            loadedPage = 0
            page = 1
            refresh++
            return@LaunchedEffect
        }
        val startedSession = session
        val startedFavouritesRevision = favouritesRevision
        val requestKey = cacheKey
        val requestedPage = page
        if (requestedPage == 1) {
            val snapshot = PicacgLibraryCache.get(requestKey)
            if (snapshot != null) {
                comics = snapshot.items
                hasMore = snapshot.hasMore
                loadedPage = snapshot.page
            }
        }
        val requestGeneration = loadGeneration + 1L
        loadGeneration = requestGeneration
        loading = true
        error = null
        try {
            val result = PicacgRepository.favourites(
                page = requestedPage,
                sort = requestKey.sort,
                expectedSessionRevision = startedSession,
            )
            if (!PicacgCollectionPolicy.acceptsResult(startedSession, PicacgAuthStore.sessionRevision.value, PicacgAuthStore.loggedIn.value) ||
                PicacgRepository.favouritesRevision.value != startedFavouritesRevision ||
                loadGeneration != requestGeneration ||
                latestCacheKey != requestKey || latestPage != requestedPage
            ) {
                return@LaunchedEffect
            }
            val nextComics = PicacgCollectionPolicy.merge(comics, result.items, firstPage = requestedPage == 1) { it.id }
            comics = nextComics
            hasMore = result.hasMore
            loadedPage = requestedPage
            PicacgLibraryCache.put(requestKey, PicacgLibraryCacheEntry(nextComics, requestedPage, result.hasMore))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (PicacgAuthStore.sessionRevision.value == startedSession &&
                PicacgRepository.favouritesRevision.value == startedFavouritesRevision &&
                loadGeneration == requestGeneration &&
                latestCacheKey == requestKey && latestPage == requestedPage
            ) {
                error = failure.message ?: loadFailed
            }
        } finally {
            if (loadGeneration == requestGeneration) loading = false
        }
    }

    val filtered = comics.filter { PicacgCollectionPolicy.matchesSearch(query, it.name) }
    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }
    fun applyFilter() {
        showFilter = false
        if (!history) {
            val nextSort = PicacgCollectionPolicy.normalizeSort(draftSort)
            if (nextSort != sort) {
                sort = nextSort
                preferences.edit().putString("picacg_favorites_sort", nextSort).apply()
                comics = emptyList()
                hasMore = false
                loadedPage = 0
                page = 1
                refresh++
            }
            displayMode = PicacgCollectionPolicy.normalizeDisplayMode(draftDisplayMode)
            gridColumns = PicacgCollectionPolicy.normalizeGridColumns(draftGridColumns)
            preferences.edit()
                .putInt(PicacgCollectionPolicy.displayPreferenceKey(), displayMode)
                .putInt(PicacgCollectionPolicy.gridColumnsPreferenceKey(), gridColumns)
                .apply()
        }
    }

    BackHandler(enabled = selectionMode) { exitSelection() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { scrollBehavior ->
            LibraryHistoryToolbar(
                platform = ContentPlatform.PICACG,
                onSelectPlatform = onSelectPlatform,
                searchQuery = query,
                onChangeSearchQuery = { query = it },
                titleContent = { Text(stringResource(if (history) KMR.strings.picacg_history_title else KMR.strings.picacg_library_title)) },
                searchSupported = !history && loggedIn,
                refreshSupported = !history,
                refreshBusy = loading || removingSelected,
                refreshEnabled = loggedIn,
                refreshIndicatorSeparate = true,
                refreshEnabledWhileBusy = loading && !removingSelected,
                filterSupported = !history && loggedIn,
                onRefresh = {
                    page = 1
                    refresh++
                    exitSelection()
                },
                onFilter = {
                    draftSort = sort
                    draftDisplayMode = displayMode
                    draftGridColumns = gridColumns
                    showFilter = true
                },
                onEnterSelection = { if (!history && loggedIn && !loading && comics.isNotEmpty()) selectionMode = true },
                selectionMode = selectionMode,
                selectionCount = selectedIds.size,
                onExitSelection = ::exitSelection,
                onSelectAll = { selectedIds = filtered.mapTo(mutableSetOf()) { it.id } },
                onInvertSelection = {
                    val visibleIds = filtered.map { it.id }.toSet()
                    selectedIds = visibleIds - selectedIds
                },
                scrollBehavior = scrollBehavior,
                history = history,
                selectionSupported = !history && loggedIn,
            )
        },
        bottomBar = {
            if (selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(
                        enabled = selectedIds.isNotEmpty() && !removingSelected,
                        onClick = {
                            val ids = selectedIds.toList()
                            val startedSession = session
                            removingSelected = true
                            scope.launch {
                                try {
                                    PicacgCollectionPolicy.removeSequentially(
                                        ids = ids,
                                        ensureSession = {
                                            check(
                                                PicacgCollectionPolicy.acceptsResult(
                                                    startedSession,
                                                    PicacgAuthStore.sessionRevision.value,
                                                    PicacgAuthStore.loggedIn.value,
                                                ),
                                            ) { loadFailed }
                                        },
                                        remove = { id -> PicacgRepository.toggleFavourite(id) },
                                    )
                                    exitSelection()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: loadFailed
                                    exitSelection()
                                } finally {
                                    removingSelected = false
                                    page = 1
                                    refresh++
                                }
                            }
                        },
                    ) {
                        Text(stringResource(KMR.strings.picacg_cancel) + stringResource(KMR.strings.picacg_favourite), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    ) { contentPadding ->
        when {
            PicacgCollectionPolicy.requiresLogin(history, loggedIn) -> Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(KMR.strings.zy_account_picacg_favorites_login_required))
                Button(onClick = onRequireLogin) { Text(stringResource(KMR.strings.zy_account_picacg_login_action)) }
            }
            loading && comics.isEmpty() -> LoadingScreen(modifier = Modifier.padding(contentPadding))
            error != null && comics.isEmpty() -> Column(modifier = Modifier.padding(contentPadding).padding(24.dp)) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }) { Text(stringResource(KMR.strings.picacg_retry)) }
            }
            filtered.isEmpty() -> EmptyScreen(
                message = stringResource(if (query.isNullOrBlank()) KMR.strings.picacg_favourites_empty else KMR.strings.picacg_search_empty),
                modifier = Modifier.padding(contentPadding),
            )
            displayMode == PicacgCollectionPolicy.DISPLAY_GRID -> LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                gridItems(filtered, key = { it.id }) { comic ->
                    Box {
                        PicacgMangaCard(
                            manga = comic,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (comic.id in selectedIds) selectedIds - comic.id else selectedIds + comic.id
                                } else {
                                    onOpenManga("picacg:${comic.id}")
                                }
                            },
                        )
                        if (selectionMode) {
                            Checkbox(
                                checked = comic.id in selectedIds,
                                onCheckedChange = { checked -> selectedIds = if (checked) selectedIds + comic.id else selectedIds - comic.id },
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (hasMore) {
                            TextButton(onClick = { if (error == null) page = loadedPage + 1 else refresh++ }, enabled = !loading) {
                                Text(
                                    stringResource(
                                        if (loading) {
                                            KMR.strings.picacg_loading
                                        } else if (error != null) {
                                            KMR.strings.picacg_retry
                                        } else {
                                            KMR.strings.picacg_load_more
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                items(filtered, key = { it.id }) { comic ->
                    PicacgMangaListRow(
                        manga = comic,
                        selected = comic.id in selectedIds,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (comic.id in selectedIds) selectedIds - comic.id else selectedIds + comic.id
                            } else {
                                onOpenManga("picacg:${comic.id}")
                            }
                        },
                        onCheckedChange = { checked -> selectedIds = if (checked) selectedIds + comic.id else selectedIds - comic.id },
                    )
                }
                item {
                    Column {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (hasMore) {
                            TextButton(onClick = { if (error == null) page = loadedPage + 1 else refresh++ }, enabled = !loading) {
                                Text(
                                    stringResource(
                                        if (loading) {
                                            KMR.strings.picacg_loading
                                        } else if (error != null) {
                                            KMR.strings.picacg_retry
                                        } else {
                                            KMR.strings.picacg_load_more
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilter) {
        AlertDialog(
            onDismissRequest = { showFilter = false },
            title = { Text(stringResource(KMR.strings.picacg_filter_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!history) {
                        Text(stringResource(KMR.strings.picacg_sort))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PicacgCollectionPolicy.sortCodes.take(2).forEach { code ->
                                val label = when (code) {
                                    "dd" -> stringResource(KMR.strings.picacg_favorites_recent)
                                    else -> stringResource(KMR.strings.picacg_favorites_oldest)
                                }
                                FilterChip(selected = draftSort == code, onClick = { draftSort = code }, label = { Text(label) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(selected = draftSort == "ld", onClick = { draftSort = "ld" }, label = { Text(stringResource(KMR.strings.picacg_most_likes)) })
                            FilterChip(selected = draftSort == "vd", onClick = { draftSort = "vd" }, label = { Text(stringResource(KMR.strings.picacg_most_views)) })
                        }
                    }
                    Text(stringResource(KMR.strings.picacg_collection_display_mode))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = draftDisplayMode == PicacgCollectionPolicy.DISPLAY_GRID,
                            onClick = { draftDisplayMode = PicacgCollectionPolicy.DISPLAY_GRID },
                            label = { Text(stringResource(KMR.strings.picacg_collection_grid)) },
                        )
                        FilterChip(
                            selected = draftDisplayMode == PicacgCollectionPolicy.DISPLAY_LIST,
                            onClick = { draftDisplayMode = PicacgCollectionPolicy.DISPLAY_LIST },
                            label = { Text(stringResource(KMR.strings.picacg_collection_list)) },
                        )
                    }
                    Text(stringResource(KMR.strings.picacg_collection_grid_columns))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (2..5).forEach { columns ->
                            FilterChip(
                                selected = draftGridColumns == columns,
                                onClick = { draftGridColumns = columns },
                                label = {
                                    Text(stringResource(KMR.strings.picacg_collection_grid_columns_option, columns))
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::applyFilter) { Text(stringResource(KMR.strings.picacg_apply)) } },
            dismissButton = { TextButton(onClick = { showFilter = false }) { Text(stringResource(KMR.strings.picacg_cancel)) } },
        )
    }
}

@Composable
private fun PicacgMangaListRow(
    manga: Manga,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) Checkbox(checked = selected, onCheckedChange = onCheckedChange)
        SubcomposeAsyncImage(
            model = manga.picx.ifBlank { manga.pic },
            contentDescription = manga.name,
            imageLoader = PicacgImageLoader.get(LocalContext.current),
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(68.dp).height(96.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(manga.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (manga.author.isNotBlank()) {
                Text(manga.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
