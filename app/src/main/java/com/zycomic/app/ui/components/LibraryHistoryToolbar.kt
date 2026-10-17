package com.zycomic.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Library/history toolbar: title/search stay on row one; actions use fixed slots on row two. */
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
    Column {
        if (selectionMode) {
            AppBar(
                titleContent = { Text(selectionCount.toString()) },
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
                scrollBehavior = scrollBehavior,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                ToolbarSlot(onClick = onSelectAll, description = "全选", icon = Icons.Outlined.SelectAll)
                ToolbarSlot(onClick = onInvertSelection, description = "反选", icon = Icons.Outlined.FlipToBack)
                ToolbarSlot(onClick = null)
                ToolbarSlot(onClick = null)
            } else {
                ToolbarSlot(
                    onClick = if (searchSupported) {
                        { onChangeSearchQuery("") }
                    } else {
                        null
                    },
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
