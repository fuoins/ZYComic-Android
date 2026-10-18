package com.zycomic.app.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zycomic.app.data.dto.Manga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun JmDetailActions(vm: MangaDetailViewModel, manga: Manga, onSaveCover: () -> Unit, onShareCover: () -> Unit) {
    val busy by vm.jmActionBusy.collectAsState()
    val commentCount by vm.jmCommentCount.collectAsState()
    val needsLogin by vm.jmNeedLogin.collectAsState()
    val account by vm.jmAccountName.collectAsState()
    var showAccount by remember(vm) { mutableStateOf(false) }
    var showComments by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(vm, needsLogin) {
        if (needsLogin) {
            showAccount = true
            vm.jmNeedLogin.value = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::toggleFavorite, enabled = !busy, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(if (manga.fav == 1) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, null)
                    Text(stringResource(if (manga.fav == 1) KMR.strings.jm_favorited else KMR.strings.jm_favorite), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = vm::toggleJmLike, enabled = !busy, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(if (manga.jmLiked == true) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp, null)
                    Text(stringResource(if (manga.jmLiked == true) KMR.strings.jm_liked_count else KMR.strings.jm_like_count, manga.jmLikes?.toString() ?: "—"), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = {
                showComments = true
                vm.loadJmComments()
            }, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.ChatBubbleOutline, null)
                    Text(stringResource(KMR.strings.jm_comment_count, commentCount?.toString() ?: "—"), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            JmDetailOverflow(manga.name, manga.id, onSaveCover, onShareCover, Modifier.weight(1f))
        }
    }
    if (showComments) JmCommentsDialog(vm, onDismiss = { showComments = false })
    if (showAccount) JmAccountDialog(vm, account, onDismiss = { showAccount = false })
}

@Composable
private fun JmAccountDialog(vm: MangaDetailViewModel, account: String?, onDismiss: () -> Unit) {
    val busy by vm.jmLoginBusy.collectAsState()
    val error by vm.jmLoginError.collectAsState()
    var username by remember { mutableStateOf("") }
    // Password is held only while this dialog is composed; never saved into saved-instance state.
    var password by remember { mutableStateOf("") }
    LaunchedEffect(account) {
        if (account != null) password = ""
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(KMR.strings.jm_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account == null) {
                    Text(stringResource(KMR.strings.jm_login_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(KMR.strings.jm_username)) }, singleLine = true, enabled = !busy)
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(KMR.strings.jm_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
                } else {
                    Text(account)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        },
        confirmButton = {
            if (account == null) {
                TextButton(onClick = {
                    vm.loginJm(username.trim(), password)
                    password = ""
                }, enabled = !busy && username.isNotBlank() && password.isNotEmpty()) { Text(stringResource(KMR.strings.jm_login)) }
            } else {
                TextButton(onClick = {
                    vm.logoutJm()
                    onDismiss()
                }) { Text(stringResource(KMR.strings.jm_logout)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(KMR.strings.jm_close)) } },
    )
}

@Composable
private fun JmCommentsDialog(vm: MangaDetailViewModel, onDismiss: () -> Unit) {
    val comments by vm.jmComments.collectAsState()
    val loading by vm.jmCommentsLoading.collectAsState()
    val error by vm.jmCommentsError.collectAsState()
    val hasMore by vm.jmCommentsHasMore.collectAsState()
    val busy by vm.jmActionBusy.collectAsState()
    val account by vm.jmAccountName.collectAsState()
    val context = LocalContext.current
    val jmImageLoader = remember(context) { com.zycomic.app.ui.components.JmCoverImageLoader.get(context) }
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.jm_comments)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(comments, key = { it.id }) { comment ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (comment.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = comment.avatarUrl,
                                        contentDescription = null,
                                        imageLoader = jmImageLoader,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(36.dp).clip(CircleShape),
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.PersonOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(36.dp).clip(CircleShape),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    comment.username,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(comment.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(comment.content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 44.dp))
                            HorizontalDivider()
                        }
                    }
                    item {
                        if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                        if (!loading && comments.isEmpty() && error == null) Text(stringResource(KMR.strings.jm_no_comments))
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.loadJmComments(nextPage = comments.isNotEmpty()) }) { Text(stringResource(KMR.strings.jm_retry)) }
                        }
                        if (hasMore && !loading && error == null) {
                            TextButton(onClick = { vm.loadJmComments(nextPage = true) }) { Text(stringResource(KMR.strings.jm_load_more)) }
                        }
                    }
                }
                if (account == null) {
                    TextButton(onClick = { vm.jmNeedLogin.value = true }) { Text(stringResource(KMR.strings.jm_login_first)) }
                } else {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(stringResource(KMR.strings.jm_comment_placeholder)) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.postJmComment(draft) { draft = "" } }, enabled = account != null && draft.isNotBlank() && !busy) { Text(stringResource(KMR.strings.jm_comment_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(KMR.strings.jm_close)) } },
    )
}
