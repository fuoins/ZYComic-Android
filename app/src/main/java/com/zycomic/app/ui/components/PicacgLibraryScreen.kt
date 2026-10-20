package com.zycomic.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/** Picacg's online collection is never backed by Manwa/JM account repositories. */
@Composable
fun PicacgLibraryScreen(
    history: Boolean,
    onRequireLogin: () -> Unit,
    onOpenManga: (String) -> Unit,
    onSelectPlatform: (ContentPlatform) -> Unit,
) {
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    val session by PicacgAuthStore.sessionRevision.collectAsState()
    val favouritesRevision by PicacgRepository.favouritesRevision.collectAsState()
    var comics by remember(session) { mutableStateOf<List<Manga>>(emptyList()) }
    var page by remember(session) { mutableIntStateOf(1) }
    var hasMore by remember(session) { mutableStateOf(false) }
    var loading by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var query by remember(session) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loadedFavouritesRevision by remember(session) { mutableLongStateOf(favouritesRevision) }
    val loadFailed = stringResource(KMR.strings.picacg_library_load_failed)

    LaunchedEffect(session, loggedIn, page, refresh, history, favouritesRevision) {
        if (!loggedIn || history) return@LaunchedEffect
        if (loadedFavouritesRevision != favouritesRevision) {
            loadedFavouritesRevision = favouritesRevision
            comics = emptyList()
            hasMore = false
            page = 1
            refresh++
            return@LaunchedEffect
        }
        val startedSession = session
        val requestedPage = page
        loading = true
        error = null
        try {
            val result = PicacgRepository.favourites(page = requestedPage)
            if (!PicacgCollectionPolicy.acceptsResult(startedSession, PicacgAuthStore.sessionRevision.value, PicacgAuthStore.loggedIn.value)) return@LaunchedEffect
            comics = PicacgCollectionPolicy.merge(comics, result.items, firstPage = requestedPage == 1) { it.id }
            hasMore = result.hasMore
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (PicacgAuthStore.sessionRevision.value == startedSession) error = failure.message ?: loadFailed
        } finally {
            loading = false
        }
    }
    val filtered = comics.filter { PicacgCollectionPolicy.matchesSearch(query, it.name) }
    Scaffold(
        topBar = { scrollBehavior ->
            LibraryHistoryToolbar(
                platform = ContentPlatform.PICACG,
                onSelectPlatform = onSelectPlatform,
                searchQuery = query,
                onChangeSearchQuery = { query = it },
                titleContent = { Text(stringResource(if (history) KMR.strings.picacg_history_title else KMR.strings.picacg_library_title)) },
                searchSupported = !history && loggedIn,
                refreshSupported = !history && loggedIn && !loading,
                filterSupported = false,
                onRefresh = {
                    page = 1
                    refresh++
                },
                onFilter = {},
                onEnterSelection = {},
                selectionMode = false,
                selectionCount = 0,
                onExitSelection = {},
                onSelectAll = {},
                onInvertSelection = {},
                scrollBehavior = scrollBehavior,
                history = history,
                selectionSupported = false,
            )
        },
    ) { contentPadding ->
        when {
            history -> PicacgLocalHistoryScreen(
                onOpenManga = onOpenManga,
                modifier = Modifier.padding(contentPadding),
            )
            !loggedIn -> Column(modifier = Modifier.padding(contentPadding).padding(24.dp)) {
                Text(stringResource(KMR.strings.picacg_library_login_required))
                TextButton(onClick = onRequireLogin) { Text(stringResource(KMR.strings.picacg_login_action)) }
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
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.id }) { comic ->
                    PicacgMangaCard(
                        manga = comic,
                        onClick = { onOpenManga("picacg:${comic.id}") },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (hasMore) {
                            TextButton(onClick = { if (error == null) page++ else refresh++ }, enabled = !loading) {
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
}
