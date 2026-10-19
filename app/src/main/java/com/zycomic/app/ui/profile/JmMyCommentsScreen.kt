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
import androidx.compose.material3.AlertDialog
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
import com.zycomic.app.data.repository.JmMyCommentsPolicy
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
    val total: Int? = null,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val deletingCommentId: String? = null,
    val deleteError: String? = null,
    val requestedPage: Int = 1,
)

private data class PendingCommentDelete(val comment: JmComment, val uid: String, val revision: Long)

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
    var pendingDelete by remember(sessionRevision, accountName) { mutableStateOf<PendingCommentDelete?>(null) }
    val scope = rememberCoroutineScope()
    val loadingText = stringResource(KMR.strings.jm_loading)
    val loadFailedText = stringResource(KMR.strings.jm_comments_load_failed)
    val deleteFailedText = stringResource(KMR.strings.jm_comments_delete_failed)
    val invalidDeleteContextText = stringResource(KMR.strings.jm_comments_delete_context_invalid)

    fun load(page: Int, append: Boolean) {
        if (state.loading || state.deletingCommentId != null || !loggedIn || uid.isNullOrBlank()) return
        val requestedRevision = sessionRevision
        state = state.copy(loading = true, error = null, requestedPage = page)
        scope.launch {
            try {
                val result = JmMyCommentsRepository.getPage(page)
                if (requestedRevision != JmAuthStore.sessionRevision || result.uid != JmAuthStore.uid()) return@launch
                state = JmMyCommentsUiState(
                    comments = (if (append) state.comments + result.comments else result.comments).distinctBy { it.id },
                    page = page,
                    total = result.total,
                    hasMore = result.hasMore,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestedRevision != JmAuthStore.sessionRevision) return@launch
                state = state.copy(loading = false, error = error.message ?: loadFailedText)
            }
        }
    }

    fun delete(comment: JmComment, expectedUid: String, expectedRevision: Long) {
        if (state.loading || state.deletingCommentId != null) return
        if (!JmMyCommentsPolicy.canDelete(comment.id, comment.aid, comment.bid, comment.nid)) {
            state = state.copy(deleteError = invalidDeleteContextText)
            return
        }
        state = state.copy(deletingCommentId = comment.id, deleteError = null)
        scope.launch {
            try {
                JmMyCommentsRepository.deleteComment(comment, expectedUid, expectedRevision)
                if (expectedRevision != JmAuthStore.sessionRevision || expectedUid != JmAuthStore.uid()) return@launch
                state = state.copy(
                    comments = state.comments.filterNot { it.id == comment.id },
                    total = state.total?.minus(1)?.coerceAtLeast(0),
                    deletingCommentId = null,
                    deleteError = null,
                )
                load(1, append = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (expectedRevision != JmAuthStore.sessionRevision || expectedUid != JmAuthStore.uid()) return@launch
                state = state.copy(
                    deletingCommentId = null,
                    deleteError = error.message ?: deleteFailedText,
                )
            } finally {
                if (expectedRevision == JmAuthStore.sessionRevision && expectedUid == JmAuthStore.uid() && state.deletingCommentId == comment.id) {
                    state = state.copy(deletingCommentId = null)
                }
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
                        IconButton(onClick = { load(1, append = false) }, enabled = !state.loading && state.deletingCommentId == null) {
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
            if (state.total != null && state.page > 0) {
                item {
                    Text(stringResource(KMR.strings.jm_comments_count, state.total ?: 0), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            }
            if (state.deleteError != null) {
                item {
                    Text(state.deleteError.orEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            items(state.comments, key = { it.id }) { comment ->
                JmMyCommentItem(
                    comment = comment,
                    onOpenManga = onOpenManga,
                    deleting = state.deletingCommentId == comment.id,
                    deleteEnabled = !state.loading && state.deletingCommentId == null && JmMyCommentsPolicy.canDelete(comment.id, comment.aid, comment.bid, comment.nid),
                    onDelete = { pendingDelete = PendingCommentDelete(comment, uid.orEmpty(), sessionRevision) },
                )
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

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (state.deletingCommentId == null) pendingDelete = null },
            title = { Text(stringResource(KMR.strings.jm_comment_delete_confirm_title)) },
            text = {
                Text(stringResource(KMR.strings.jm_comment_delete_confirm_message, pending.comment.mangaId.ifBlank { stringResource(KMR.strings.jm_unknown) }))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        delete(pending.comment, pending.uid, pending.revision)
                    },
                    enabled = state.deletingCommentId == null,
                ) { Text(stringResource(KMR.strings.jm_comment_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, enabled = state.deletingCommentId == null) { Text(stringResource(KMR.strings.jm_cancel)) }
            },
        )
    }
}

@Composable
private fun JmMyCommentItem(
    comment: JmComment,
    onOpenManga: (String) -> Unit,
    deleting: Boolean,
    deleteEnabled: Boolean,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(comment.time.ifBlank { "—" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (comment.mangaId.isNotBlank()) {
                Text(
                    stringResource(KMR.strings.jm_comment_album_number, comment.mangaId),
                    modifier = Modifier.clickable { onOpenManga("jm:${comment.mangaId}") },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (
                    comment.mangaName.isNotBlank() &&
                    !comment.mangaName.trim().matches(Regex("(?i)(?:JM\\s*#?\\s*)?${Regex.escape(comment.mangaId)}"))
                ) {
                    Text(comment.mangaName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(stringResource(KMR.strings.jm_comment_album_id_missing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(comment.content, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDelete, enabled = deleteEnabled && !deleting) {
                if (deleting) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(if (deleting) KMR.strings.jm_comment_deleting else KMR.strings.jm_comment_delete))
            }
            if (!JmMyCommentsPolicy.canDelete(comment.id, comment.aid, comment.bid, comment.nid)) {
                Text(stringResource(KMR.strings.jm_comments_delete_context_invalid), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        androidx.compose.material3.HorizontalDivider()
    }
}
