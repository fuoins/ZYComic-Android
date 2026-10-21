package com.zycomic.app.ui.picacg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.picacg.filter.PicacgBlockKeywordStore
import com.zycomic.app.ui.components.DisplaySettingsSection
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.FilterSection
import com.zycomic.app.ui.components.MangaGridSkeleton
import com.zycomic.app.ui.components.PicacgCollectionPolicy
import com.zycomic.app.ui.components.PicacgImageLoader
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

private enum class PicacgFeedMode(val timeframe: String? = null) {
    LEADERBOARD_24H("H24"),
    LEADERBOARD_7D("D7"),
    LEADERBOARD_30D("D30"),
    RANDOM,
    LATEST,
    FILTERED,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PicacgBrowseScreen(
    pendingTag: String? = null,
    onPendingTagConsumed: () -> Unit = {},
    onOpenManga: (String) -> Unit,
    onRequireLogin: () -> Unit,
    tabId: String = "",
    initialFilter: PicacgBrowseFilter? = null,
    pageMode: PicacgBrowsePageMode = PicacgBrowsePageMode.HOME,
    displayMode: Int = 1,
    gridColumns: Int = 3,
    orientation: Int = 1,
    filterDialogRequest: Int = 0,
    onSetDisplayMode: (Int) -> Unit = {},
    onSetGridColumns: (Int) -> Unit = {},
) {
    val loadFailed = stringResource(KMR.strings.picacg_library_load_failed)
    val invalidPreset = stringResource(KMR.strings.picacg_preset_invalid)
    val context = LocalContext.current
    val effectiveGridColumns = (gridColumns.takeIf { it > 0 } ?: 3).coerceIn(1, 10)
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    val session by PicacgAuthStore.sessionRevision.collectAsState()
    val blockKeywords by PicacgBlockKeywordStore.keywords.collectAsState()
    val presets = remember { mutableStateOf(PicacgBrowsePrefs.load(context)) }
    var mode by remember(session, tabId, initialFilter, pageMode) {
        mutableStateOf(
            if (pageMode == PicacgBrowsePageMode.CATEGORY) {
                PicacgFeedMode.FILTERED
            } else {
                PicacgFeedMode.LEADERBOARD_24H
            },
        )
    }
    var filter by remember(session, tabId, initialFilter) { mutableStateOf(initialFilter?.normalized() ?: PicacgBrowseFilter()) }
    // Reload from page one so removing a rule can restore items previously filtered at the facade.
    var page by remember(session, tabId, initialFilter, blockKeywords) { mutableStateOf(1) }
    var mangas by remember(session, tabId, initialFilter, blockKeywords) { mutableStateOf<List<Manga>>(emptyList()) }
    var hasMore by remember(session, tabId, initialFilter, blockKeywords) { mutableStateOf(false) }
    var loading by remember(session, tabId, initialFilter, blockKeywords) { mutableStateOf(false) }
    var error by remember(session, tabId, initialFilter, blockKeywords) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showFilters by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var draftFilter by remember { mutableStateOf(filter) }
    var draftCategory by remember(filter) { mutableStateOf(filter.category) }
    var draftSort by remember(filter) { mutableStateOf(filter.sort) }
    var presetName by remember { mutableStateOf("") }
    var presetError by remember { mutableStateOf<String?>(null) }
    var handledFilterRequest by remember { mutableStateOf(filterDialogRequest) }

    LaunchedEffect(filterDialogRequest) {
        if (filterDialogRequest != handledFilterRequest) {
            handledFilterRequest = filterDialogRequest
            showFilters = true
        }
    }

    LaunchedEffect(pendingTag) {
        if (pendingTag != null) {
            filter = filter.copy(tag = pendingTag.trim())
            mode = PicacgFeedMode.FILTERED
            page = 1
            onPendingTagConsumed()
        }
    }

    LaunchedEffect(session, loggedIn, tabId, mode, filter, page, refresh, pageMode, blockKeywords) {
        if (!loggedIn) return@LaunchedEffect
        loading = true
        error = null
        try {
            val currentFilter = filter.normalized()
            val pageItems: List<Manga>
            val nextPageExists: Boolean
            when (mode) {
                PicacgFeedMode.RANDOM -> {
                    pageItems = if (page == 1) PicacgRepository.random() else emptyList()
                    nextPageExists = false
                }
                PicacgFeedMode.LEADERBOARD_24H,
                PicacgFeedMode.LEADERBOARD_7D,
                PicacgFeedMode.LEADERBOARD_30D,
                -> {
                    pageItems = if (page == 1) PicacgRepository.leaderboard(mode.timeframe!!) else emptyList()
                    nextPageExists = false
                }
                PicacgFeedMode.LATEST, PicacgFeedMode.FILTERED -> {
                    val response = PicacgRepository.browse(
                        page = page,
                        category = currentFilter.category.ifBlank { null },
                        sort = currentFilter.sort,
                        tag = currentFilter.tag.ifBlank { null },
                        author = currentFilter.author.ifBlank { null },
                        creatorId = currentFilter.creatorId.ifBlank { null },
                        chineseTeam = currentFilter.chineseTeam.ifBlank { null },
                    )
                    pageItems = response.items
                    nextPageExists = response.hasMore
                }
            }
            if (session == PicacgAuthStore.sessionRevision.value && loggedIn) {
                mangas = PicacgCollectionPolicy.merge(mangas, pageItems, page == 1) { it.id }
                hasMore = nextPageExists
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (session == PicacgAuthStore.sessionRevision.value) error = failure.message ?: loadFailed
        } finally {
            loading = false
        }
    }

    fun selectMode(next: PicacgFeedMode, nextFilter: PicacgBrowseFilter = filter) {
        mode = next
        filter = nextFilter
        page = 1
        mangas = emptyList()
        error = null
        hasMore = false
    }

    fun persistPresets(next: List<PicacgBrowsePreset>) {
        presets.value = next
        PicacgBrowsePrefs.save(context, next)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!loggedIn) {
            Text(stringResource(KMR.strings.picacg_session_required))
            Button(onClick = onRequireLogin) { Text(stringResource(KMR.strings.picacg_login_action)) }
        } else {
            if (loading && mangas.isEmpty()) {
                if (displayMode == 3) {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(8) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Box(Modifier.width(68.dp).aspectRatio(0.72f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.fillMaxWidth(0.75f).height(16.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)))
                                    Box(Modifier.fillMaxWidth(0.45f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)))
                                }
                            }
                        }
                    }
                } else {
                    MangaGridSkeleton(columns = effectiveGridColumns, displayMode = displayMode, modifier = Modifier.weight(1f))
                }
            } else if (displayMode == 3) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    error?.let { failure ->
                        item {
                            Column {
                                Text(failure, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(KMR.strings.picacg_retry)) }
                            }
                        }
                    }
                    items(mangas, key = { it.id }) { manga ->
                        PicacgBrowseComicListItem(manga) { onOpenManga("picacg:${manga.id}") }
                    }
                    if (!loading && error == null && mangas.isEmpty()) item { Text(stringResource(KMR.strings.picacg_search_empty)) }
                    if (hasMore) {
                        item {
                            TextButton(onClick = { if (error == null) page++ else refresh++ }, enabled = !loading) { Text(stringResource(KMR.strings.picacg_load_more)) }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = if (gridColumns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(effectiveGridColumns),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(if (displayMode == 0) 6.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (displayMode == 0) 8.dp else 12.dp),
                ) {
                    error?.let { failure ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text(failure, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(KMR.strings.picacg_retry)) }
                            }
                        }
                    }
                    items(mangas, key = { it.id }) { manga ->
                        PicacgBrowseComicGridItem(manga = manga, displayMode = displayMode) { onOpenManga("picacg:${manga.id}") }
                    }
                    if (!loading && error == null && mangas.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(KMR.strings.picacg_search_empty)) }
                    }
                    if (hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { if (error == null) page++ else refresh++ }, enabled = !loading) { Text(stringResource(KMR.strings.picacg_load_more)) }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        LaunchedEffect(showFilters) {
            if (showFilters) {
                draftFilter = filter
                draftCategory = filter.category
                draftSort = filter.sort
            }
        }
        val displayOnly = pageMode != PicacgBrowsePageMode.CATEGORY
        TabbedDialog(
            onDismissRequest = { showFilters = false },
            tabTitles = if (displayOnly) persistentListOf("显示") else persistentListOf("重新筛选", "显示"),
        ) { dialogPage ->
            if (displayOnly || dialogPage == 1) {
                DisplaySettingsSection(
                    displayMode = displayMode,
                    gridColumns = gridColumns,
                    orientation = orientation,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(TabbedDialogPaddings.Horizontal),
                    onSetDisplayMode = onSetDisplayMode,
                    onSetGridColumns = onSetGridColumns,
                    modes = listOf(0 to "紧凑网格", 1 to "舒适网格", 2 to "仅封面网格", 3 to "列表"),
                )
            } else {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(TabbedDialogPaddings.Horizontal), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterSection("主题") {
                        FilterChip(
                            selected = draftCategory.isBlank(),
                            onClick = {
                                draftCategory = ""
                                draftFilter = draftFilter.copy(category = "")
                            },
                            text = stringResource(KMR.strings.picacg_all),
                        )
                        PicacgThemes.titles.forEach { title ->
                            FilterChip(
                                selected = draftCategory == title,
                                onClick = {
                                    draftCategory = title
                                    draftFilter = draftFilter.copy(category = title)
                                },
                                text = title,
                            )
                        }
                    }
                    if (pageMode != PicacgBrowsePageMode.CATEGORY) {
                        OutlinedTextField(draftFilter.tag, { draftFilter = draftFilter.copy(tag = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_tag)) }, singleLine = true)
                        OutlinedTextField(draftFilter.author, { draftFilter = draftFilter.copy(author = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_author)) }, singleLine = true)
                        OutlinedTextField(draftFilter.creatorId, { draftFilter = draftFilter.copy(creatorId = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_creator)) }, singleLine = true)
                        OutlinedTextField(draftFilter.chineseTeam, { draftFilter = draftFilter.copy(chineseTeam = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_team)) }, singleLine = true)
                    }
                    FilterSection(stringResource(KMR.strings.picacg_sort)) {
                        listOf("新到旧" to "dd", "旧到新" to "da", "最多喜欢" to "ld", "最多浏览" to "vd").forEach { (label, code) ->
                            FilterChip(text = label, selected = draftSort == code, onClick = {
                                draftSort = code
                                draftFilter = draftFilter.copy(sort = code)
                            })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            val next = draftFilter.copy(category = draftCategory, sort = draftSort).normalized()
                            selectMode(if (next.category.isBlank() && pageMode == PicacgBrowsePageMode.HOME) PicacgFeedMode.LATEST else PicacgFeedMode.FILTERED, next)
                            showFilters = false
                        }) { Text(stringResource(KMR.strings.picacg_apply)) }
                    }
                }
            }
        }
    }

    if (showPresets) {
        AlertDialog(
            onDismissRequest = {
                showPresets = false
                presetError = null
            },
            title = { Text(stringResource(KMR.strings.picacg_presets_title)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text(stringResource(KMR.strings.picacg_presets_local_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        OutlinedTextField(
                            value = presetName,
                            onValueChange = {
                                presetName = it
                                presetError = null
                            },
                            label = { Text(stringResource(KMR.strings.picacg_preset_name)) },
                            singleLine = true,
                        )
                    }
                    item {
                        TextButton(
                            onClick = {
                                val next = PicacgBrowsePrefsPolicy.save(presets.value, presetName, filter)
                                if (next == null) {
                                    presetError = invalidPreset
                                } else {
                                    persistPresets(next)
                                    presetName = ""
                                    presetError = null
                                }
                            },
                            enabled = presetName.isNotBlank(),
                        ) { Text(stringResource(KMR.strings.picacg_preset_save)) }
                    }
                    presetError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    item { Text(stringResource(KMR.strings.picacg_presets_saved), style = MaterialTheme.typography.titleSmall) }
                    items(presets.value, key = { "preset-${it.id}" }) { preset ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                selectMode(PicacgFeedMode.FILTERED, preset.filter)
                                showPresets = false
                            }) { Text("${preset.name}  ·  ${preset.filter.summary()}") }
                            TextButton(onClick = { persistPresets(PicacgBrowsePrefsPolicy.delete(presets.value, preset.id)) }) { Text(stringResource(KMR.strings.picacg_delete)) }
                        }
                    }
                    if (presets.value.isEmpty()) item { Text(stringResource(KMR.strings.picacg_presets_empty)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPresets = false
                    presetError = null
                }) { Text(stringResource(KMR.strings.picacg_done)) }
            },
        )
    }
}

private fun PicacgBrowseFilter.summary(): String = listOf(category, tag, author, creatorId, chineseTeam)
    .filter(String::isNotBlank)
    .joinToString(" · ")
    .ifBlank { sort }

@Composable
private fun PicacgBrowseComicGridItem(manga: Manga, displayMode: Int, onClick: () -> Unit) {
    if (displayMode == 1) {
        com.zycomic.app.ui.components.PicacgMangaCard(manga = manga, onClick = onClick)
        return
    }
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
    ) {
        val cover = manga.picx.ifBlank { manga.pic }
        if (cover.isNotBlank()) {
            SubcomposeAsyncImage(
                model = cover,
                contentDescription = manga.name,
                imageLoader = PicacgImageLoader.get(context),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (displayMode == 0) {
            Text(
                text = manga.name,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f)).padding(6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PicacgBrowseComicListItem(manga: Manga, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubcomposeAsyncImage(
            model = manga.picx.ifBlank { manga.pic },
            contentDescription = manga.name,
            imageLoader = PicacgImageLoader.get(context),
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(68.dp).aspectRatio(0.72f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(manga.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(manga.author.ifBlank { manga.categoryName }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
