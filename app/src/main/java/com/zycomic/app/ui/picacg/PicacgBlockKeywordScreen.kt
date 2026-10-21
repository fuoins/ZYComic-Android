package com.zycomic.app.ui.picacg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.zycomic.app.picacg.filter.PicacgBlockKeywordStore
import com.zycomic.app.picacg.filter.PicacgKeywordAddResult
import com.zycomic.app.picacg.filter.PicacgKeywordFilter
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/** Management view can be opened from the Picacg profile even while signed out or offline. */
@Composable
fun PicacgBlockKeywordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember(context) { PicacgBlockKeywordStore.initialize(context) }
    val keywords by PicacgBlockKeywordStore.keywords.collectAsState()
    var entry by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val filteredKeywords = remember(keywords, query) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) keywords else keywords.filter { it.contains(normalizedQuery) }
    }
    val addKeyword = stringResource(KMR.strings.picacg_block_keyword_add)
    val keywordAdded = stringResource(KMR.strings.picacg_block_keyword_added)
    val invalidKeyword = stringResource(KMR.strings.picacg_block_keyword_invalid)
    val duplicateKeyword = stringResource(KMR.strings.picacg_block_keyword_duplicate)
    val addedPreset = stringResource(KMR.strings.picacg_block_keyword_preset_added)
    val addedNothing = stringResource(KMR.strings.picacg_block_keyword_preset_exists)

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(KMR.strings.picacg_block_keyword_back))
            }
            Text(stringResource(KMR.strings.picacg_block_keyword_title), style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(KMR.strings.picacg_block_keyword_description), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it.take(PicacgKeywordFilter.MAX_KEYWORD_LENGTH + 1) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(KMR.strings.picacg_block_keyword_input)) },
                supportingText = { Text("${entry.trim().length}/${PicacgKeywordFilter.MAX_KEYWORD_LENGTH}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitKeyword(entry, { message = it }, { entry = "" }, keywordAdded, invalidKeyword, duplicateKeyword) }),
            )
            Button(
                onClick = { submitKeyword(entry, { message = it }, { entry = "" }, keywordAdded, invalidKeyword, duplicateKeyword) },
                enabled = entry.trim().isNotEmpty() && entry.trim().length <= PicacgKeywordFilter.MAX_KEYWORD_LENGTH,
            ) { Text(addKeyword) }
        }
        Text(stringResource(KMR.strings.picacg_block_keyword_presets), style = MaterialTheme.typography.titleMedium)
        PicacgKeywordFilter.presetGroups.forEach { group ->
            TextButton(
                onClick = {
                    val count = PicacgBlockKeywordStore.addKeywords(group.keywords)
                    message = if (count > 0) addedPreset.format(count) else addedNothing
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("${presetTitle(group.id)} · ${group.keywords.size}", modifier = Modifier.fillMaxWidth())
            }
        }
        Text(stringResource(KMR.strings.picacg_block_keyword_list), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(KMR.strings.picacg_block_keyword_search)) },
            singleLine = true,
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (filteredKeywords.isEmpty()) {
            Text(stringResource(KMR.strings.picacg_block_keyword_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            filteredKeywords.forEach { keyword ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(keyword, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { PicacgBlockKeywordStore.removeKeyword(keyword) }) {
                        Text(stringResource(KMR.strings.picacg_block_keyword_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun presetTitle(id: String): String = stringResource(
    when (id) {
        "bl_femboy" -> KMR.strings.picacg_block_keyword_preset_bl
        "ai_art" -> KMR.strings.picacg_block_keyword_preset_ai
        "ntr" -> KMR.strings.picacg_block_keyword_preset_ntr
        else -> KMR.strings.picacg_block_keyword_preset_heavy
    },
)

private fun submitKeyword(
    value: String,
    setMessage: (String) -> Unit,
    clearEntry: () -> Unit,
    addedText: String,
    invalidText: String,
    duplicateText: String,
) {
    when (PicacgBlockKeywordStore.addKeyword(value)) {
        PicacgKeywordAddResult.ADDED -> {
            setMessage(addedText)
            clearEntry()
        }
        PicacgKeywordAddResult.INVALID -> setMessage(invalidText)
        PicacgKeywordAddResult.DUPLICATE -> setMessage(duplicateText)
    }
}
