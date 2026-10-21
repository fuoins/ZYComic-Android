package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgComicState
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.picacg.filter.PicacgBlockKeywordStore
import com.zycomic.app.reader.PicacgSource
import com.zycomic.app.reader.ReaderLauncher
import com.zycomic.app.ui.components.PicacgImageLoader
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
    var manga by remember(selectedId, session) { mutableStateOf<Manga?>(null) }
    var state by remember(selectedId, session) { mutableStateOf<PicacgComicState?>(null) }
    var related by remember(selectedId, session, blockKeywords) { mutableStateOf<List<Manga>>(emptyList()) }
    var relatedError by remember(selectedId, session) { mutableStateOf<String?>(null) }
    var error by remember(selectedId, session) { mutableStateOf<String?>(null) }
    var busy by remember(selectedId, session) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var login by remember { mutableStateOf(false) }
    var comments by remember(selectedId, session) { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf<String?>(null) }
    val invalidId = stringResource(KMR.strings.picacg_invalid_id)
    val uncertainWrite = stringResource(KMR.strings.picacg_write_uncertain)
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedId, session, loggedIn, refresh) {
        if (!loggedIn) return@LaunchedEffect
        if (!bookId.startsWith("picacg:") || selectedId.isBlank()) {
            error = invalidId
            return@LaunchedEffect
        }
        error = null
        try {
            val response = PicacgRepository.detail(selectedId)
            if (sessionIsCurrent(session)) manga = response
            val metadata = PicacgRepository.comicState(selectedId)
            if (sessionIsCurrent(session)) state = metadata
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (sessionIsCurrent(session)) error = failure.message
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
    fun openChapter(detail: Manga, chapterId: String) {
        if (busy || !sessionIsCurrent(session)) return
        busy = true
        scope.launch {
            try {
                ReaderLauncher.launch(context, detail, chapterId, sourceId = PicacgSource.id)
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
        if (busy || state == null || !sessionIsCurrent(session)) return
        busy = true
        state = null
        error = null
        scope.launch {
            try {
                if (favourite) PicacgRepository.toggleFavourite(selectedId) else PicacgRepository.toggleLike(selectedId)
                val result = PicacgRepository.comicState(selectedId)
                if (sessionIsCurrent(session)) state = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Keep actions disabled until an explicit GET refresh resolves an ambiguous write.
                if (sessionIsCurrent(session)) error = uncertainWrite
            } finally {
                busy = false
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    TextButton(onClick = onClose) { Text(stringResource(KMR.strings.picacg_back)) }
                    TextButton(onClick = {
                        state = null
                        refresh++
                    }, enabled = !busy) { Text(stringResource(KMR.strings.picacg_profile_refresh)) }
                }
                if (!loggedIn) {
                    Text(stringResource(KMR.strings.picacg_session_required))
                    Button(onClick = { login = true }) { Text(stringResource(KMR.strings.picacg_login_action)) }
                } else {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val detail = manga
                    if (detail == null && error == null) CircularProgressIndicator()
                    if (detail != null) {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                HorizontalDivider()
                                Row {
                                    TextButton(onClick = { changeInteraction(true) }, enabled = metadata != null && !busy) { Text(stringResource(if (metadata?.isFavourite == true) KMR.strings.picacg_favourited else KMR.strings.picacg_favourite)) }
                                    TextButton(onClick = { changeInteraction(false) }, enabled = metadata != null && !busy) { Text(stringResource(if (metadata?.isLiked == true) KMR.strings.picacg_liked else KMR.strings.picacg_like) + " · ${metadata?.likesCount ?: 0}") }
                                    TextButton(onClick = { comments = true }, enabled = metadata != null && !busy) { Text(stringResource(KMR.strings.picacg_comments_title) + " · ${metadata?.commentsCount ?: 0}") }
                                }
                                if (busy) CircularProgressIndicator()
                            }
                            if (detail.chapterList.isEmpty()) {
                                item { Text(stringResource(KMR.strings.picacg_no_chapters), modifier = Modifier.padding(horizontal = 16.dp)) }
                            } else {
                                item {
                                    MangaDetailChapterGrid(
                                        chapters = detail.chapterList,
                                        onChapterClick = { chapter -> openChapter(detail, chapter.id) },
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item { Text(stringResource(KMR.strings.picacg_recommendations), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
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
            refresh++
        }
    }
}
