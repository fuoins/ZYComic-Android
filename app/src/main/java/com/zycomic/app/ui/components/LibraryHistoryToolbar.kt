package com.zycomic.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformSelector
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SearchToolbar

/** Library/history toolbar: title and actions share one row, with a compact menu on narrow screens. */
@Composable
fun LibraryHistoryToolbar(
    platform: ContentPlatform,
    onSelectPlatform: (ContentPlatform) -> Unit,
    searchQuery: String?,
    onChangeSearchQuery: (String?) -> Unit,
    titleContent: @Composable () -> Unit,
    searchSupported: Boolean,
    refreshSupported: Boolean,
    filterSupported: Boolean,
    onRefresh: () -> Unit,
    onFilter: () -> Unit,
    onEnterSelection: () -> Unit,
    selectionMode: Boolean,
    selectionCount: Int,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
) {
    BoxWithConstraints {
        val compact = maxWidth < 440.dp
        var overflowExpanded by remember { mutableStateOf(false) }
        val actions: @Composable RowScope.() -> Unit = {
            if (compact) {
                ToolbarOverflow(
                    expanded = overflowExpanded,
                    onExpandedChange = { overflowExpanded = it },
                    selectionMode = selectionMode,
                    searchSupported = searchSupported,
                    refreshSupported = refreshSupported,
                    filterSupported = filterSupported,
                    canEnterSelection = platform == ContentPlatform.MANWA,
                    onSearch = { onChangeSearchQuery("") },
                    onRefresh = onRefresh,
                    onFilter = onFilter,
                    onEnterSelection = onEnterSelection,
                    onSelectAll = onSelectAll,
                    onInvertSelection = onInvertSelection,
                )
            } else if (selectionMode) {
                ToolbarSlot(onClick = onSelectAll, description = "全选", icon = Icons.Outlined.SelectAll)
                ToolbarSlot(onClick = onInvertSelection, description = "反选", icon = Icons.Outlined.FlipToBack)
                ToolbarSlot(onClick = null)
                ToolbarSlot(onClick = null)
            } else {
                ToolbarSlot(
                    onClick = if (searchSupported) ({ onChangeSearchQuery("") }) else null,
                    description = if (searchQuery == null) "搜索" else "清除搜索",
                    icon = if (searchQuery == null) Icons.Outlined.Search else Icons.Outlined.Close,
                )
                ToolbarSlot(
                    onClick = onRefresh.takeIf { refreshSupported },
                    description = "刷新",
                    icon = Icons.Outlined.Refresh,
                )
                if (filterSupported) {
                    ToolbarSlot(onClick = onFilter, description = "筛选", icon = Icons.Outlined.FilterList)
                } else {
                    ToolbarSlot(onClick = null)
                }
                ToolbarSlot(
                    onClick = onEnterSelection.takeIf { platform == ContentPlatform.MANWA },
                    description = "多选",
                    icon = Icons.Outlined.Checklist,
                )
            }
            Box(
                modifier = Modifier.width(112.dp).height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                ContentPlatformSelector(
                    selected = platform,
                    onSelect = onSelectPlatform,
                    compact = true,
                )
            }
        }

        if (selectionMode) {
            AppBar(
                titleContent = { Text(selectionCount.toString()) },
                actions = actions,
                isActionMode = true,
                onCancelActionMode = onExitSelection,
                scrollBehavior = scrollBehavior,
            )
        } else {
            SearchToolbar(
                searchQuery = searchQuery,
                onChangeSearchQuery = onChangeSearchQuery,
                titleContent = titleContent,
                searchEnabled = false,
                onClickCloseSearch = { onChangeSearchQuery(null) },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        }
    }
}

@Composable
private fun ToolbarOverflow(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectionMode: Boolean,
    searchSupported: Boolean,
    refreshSupported: Boolean,
    filterSupported: Boolean,
    canEnterSelection: Boolean,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onFilter: () -> Unit,
    onEnterSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
) {
    Box(
        modifier = Modifier.width(48.dp).height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            fun closeAndRun(action: () -> Unit) {
                onExpandedChange(false)
                action()
            }
            if (selectionMode) {
                DropdownMenuItem(text = { Text("全选") }, onClick = { closeAndRun(onSelectAll) })
                DropdownMenuItem(text = { Text("反选") }, onClick = { closeAndRun(onInvertSelection) })
            } else {
                if (searchSupported) {
                    DropdownMenuItem(text = { Text("搜索") }, onClick = { closeAndRun(onSearch) })
                }
                if (refreshSupported) {
                    DropdownMenuItem(text = { Text("刷新") }, onClick = { closeAndRun(onRefresh) })
                }
                if (filterSupported) {
                    DropdownMenuItem(text = { Text("筛选") }, onClick = { closeAndRun(onFilter) })
                }
                if (canEnterSelection) {
                    DropdownMenuItem(text = { Text("多选") }, onClick = { closeAndRun(onEnterSelection) })
                }
            }
        }
    }
}

@Composable
private fun ToolbarSlot(
    onClick: (() -> Unit)?,
    description: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Box(
        modifier = Modifier.width(48.dp).height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (onClick != null && icon != null) {
            IconButton(onClick = onClick) {
                Icon(imageVector = icon, contentDescription = description)
            }
        }
    }
}
