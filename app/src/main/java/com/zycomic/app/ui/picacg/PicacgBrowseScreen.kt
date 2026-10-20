package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgCategory
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.ui.components.PicacgCollectionPolicy
import com.zycomic.app.ui.components.PicacgMangaCard
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

private enum class PicacgFeedMode(val timeframe: String? = null) {
    LATEST,
    RANDOM,
    LEADERBOARD_24H("H24"),
    LEADERBOARD_7D("D7"),
    LEADERBOARD_30D("D30"),
    FILTERED,
}

@Composable
private fun PicacgFeedMode.label(): String = stringResource(
    when (this) {
        PicacgFeedMode.LATEST -> KMR.strings.picacg_latest
        PicacgFeedMode.RANDOM -> KMR.strings.picacg_random
        PicacgFeedMode.LEADERBOARD_24H -> KMR.strings.picacg_rank_24h
        PicacgFeedMode.LEADERBOARD_7D -> KMR.strings.picacg_rank_7d
        PicacgFeedMode.LEADERBOARD_30D -> KMR.strings.picacg_rank_30d
        PicacgFeedMode.FILTERED -> KMR.strings.picacg_filtered
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PicacgBrowseScreen(
    pendingTag: String? = null,
    onPendingTagConsumed: () -> Unit = {},
    onOpenManga: (String) -> Unit,
    onOpenSearch: (String) -> Unit,
    onRequireLogin: () -> Unit,
) {
    val loadFailed = stringResource(KMR.strings.picacg_library_load_failed)
    val invalidPreset = stringResource(KMR.strings.picacg_preset_invalid)
    val context = LocalContext.current
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    val session by PicacgAuthStore.sessionRevision.collectAsState()
    val presets = remember { mutableStateOf(PicacgBrowsePrefs.load(context)) }
    var mode by remember(session) { mutableStateOf(PicacgFeedMode.LATEST) }
    var filter by remember(session) { mutableStateOf(PicacgBrowseFilter()) }
    var categories by remember(session) { mutableStateOf<List<PicacgCategory>>(emptyList()) }
    var page by remember(session) { mutableStateOf(1) }
    var mangas by remember(session) { mutableStateOf<List<Manga>>(emptyList()) }
    var hasMore by remember(session) { mutableStateOf(false) }
    var loading by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showFilters by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var draftFilter by remember { mutableStateOf(filter) }
    var keyword by remember(session) { mutableStateOf("") }
    var presetName by remember { mutableStateOf("") }
    var presetError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingTag) {
        if (pendingTag != null) {
            filter = filter.copy(tag = pendingTag.trim())
            mode = PicacgFeedMode.FILTERED
            page = 1
            onPendingTagConsumed()
        }
    }

    LaunchedEffect(session, loggedIn, refresh) {
        if (!loggedIn) return@LaunchedEffect
        try {
            categories = PicacgBrowsePolicy.visibleCategories(PicacgRepository.categories())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Browsing remains usable with manually entered category names if category lookup fails.
        }
    }

    LaunchedEffect(session, loggedIn, mode, filter, page, refresh) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(KMR.strings.picacg_browse_search)) },
                singleLine = true,
            )
            TextButton(onClick = { onOpenSearch(keyword) }, enabled = keyword.isNotBlank()) { Text(stringResource(KMR.strings.picacg_search_action)) }
            TextButton(onClick = { showFilters = true }) { Text(stringResource(KMR.strings.picacg_filter)) }
            TextButton(onClick = { showPresets = true }) { Text(stringResource(KMR.strings.picacg_presets)) }
        }
        if (!loggedIn) {
            Text(stringResource(KMR.strings.picacg_session_required))
            Button(onClick = onRequireLogin) { Text(stringResource(KMR.strings.picacg_login_action)) }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(PicacgFeedMode.entries.filter { it != PicacgFeedMode.FILTERED }, key = { it.name }) { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { selectMode(item, if (item == PicacgFeedMode.LATEST) filter.copy(sort = "dd") else filter) },
                        label = { Text(item.label()) },
                    )
                }
                items(presets.value, key = { it.id }) { preset ->
                    FilterChip(
                        selected = mode == PicacgFeedMode.FILTERED && filter.normalized() == preset.filter,
                        onClick = { selectMode(PicacgFeedMode.FILTERED, preset.filter) },
                        label = { Text(preset.name) },
                    )
                }
            }
            if (categories.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        FilterChip(
                            selected = filter.category.isBlank(),
                            onClick = { selectMode(modeForCurrentFilter(), filter.copy(category = "")) },
                            label = { Text(stringResource(KMR.strings.picacg_all)) },
                        )
                    }
                    items(categories, key = { it.id.ifBlank { it.title } }) { category ->
                        FilterChip(
                            selected = filter.category == category.title,
                            onClick = { selectMode(modeForCurrentFilter(), filter.copy(category = category.title)) },
                            label = { Text(category.title) },
                        )
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (loading && mangas.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { CircularProgressIndicator() }
                error?.let { failure ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text(failure, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(KMR.strings.picacg_retry)) }
                        }
                    }
                }
                items(mangas, key = { it.id }) { manga ->
                    PicacgMangaCard(manga = manga, onClick = { onOpenManga("picacg:${manga.id}") })
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

    if (showFilters) {
        LaunchedEffect(showFilters) { if (showFilters) draftFilter = filter }
        AlertDialog(
            onDismissRequest = { showFilters = false },
            title = { Text(stringResource(KMR.strings.picacg_filter_title)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedTextField(draftFilter.category, { draftFilter = draftFilter.copy(category = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_category)) }, singleLine = true)
                    }
                    item {
                        OutlinedTextField(draftFilter.tag, { draftFilter = draftFilter.copy(tag = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_tag)) }, singleLine = true)
                    }
                    item {
                        OutlinedTextField(draftFilter.author, { draftFilter = draftFilter.copy(author = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_author)) }, singleLine = true)
                    }
                    item {
                        OutlinedTextField(draftFilter.creatorId, { draftFilter = draftFilter.copy(creatorId = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_creator)) }, singleLine = true)
                    }
                    item {
                        OutlinedTextField(draftFilter.chineseTeam, { draftFilter = draftFilter.copy(chineseTeam = it) }, label = { Text(stringResource(KMR.strings.picacg_filter_team)) }, singleLine = true)
                    }
                    item { Text(stringResource(KMR.strings.picacg_sort)) }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(stringResource(KMR.strings.picacg_latest) to "dd", stringResource(KMR.strings.picacg_oldest) to "da", stringResource(KMR.strings.picacg_most_likes) to "ld", stringResource(KMR.strings.picacg_most_views) to "vd").forEach { (label, code) ->
                                FilterChip(draftFilter.sort == code, onClick = { draftFilter = draftFilter.copy(sort = code) }, label = { Text(label) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectMode(PicacgFeedMode.FILTERED, draftFilter.normalized())
                    showFilters = false
                }) { Text(stringResource(KMR.strings.picacg_apply)) }
            },
            dismissButton = { TextButton(onClick = { showFilters = false }) { Text(stringResource(KMR.strings.picacg_cancel)) } },
        )
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

private fun modeForCurrentFilter(): PicacgFeedMode = PicacgFeedMode.FILTERED

private fun PicacgBrowseFilter.summary(): String = listOf(category, tag, author, creatorId, chineseTeam)
    .filter(String::isNotBlank)
    .joinToString(" · ")
    .ifBlank { sort }
