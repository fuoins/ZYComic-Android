package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.picacg.PicacgCategory
import com.zycomic.app.picacg.PicacgRepository
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PicacgSearchCategoriesDialog(selected: List<String>, onApply: (List<String>) -> Unit, onClose: () -> Unit) {
    val session by PicacgAuthStore.sessionRevision.collectAsState()
    var categories by remember(session) { mutableStateOf<List<PicacgCategory>>(emptyList()) }
    var chosen by remember(session) { mutableStateOf(selected.toSet()) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var loading by remember(session) { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(session, refresh) {
        if (!PicacgAuthStore.loggedIn.value) return@LaunchedEffect
        loading = true
        error = null
        try {
            val result = PicacgRepository.categories()
            if (sessionIsCurrent(session)) categories = result.filter { it.active && !it.isWeb }.distinctBy { it.title }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (sessionIsCurrent(session)) error = failure.message
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(KMR.strings.picacg_search_categories)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(KMR.strings.picacg_search_categories_hint))
                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(KMR.strings.picacg_retry)) }
                TextButton(onClick = { chosen = emptySet() }) { Text(stringResource(KMR.strings.picacg_all)) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(categories, key = { it.title }) { category ->
                        TextButton(onClick = {
                            chosen = if (category.title in chosen) chosen - category.title else chosen + category.title
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text((if (category.title in chosen) "✓ " else "") + category.title)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (sessionIsCurrent(session)) onApply(chosen.toList()) }, enabled = !loading && PicacgAuthStore.loggedIn.value) { Text(stringResource(KMR.strings.picacg_apply)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(KMR.strings.picacg_cancel)) } },
    )
}
