package com.zycomic.app.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.StartupPlatformPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun JmAlbumJumpDialog(onDismiss: () -> Unit, onOpenManga: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var invalidId by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var randomError by remember { mutableStateOf<String?>(null) }
    val emptyMessage = stringResource(KMR.strings.jm_random_album_empty)
    val errorMessage = stringResource(KMR.strings.jm_random_album_failed)

    fun open(route: String) {
        if (StartupPlatformPolicy.checkSelection(context, ContentPlatform.JM)) {
            onDismiss()
            onOpenManga(route)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.jm_album_jump_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalidId = false
                    },
                    label = { Text(stringResource(KMR.strings.jm_album_jump_id_label)) },
                    singleLine = true,
                    isError = invalidId,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = if (invalidId) {
                        { Text(stringResource(KMR.strings.jm_album_jump_invalid_id)) }
                    } else {
                        null
                    },
                )
                randomError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        // AlertDialog puts dismissButton directly left of confirmButton, so use a full-width
        // row here to keep the blind box anchored at the lower-left, not beside the ID label.
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        if (loading) return@TextButton
                        loading = true
                        randomError = null
                        scope.launch {
                            try {
                                val route = JmRandomAlbumRepository.randomRoute()
                                if (route == null) randomError = emptyMessage else open(route)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                randomError = errorMessage
                            } finally {
                                loading = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (loading) KMR.strings.jm_random_album_loading else KMR.strings.jm_random_album))
                }
                Row {
                    TextButton(onClick = onDismiss) { Text(stringResource(KMR.strings.jm_album_jump_cancel)) }
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            val route = JmAlbumJumpPolicy.route(input)
                            if (route == null) invalidId = true else open(route)
                        },
                    ) { Text(stringResource(KMR.strings.jm_album_jump_submit)) }
                }
            }
        },
    )
}
