package com.zycomic.app.ui.components

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.zycomic.app.reader.PicacgSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat

/**
 * Shows Picacg reader history from the shared on-device history database only. Search, refresh,
 * and deletion operate on that local data; no Picacg history/account endpoint is called here.
 */
@Composable
fun PicacgLocalHistoryScreen(
    onOpenManga: (String) -> Unit,
    onSelectPlatform: (com.zycomic.app.platform.ContentPlatform) -> Unit,
    modifier: Modifier = Modifier,
) {
    val getHistory = remember { Injekt.get<GetHistory>() }
    val getManga = remember { Injekt.get<GetManga>() }
    val getChapter = remember { Injekt.get<GetChapter>() }
    val removeHistory = remember { Injekt.get<RemoveHistory>() }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val historyFlow = remember(getHistory, getManga, getChapter, refreshKey) {
        getHistory.subscribe(
            query = "",
            unfinishedManga = null,
            unfinishedChapter = null,
            nonLibraryEntries = null,
        ).map { entries ->
            withContext(Dispatchers.IO) {
                PicacgLocalHistoryPolicy.newestFirst(
                    PicacgLocalHistoryPolicy.onlyPicacg(entries, PicacgSource.id),
                ).map { history ->
                    val manga = getManga.await(history.mangaId)
                    val comicId = manga?.let {
                        PicacgLocalHistoryPolicy.comicId(it.url, it.source, PicacgSource.id)
                    }
                    val chapter = getChapter.await(history.chapterId)
                    PicacgLocalHistoryRow(
                        history = history,
                        comicId = comicId,
                        author = manga?.author.orEmpty(),
                        categories = manga?.genre.orEmpty(),
                        tags = manga?.genre.orEmpty(),
                        chapterTitle = chapter?.name.orEmpty(),
                    )
                }
            }
        }
    }
    val allRows by historyFlow.collectAsState(initial = null)
    val rows = allRows.orEmpty().filter { row ->
        PicacgLocalHistoryPolicy.matchesSearch(
            query = searchQuery.orEmpty(),
            title = row.history.title,
            author = row.author,
            categories = row.categories,
            tags = row.tags,
        )
    }
    val visibleIds = rows.map { it.history.id }.toSet()

    LaunchedEffect(refreshKey, allRows) {
        if (refreshKey > 0 && allRows != null) isRefreshing = false
    }
    LaunchedEffect(visibleIds, selectedIds) {
        selectedIds = selectedIds.intersect(visibleIds)
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelection(id: Long) {
        selectedIds = PicacgLocalHistoryPolicy.toggleSelection(selectedIds, id)
    }

    BackHandler(enabled = selectionMode) { exitSelection() }
    BackHandler(enabled = searchQuery != null && !selectionMode) { searchQuery = null }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = { scrollBehavior ->
            GlassTopBar {
            LibraryHistoryToolbar(
                platform = com.zycomic.app.platform.ContentPlatform.PICACG,
                onSelectPlatform = onSelectPlatform,
                searchQuery = searchQuery,
                onChangeSearchQuery = { searchQuery = it },
                titleContent = { LibraryHistoryTitle("历史") },
                searchSupported = true,
                refreshSupported = !isRefreshing && !deleting,
                filterSupported = false,
                onRefresh = {
                    isRefreshing = true
                    refreshKey++
                },
                onFilter = {},
                onEnterSelection = { selectionMode = true },
                selectionMode = selectionMode,
                selectionCount = selectedIds.size,
                onExitSelection = ::exitSelection,
                onSelectAll = { selectedIds = PicacgLocalHistoryPolicy.allVisibleIds(rows.map { it.history.id }) },
                onInvertSelection = {
                    selectedIds = visibleIds - selectedIds
                },
                scrollBehavior = scrollBehavior,
                history = true,
                selectionSupported = true,
                selectionEnabled = true,
            )
            }
        },
        bottomBar = {
            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .glassSurface(shape = RoundedCornerShape(14.dp), radius = 14.dp, stroke = false),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(
                        enabled = selectedIds.isNotEmpty() && !deleting,
                        onClick = { showDeleteConfirmation = true },
                    ) {
                        Text(
                            text = stringResource(KMR.strings.picacg_local_history_delete_selected, selectedIds.size),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        when {
            allRows == null -> LoadingScreen(modifier = Modifier.padding(contentPadding))
            rows.isEmpty() -> EmptyScreen(
                message = stringResource(
                    if (searchQuery.isNullOrBlank()) {
                        KMR.strings.picacg_local_history_empty
                    } else {
                        KMR.strings.picacg_local_history_search_empty
                    },
                ),
                modifier = Modifier.padding(contentPadding),
            )
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    refreshKey++
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(rows, key = { it.history.id }) { row ->
                        PicacgLocalHistoryItem(
                            row = row,
                            selected = row.history.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(row.history.id)
                                } else {
                                    row.comicId?.let { onOpenManga("picacg:$it") }
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                toggleSelection(row.history.id)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirmation = false },
            title = { Text(stringResource(KMR.strings.picacg_local_history_delete_title)) },
            text = { Text(stringResource(KMR.strings.picacg_local_history_delete_message, selectedIds.size)) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        // One visible row represents a manga, not every historical chapter.
                        // Reset all its history timestamps without touching chapter read/page state.
                        val mangaIdsToRemove = rows.filter { it.history.id in selectedIds }
                            .map { it.history.mangaId }.distinct()
                        deleting = true
                        scope.launch {
                            try {
                                removeHistory.awaitManga(mangaIdsToRemove)
                                selectedIds = emptySet()
                                selectionMode = false
                            } finally {
                                deleting = false
                                showDeleteConfirmation = false
                                refreshKey++
                            }
                        }
                    },
                ) { Text(stringResource(KMR.strings.picacg_local_history_delete_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }, enabled = !deleting) { Text(stringResource(KMR.strings.picacg_local_history_cancel)) }
            },
        )
    }
}

private data class PicacgLocalHistoryRow(
    val history: HistoryWithRelations,
    val comicId: String?,
    val author: String,
    val categories: List<String>,
    val tags: List<String>,
    val chapterTitle: String,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PicacgLocalHistoryItem(
    row: PicacgLocalHistoryRow,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val history = row.history
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val readTime = history.readAt?.let { readAt ->
        DateUtils.getRelativeTimeSpanString(
            readAt.time,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    val chapter = row.chapterTitle.takeIf { it.isNotBlank() }
        ?: history.chapterNumber.takeIf { it > 0 }?.let { number ->
            val value = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
            stringResource(KMR.strings.picacg_local_history_chapter, value)
        }
    val totalChapters = history.totalCountCalculated.coerceAtLeast(0)
    val readChapters = history.readCountCalculated.coerceIn(0, totalChapters)
    val progress = if (totalChapters > 0) readChapters.toFloat() / totalChapters else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .glassSurface(shape = RoundedCornerShape(14.dp), radius = 14.dp, stroke = false)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                enabled = true,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
            )
        }
        PicacgLocalHistoryCover(
            url = history.coverData.url,
            modifier = Modifier.width(68.dp),
            contentDescription = history.title,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .glassSurface(
                        shape = RoundedCornerShape(8.dp),
                        radius = 8.dp,
                        part = GlassPart.COVER_NAME,
                        stroke = false,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = history.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.author.isNotBlank()) {
                Text(stringResource(KMR.strings.picacg_local_history_author, row.author), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            chapter?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val pageNumber = PicacgLocalHistoryPolicy.lastReadPageNumber(history.lastPageRead)
            Text(
                text = stringResource(KMR.strings.picacg_local_history_last_page, pageNumber.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (totalChapters > 0) {
                LinearProgressIndicator(
                    { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(KMR.strings.picacg_local_history_progress, readChapters, totalChapters, (progress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            history.readAt?.let {
                Text(
                    text = readTime ?: dateFormat.format(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PicacgLocalHistoryCover(
    url: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (!url.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = url,
                imageLoader = PicacgImageLoader.get(context),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = { CoverError(Modifier.fillMaxSize()) },
            )
        }
    }
}
