package com.zycomic.app.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YuzongDefaultDialog(
    initialName: String,
    onRename: (String) -> Boolean,
    onDismiss: () -> Unit,
    checkNameTaken: (String) -> Boolean = { false },
) {
    val ctx = LocalContext.current
    var platform by remember { mutableStateOf(PlatformPrefs.loadPlatform(ctx)) }
    var sub by remember { mutableStateOf(PlatformPrefs.loadSub(ctx, platform)) }
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val nameError = when {
        trimmed.isEmpty() -> "名称不能为空"
        checkNameTaken(trimmed) -> "名称已存在"
        else -> null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxHeight(0.9f).widthIn(max = 460.dp).fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                        Text("编辑宇总排行", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = {
                            Column {
                                nameError?.let { Text(it) }
                                Text(stringResource(KMR.strings.browse_tab_name_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        label = { Text("tab 名称") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Text("默认平台", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlatformCatalog.platforms.forEach { p ->
                            FilterChip(
                                selected = platform == p,
                                onClick = {
                                    platform = p
                                    sub = p.defaultSub
                                },
                                label = { Text(p.label) },
                            )
                        }
                    }
                    Text("默认子榜", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        platform.subs.forEach { s ->
                            FilterChip(
                                selected = sub == s.id,
                                onClick = { sub = s.id },
                                label = { Text(s.label) },
                            )
                        }
                    }
                    HorizontalDivider()
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                        Button(enabled = nameError == null, onClick = {
                            if (trimmed != initialName && !onRename(trimmed)) return@Button
                            PlatformPrefs.save(ctx, platform, sub)
                            onDismiss()
                        }) { Text("保存") }
                    }
                }
            }
        }
    }
}
