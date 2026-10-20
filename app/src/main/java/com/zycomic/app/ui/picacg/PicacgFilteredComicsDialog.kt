package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.ui.components.PicacgCollectionPolicy
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun PicacgFilteredComicsDialog(filter: PicacgBrowseFilter, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val session by PicacgAuthStore.sessionRevision.collectAsState()
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    var comics by remember(session, filter) { mutableStateOf<List<Manga>>(emptyList()) }
    var page by remember(session, filter) { mutableStateOf(1) }
    var more by remember(session, filter) { mutableStateOf(false) }
    var loading by remember(session, filter) { mutableStateOf(false) }
    var error by remember(session, filter) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(session, loggedIn, filter, page, refresh) {
        if (!loggedIn) return@LaunchedEffect
        loading = true
        error = null
        try {
            val result = PicacgRepository.browse(page, filter.category, filter.sort, filter.tag, filter.author, filter.creatorId, filter.chineseTeam)
            if (sessionIsCurrent(session)) {
                comics = PicacgCollectionPolicy.merge(comics, result.items, page == 1) { it.id }
                more = result.hasMore
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (sessionIsCurrent(session)) error = failure.message
        } finally {
            loading = false
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text(stringResource(KMR.strings.picacg_back)) }
                Text(listOf(filter.category, filter.tag, filter.author, filter.chineseTeam, filter.creatorId).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.titleLarge)
                if (!loggedIn) Text(stringResource(KMR.strings.picacg_session_required))
                if (loading) CircularProgressIndicator()
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(KMR.strings.picacg_retry)) }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(comics, key = { it.id }) { comic ->
                        TextButton(onClick = { if (sessionIsCurrent(session)) onOpen(comic.id) }) { Text(comic.name) }
                    }
                    if (!loading && error == null && comics.isEmpty()) item { Text(stringResource(KMR.strings.picacg_search_empty)) }
                    if (more) item { TextButton(onClick = { page++ }, enabled = !loading && error == null) { Text(stringResource(KMR.strings.picacg_load_more)) } }
                }
            }
        }
    }
}
