package com.zycomic.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformSelector
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SearchToolbar

/** Library/history toolbar: title and all supported actions share one compact, aligned row. */
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
    history: Boolean = false,
) {
    val slotWidth = 40.dp
    val actions: @Composable RowScope.() -> Unit = {
        if (selectionMode) {
            ToolbarSlot(onClick = onSelectAll, description = "全选", icon = Icons.Outlined.SelectAll, width = slotWidth)
            ToolbarSlot(onClick = onInvertSelection, description = "反选", icon = Icons.Outlined.FlipToBack, width = slotWidth)
            ToolbarSlot(onClick = null, width = slotWidth)
            ToolbarSlot(onClick = null, width = slotWidth)
        } else {
            LibraryHistoryToolbarPolicy.visibleActions(
                history = history,
                searchExpanded = searchQuery != null,
                jmPlatform = platform == ContentPlatform.JM,
            ).forEach { action ->
                when (action) {
                    LibraryHistoryToolbarPolicy.Action.SEARCH -> ToolbarSlot(
                        onClick = if (searchSupported) ({ onChangeSearchQuery("") }) else null,
                        description = if (searchQuery == null) "搜索" else "清除搜索",
                        icon = if (searchQuery == null) Icons.Outlined.Search else Icons.Outlined.Close,
                        width = slotWidth,
                    )
                    LibraryHistoryToolbarPolicy.Action.REFRESH -> ToolbarSlot(
                        onClick = onRefresh.takeIf { refreshSupported },
                        description = "刷新",
                        icon = Icons.Outlined.Refresh,
                        width = slotWidth,
                    )
                    LibraryHistoryToolbarPolicy.Action.FILTER -> ToolbarSlot(
                        onClick = onFilter.takeIf { filterSupported },
                        description = "筛选",
                        icon = Icons.Outlined.FilterList,
                        width = slotWidth,
                    )
                    LibraryHistoryToolbarPolicy.Action.EMPTY -> ToolbarSlot(onClick = null, width = slotWidth)
                }
            }
            ToolbarSlot(
                onClick = onEnterSelection,
                description = "多选",
                icon = Icons.Outlined.Checklist,
                width = slotWidth,
            )
        }
        if (LibraryHistoryToolbarPolicy.showPlatformSelector(searchQuery != null, platform == ContentPlatform.JM)) {
            Box(
                modifier = Modifier.width(100.dp).height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                ContentPlatformSelector(
                    selected = platform,
                    onSelect = onSelectPlatform,
                    compact = true,
                )
            }
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

@Composable
private fun ToolbarSlot(
    onClick: (() -> Unit)?,
    description: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    width: androidx.compose.ui.unit.Dp = 48.dp,
) {
    Box(
        modifier = Modifier.width(width).height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (onClick != null && icon != null) {
            IconButton(onClick = onClick, modifier = Modifier.width(width)) {
                Icon(imageVector = icon, contentDescription = description)
            }
        }
    }
}
