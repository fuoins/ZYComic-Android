package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgComment
import com.zycomic.app.picacg.PicacgRepository
import com.zycomic.app.ui.components.PicacgCollectionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** Every thread and draft belongs to the current Picacg session, never another platform/account. */
@Composable
internal fun PicacgCommentsDialog(comicId: String, allowComment: Boolean, onClose: () -> Unit) {
    val session by PicacgAuthStore.sessionRevision.collectAsState()
    val loggedIn by PicacgAuthStore.loggedIn.collectAsState()
    var parent by remember(comicId, session) { mutableStateOf<PicacgComment?>(null) }
    var page by remember(comicId, session, parent?.id) { mutableStateOf(1) }
    var comments by remember(comicId, session, parent?.id) { mutableStateOf<List<PicacgComment>>(emptyList()) }
    var more by remember(comicId, session, parent?.id) { mutableStateOf(false) }
    var draft by remember(comicId, session, parent?.id) { mutableStateOf("") }
    var loading by remember(comicId, session, parent?.id) { mutableStateOf(false) }
    var blockedAt by remember(comicId, session) { mutableStateOf<PicacgCommentWriteLocation?>(null) }
    val writesBlocked = blockedAt != null
    var writing by remember(comicId, session) { mutableStateOf(false) }
    var error by remember(comicId, session, parent?.id) { mutableStateOf<String?>(null) }
    var notice by remember(comicId, session, parent?.id) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val uncertainWrite = stringResource(KMR.strings.picacg_write_uncertain)
    val sent = stringResource(KMR.strings.picacg_comment_sent)
    fun current() = loggedIn && session == PicacgAuthStore.sessionRevision.value && PicacgAuthStore.loggedIn.value
    LaunchedEffect(comicId, session, loggedIn, parent?.id, page, refresh) {
        if (!loggedIn) return@LaunchedEffect
        loading = true
        error = null
        try {
            val result = PicacgRepository.comments(comicId, page, replyTo = parent?.id)
            if (current()) {
                comments = PicacgCollectionPolicy.merge(comments, result.items, page == 1) { it.id }
                more = result.hasMore
                if (PicacgCommentWritePolicy.resolves(blockedAt, PicacgCommentWriteLocation(parent?.id, page, refresh))) blockedAt = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (current()) error = failure.message
        } finally {
            loading = false
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    TextButton(onClick = onClose) { Text(stringResource(KMR.strings.picacg_back)) }
                    Text(stringResource(KMR.strings.picacg_comments_title), style = MaterialTheme.typography.titleLarge)
                }
                if (!loggedIn) {
                    Text(stringResource(KMR.strings.picacg_session_required))
                } else {
                    parent?.let { root ->
                        TextButton(onClick = { parent = null }, enabled = !writing && !writesBlocked) { Text(stringResource(KMR.strings.picacg_comments_root)) }
                        Text(root.userName + ": " + root.content)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    notice?.let { Text(it) }
                    TextButton(onClick = {
                        page = blockedAt?.page ?: 1
                        refresh++
                    }, enabled = !loading && !writing) { Text(stringResource(KMR.strings.picacg_profile_refresh)) }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (loading) item { CircularProgressIndicator() }
                        items(comments, key = { it.id }) { comment ->
                            Column {
                                Text(comment.userName, style = MaterialTheme.typography.titleSmall)
                                Text(comment.content)
                                Text(comment.createdAt, style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = {
                                        if (!current() || writing || writesBlocked) return@TextButton
                                        writing = true
                                        error = null
                                        scope.launch {
                                            try {
                                                PicacgRepository.toggleCommentLike(comment.id)
                                                if (current()) {
                                                    page = 1
                                                    refresh++
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                if (current()) {
                                                    error = uncertainWrite
                                                    blockedAt = PicacgCommentWriteLocation(parent?.id, page, refresh)
                                                }
                                            } finally {
                                                writing = false
                                            }
                                        }
                                    }, enabled = !writing && !writesBlocked && !loading) {
                                        Text(stringResource(if (comment.isLiked) KMR.strings.picacg_liked else KMR.strings.picacg_like) + " · ${comment.likesCount}")
                                    }
                                    // Replies are rooted at the top-level comment, matching the documented child-thread API.
                                    if (parent == null) {
                                        TextButton(onClick = { parent = comment }, enabled = !writing && !writesBlocked) {
                                            Text(stringResource(KMR.strings.picacg_replies) + " · ${comment.replyCount}")
                                        }
                                    }
                                }
                            }
                        }
                        if (!loading && error == null && comments.isEmpty()) item { Text(stringResource(KMR.strings.picacg_comments_empty)) }
                        if (more) {
                            item {
                                TextButton(onClick = { if (error == null) page++ else refresh++ }, enabled = !loading && !writing && !writesBlocked) { Text(stringResource(if (error == null) KMR.strings.picacg_load_more else KMR.strings.picacg_retry)) }
                            }
                        }
                    }
                    if (allowComment) {
                        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(KMR.strings.picacg_comment_draft)) }, enabled = !writing)
                        Button(onClick = {
                            if (!current() || writing || writesBlocked || draft.isBlank()) return@Button
                            val content = draft.trim()
                            val replyTo = parent?.id
                            writing = true
                            error = null
                            notice = null
                            scope.launch {
                                try {
                                    PicacgRepository.sendComment(comicId, content, replyTo)
                                    if (current()) {
                                        draft = ""
                                        notice = sent
                                        page = 1
                                        refresh++
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    // Do not retry a POST: refresh first to inspect whether it reached the server.
                                    if (current()) {
                                        error = uncertainWrite
                                        blockedAt = PicacgCommentWriteLocation(parent?.id, page, refresh)
                                    }
                                } finally {
                                    writing = false
                                }
                            }
                        }, enabled = !writing && !writesBlocked && !loading && draft.isNotBlank()) { Text(stringResource(KMR.strings.picacg_comment_send)) }
                    } else {
                        Text(stringResource(KMR.strings.picacg_comments_disabled))
                    }
                }
            }
        }
    }
}
