package com.zycomic.app.ui.picacg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgComicState
import com.zycomic.app.picacg.PicacgDetailCache
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.picacg.filter.PicacgBlockKeywordStore
import com.zycomic.app.reader.PicacgSource
import com.zycomic.app.reader.ReaderLauncher
import com.zycomic.app.ui.components.PicacgImageLoader
import com.zycomic.app.ui.manga.MangaDetailBackdrop
import com.zycomic.app.ui.manga.MangaDetailChapterGrid
import com.zycomic.app.ui.manga.MangaDetailCoverHeader
import com.zycomic.app.ui.manga.MangaDetailDescription
import com.zycomic.app.ui.manga.MangaDetailRecommendationRow
import com.zycomic.app.ui.manga.MangaDetailTagChips
import com.zycomic.app.ui.search.SearchOverlay
import com.zycomic.app.ui.search.SearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PicacgDetailOverlay(bookId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    val session by PicacgAuthStore.sessionRevision.collectAsState()
    val blockKeywords by PicacgBlockKeywordStore.keywords.collectAsState()
    var selectedId by remember(bookId) { mutableStateOf(bookId.removePrefix("picacg:")) }
    val snapshot = remember(selectedId, session, loggedIn) {
        if (loggedIn) PicacgDetailCache.get(session, selectedId) else null
    }
    var manga by remember(selectedId, session, loggedIn) { mutableStateOf(snapshot?.manga) }
    var state by remember(selectedId, session, loggedIn) { mutableStateOf(snapshot?.state) }
    var related by remember(selectedId, session, blockKeywords) { mutableStateOf<List<Manga>>(emptyList()) }
    var relatedError by remember(selectedId, session) { mutableStateOf<String?>(null) }
    var error by remember(selectedId, session) { mutableStateOf<String?>(null) }
    var busy by remember(selectedId, session) { mutableStateOf(false) }
    var chapterAscending by remember(selectedId) { mutableStateOf(false) }
    var refreshing by remember(selectedId, session, loggedIn) { mutableStateOf(loggedIn) }
    var refresh by remember { mutableStateOf(0) }
    var login by remember { mutableStateOf(false) }
    var comments by remember(selectedId, session) { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf<String?>(null) }
    val invalidId = stringResource(KMR.strings.picacg_invalid_id)
    val uncertainWrite = stringResource(KMR.strings.picacg_write_uncertain)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, session) { listState.scrollToItem(0) }
    LaunchedEffect(selectedId, session, loggedIn, refresh) {
        if (!loggedIn || !sessionIsCurrent(session)) return@LaunchedEffect
        PicacgDetailCache.activateSession(session, loggedIn)
        if (!bookId.startsWith("picacg:") || selectedId.isBlank()) {
            error = invalidId
            refreshing = false
            return@LaunchedEffect
        }
        error = null
        refreshing = true
        try {
            val response = PicacgRepository.detail(selectedId)
            if (sessionIsCurrent(session)) {
                manga = response
                PicacgDetailCache.put(session, selectedId, response, state)
            }
            val metadata = PicacgRepository.comicState(selectedId)
            if (sessionIsCurrent(session)) {
                state = metadata
                PicacgDetailCache.put(session, selectedId, response, metadata)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (sessionIsCurrent(session)) {
                state = null
                PicacgDetailCache.invalidateState(session, selectedId)
                error = failure.message ?: uncertainWrite
            }
        } finally {
            refreshing = false
        }
    }
    // Recommendations may fail independently: they must never block chapters or reading.
    LaunchedEffect(selectedId, session, loggedIn, refresh, blockKeywords) {
        if (!loggedIn || selectedId.isBlank()) return@LaunchedEffect
        relatedError = null
        try {
            val result = PicacgRepository.recommendations(selectedId)
            if (sessionIsCurrent(session)) related = result.filter { it.id != selectedId }.distinctBy { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (sessionIsCurrent(session)) relatedError = failure.message
        }
    }
    fun openChapter(detail: Manga, chapterId: String? = null) {
        if (busy || !sessionIsCurrent(session)) return
        busy = true
        scope.launch {
            try {
                val target = chapterId?.let { PicacgDetailReadingTarget(it) } ?: picacgDetailReadingTarget(detail)
                    ?: return@launch
                if (!sessionIsCurrent(session)) return@launch
                ReaderLauncher.launch(context, detail, target.chapterId, page = target.page, sourceId = PicacgSource.id)
                onClose()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (sessionIsCurrent(session)) error = failure.message
            } finally {
                busy = false
            }
        }
    }
    fun changeInteraction(favourite: Boolean) {
        if (busy || refreshing || state == null || !sessionIsCurrent(session)) return
        val requestId = selectedId
        busy = true
        state = null
        PicacgDetailCache.invalidateState(session, requestId)
        error = null
        scope.launch {
            try {
                if (favourite) PicacgRepository.toggleFavourite(requestId) else PicacgRepository.toggleLike(requestId)
                val result = PicacgRepository.comicState(requestId)
                if (sessionIsCurrent(session)) {
                    state = result
                    manga?.let { PicacgDetailCache.put(session, requestId, it, result) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Keep actions disabled until a GET retry resolves an ambiguous write; never retry the POST.
                if (sessionIsCurrent(session)) error = uncertainWrite
            } finally {
                busy = false
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                manga?.let { detail ->
                    MangaDetailBackdrop(
                        coverModel = detail.picx.ifBlank { detail.pic },
                        imageLoader = PicacgImageLoader.get(context),
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(KMR.strings.picacg_back))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { scope.launch { listState.animateScrollToItem(2) } },
                            enabled = manga != null && loggedIn && listState.layoutInfo.totalItemsCount > 2,
                        ) { Text(stringResource(KMR.strings.picacg_recommendations)) }
                    }
                    if (!loggedIn) {
                        Text(stringResource(KMR.strings.picacg_session_required), modifier = Modifier.padding(horizontal = 16.dp))
                        Button(onClick = { login = true }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(KMR.strings.picacg_login_action)) }
                    } else {
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                            TextButton(onClick = { refresh++ }, enabled = !busy && !refreshing) { Text(stringResource(KMR.strings.picacg_retry)) }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val detail = manga
                            if (detail == null && error == null) CircularProgressIndicator()
                            if (detail != null) {
                                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        val metadata = state
                                        MangaDetailCoverHeader(
                                            manga = detail,
                                            authors = listOfNotNull(detail.author.takeIf(String::isNotBlank), metadata?.chineseTeam?.takeIf(String::isNotBlank)),
                                            imageLoader = PicacgImageLoader.get(context),
                                            coverModel = detail.picx.ifBlank { detail.pic },
                                            onSearchTitle = { value ->
                                                PicacgDetailSearchPolicy.query(PicacgDetailSearchPolicy.Source.TITLE, value)?.let { searchTerm = it }
                                            },
                                            onSearchAuthor = { value ->
                                                PicacgDetailSearchPolicy.query(PicacgDetailSearchPolicy.Source.AUTHOR, value)?.let { searchTerm = it }
                                            },
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            trailingInfo = {
                                                metadata?.categories.orEmpty().firstOrNull()?.let { category ->
                                                    Text(category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                        )
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { changeInteraction(true) }, enabled = metadata != null && !busy && !refreshing, modifier = Modifier.weight(1f)) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(if (metadata?.isFavourite == true) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null)
                                                    Text(stringResource(if (metadata?.isFavourite == true) KMR.strings.picacg_favourited else KMR.strings.picacg_favourite), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            TextButton(onClick = { changeInteraction(false) }, enabled = metadata != null && !busy && !refreshing, modifier = Modifier.weight(1f)) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(if (metadata?.isLiked == true) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = null)
                                                    Text(stringResource(if (metadata?.isLiked == true) KMR.strings.picacg_liked else KMR.strings.picacg_like), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text("${metadata?.likesCount ?: 0}", modifier = Modifier.height(20.dp), textAlign = TextAlign.Center, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            TextButton(onClick = { comments = true }, enabled = metadata != null && !busy && !refreshing, modifier = Modifier.weight(1f)) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null)
                                                    Text(stringResource(KMR.strings.picacg_comments_title), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text("${metadata?.commentsCount ?: 0}", modifier = Modifier.height(20.dp), textAlign = TextAlign.Center, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                        HorizontalDivider()
                                        if (detail.text.isNotBlank()) MangaDetailDescription(detail.text)
                                        val searchTags = buildList {
                                            metadata?.categories.orEmpty().forEach(::add)
                                            metadata?.tags.orEmpty().forEach(::add)
                                        }.distinct()
                                        MangaDetailTagChips(
                                            tags = searchTags,
                                            onTagClick = { tag ->
                                                PicacgDetailSearchPolicy.query(PicacgDetailSearchPolicy.Source.TAG, tag)?.let { searchTerm = it }
                                            },
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                        )
                                        if (busy) CircularProgressIndicator()
                                    }
                                    if (detail.chapterList.isEmpty()) {
                                        item { Text(stringResource(KMR.strings.picacg_no_chapters), modifier = Modifier.padding(horizontal = 16.dp)) }
                                    } else {
                                        item {
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text("共 ${detail.chapterList.size} 章", style = MaterialTheme.typography.bodyMedium)
                                                    Row(
                                                        modifier = Modifier.clickable { chapterAscending = !chapterAscending },
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.SwapVert,
                                                            contentDescription = "切换排序",
                                                            modifier = Modifier.size(18.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            if (chapterAscending) "升序" else "降序",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                                MangaDetailChapterGrid(
                                                    chapters = if (chapterAscending) {
                                                        detail.chapterList.sortedBy { it.sort }
                                                    } else {
                                                        detail.chapterList.sortedByDescending { it.sort }
                                                    },
                                                    onChapterClick = { chapter -> openChapter(detail, chapter.id) },
                                                )
                                            }
                                        }
                                    }
                                    // Header and chapter section each occupy one item, so recommendations begin at index 2.
                                    item(key = "recommendations") { Text(stringResource(KMR.strings.picacg_recommendations), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
                                    relatedError?.let { failure -> item { Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) } }
                                    item {
                                        MangaDetailRecommendationRow(
                                            mangas = related,
                                            imageLoader = PicacgImageLoader.get(context),
                                            coverModel = { comic -> comic.picx.ifBlank { comic.pic } },
                                            onMangaClick = { comic -> selectedId = comic.id },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val readableDetail = manga
                if (loggedIn && readableDetail != null && readableDetail.chapterList.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = { if (!busy) openChapter(readableDetail) },
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(KMR.strings.picacg_start_or_continue_reading), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
    searchTerm?.let { query ->
        SearchOverlay(
            initialKeyword = query,
            initialProvider = SearchProvider.fromStorage(PicacgDetailSearchPolicy.SEARCH_PROVIDER),
            onClose = { searchTerm = null },
            onOpenManga = { id ->
                selectedId = id.removePrefix("picacg:")
                searchTerm = null
            },
        )
    }
    if (login) PicacgLoginDialog { login = false }
    if (comments && loggedIn) {
        PicacgCommentsDialog(selectedId, allowComment = state?.allowComment == true) {
            comments = false
            state = null
            PicacgDetailCache.invalidateState(session, selectedId)
            refresh++
        }
    }
}
