package com.zycomic.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zycomic.app.ui.components.FilterChip

/**
 * Search-and-select dialog shared by settings and manga detail.
 * The caller supplies the candidate set so detail can restrict it to that manga's tags.
 */
@Composable
fun AddBlacklistDialog(
    tags: List<String>,
    adding: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val availableTags = remember(tags) { tags.filter { it.isNotBlank() }.distinct() }
    var keyword by remember(availableTags) { mutableStateOf("") }
    var selected by remember(availableTags) { mutableStateOf<Set<String>>(emptySet()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "添加屏蔽标签（已选 ${selected.size}）",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(16.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索标签") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            val filtered = if (keyword.isBlank()) {
                availableTags
            } else {
                availableTags.filter { it.contains(keyword, ignoreCase = true) }
            }
            if (filtered.isEmpty()) {
                Text(
                    if (availableTags.isEmpty()) "暂无可添加标签" else "没有匹配的标签",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    filtered.forEach { tag ->
                        FilterChip(
                            text = tag,
                            selected = tag in selected,
                            onClick = {
                                selected = if (tag in selected) selected - tag else selected + tag
                            },
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {
                    selected = emptySet()
                    keyword = ""
                }) { Text("重置") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !adding,
                    onClick = {
                        if (selected.isEmpty()) {
                            Toast.makeText(context, "请先选择标签", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        onSubmit(selected.toList())
                    },
                ) { Text(if (adding) "添加中..." else "确认添加 (${selected.size})") }
            }
        }
    }
}
