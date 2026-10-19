package com.zycomic.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.dto.JmComment
import com.zycomic.app.data.repository.JmMyCommentsRepository
import com.zycomic.app.net.JmAuthStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

private data class JmMyCommentsUiState(
    val comments: List<JmComment> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val requestedPage: Int = 1,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmMyCommentsScreen(
    onBack: () -> Unit,
    onRequireLogin: () -> Unit,
    onOpenManga: (String) -> Unit,
    isDetailOpen: Boolean,
) {
    val loggedIn by JmAuthStore.loggedInFlow.collectAsState()
    val accountName by JmAuthStore.accountNameFlow.collectAsState()
    val sessionRevision by JmAuthStore.sessionRevisionFlow.collectAsState()
    val uid = JmAuthStore.uid()
    var state by remember(sessionRevision, accountName) { mutableStateOf(JmMyCommentsUiState()) }
    val scope = rememberCoroutineScope()
    val loadingText = stringResource(KMR.strings.jm_loading)

    fun load(page: Int, append: Boolean) {
        if (state.loading || !loggedIn || uid.isNullOrBlank()) return
        val requestedRevision = sessionRevision
        state = state.copy(loading = true, error = null, requestedPage = page)
        scope.launch {
            try {
                val result = JmMyCommentsRepository.getPage(page)
                if (requestedRevision != JmAuthStore.sessionRevision || result.uid != JmAuthStore.uid()) return@launch
                state = JmMyCommentsUiState(
                    comments = (if (append) state.comments + result.comments else result.comments).distinctBy { it.id },
                    page = page,
                    hasMore = result.hasMore,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestedRevision != JmAuthStore.sessionRevision) return@launch
                state = state.copy(loading = false, error = error.message ?: "JM 留言记录加载失败")
            }
        }
    }

    LaunchedEffect(sessionRevision, loggedIn, uid) {
        if (loggedIn && !uid.isNullOrBlank()) load(1, append = false)
    }
    BackHandler(enabled = !isDetailOpen, onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(KMR.strings.jm_my_comments_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(KMR.strings.jm_back))
                    }
                },
                actions = {
                    if (loggedIn && !uid.isNullOrBlank()) {
                        IconButton(onClick = { load(1, append = false) }, enabled = !state.loading) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(KMR.strings.jm_retry))
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        ScrollbarLazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !loggedIn -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(KMR.strings.jm_comments_login_required), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = onRequireLogin) { Text(stringResource(KMR.strings.jm_login)) }
                    }
                }
                uid.isNullOrBlank() -> item {
                    Text(
                        stringResource(KMR.strings.jm_comments_uid_missing),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.loading && state.comments.isEmpty() -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(loadingText)
                    }
                }
                state.error != null && state.comments.isEmpty() -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { load(state.requestedPage, append = state.page > 0) }, enabled = !state.loading) {
                            Text(stringResource(KMR.strings.jm_retry))
                        }
                    }
                }
                state.comments.isEmpty() && state.page > 0 -> item {
                    Text(
                        stringResource(KMR.strings.jm_comments_empty),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.comments, key = { it.id }) { comment ->
                JmMyCommentItem(comment = comment, onOpenManga = onOpenManga)
            }
            if (state.error != null && state.comments.isNotEmpty()) {
                item {
                    Text(state.error.orEmpty(), modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = { load(state.requestedPage, append = state.requestedPage > state.page) },
                        enabled = !state.loading,
                    ) {
                        Text(stringResource(KMR.strings.jm_retry))
                    }
                }
            }
            if (state.hasMore && state.comments.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = { load(state.page + 1, append = true) },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        if (state.loading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(if (state.loading) KMR.strings.jm_loading else KMR.strings.jm_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun JmMyCommentItem(comment: JmComment, onOpenManga: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(comment.time.ifBlank { "—" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (comment.mangaId.isNotBlank()) {
                Text(
                    comment.mangaName.ifBlank { "禁漫 #${comment.mangaId}" },
                    modifier = Modifier.clickable { onOpenManga("jm:${comment.mangaId}") },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.HorizontalDivider()
    }
}
