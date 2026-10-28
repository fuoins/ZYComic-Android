package com.zycomic.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformSelector
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.SearchToolbar
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

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
    backgroundColor: Color = Color.Transparent,
    history: Boolean = false,
    selectionSupported: Boolean = true,
    refreshBusy: Boolean = false,
    selectionEnabled: Boolean = true,
    refreshEnabled: Boolean = true,
    refreshIndicatorSeparate: Boolean = false,
    refreshEnabledWhileBusy: Boolean = false,
) {
    val slotWidth = LibraryHistoryToolbarPolicy.ACTION_SLOT_WIDTH_DP.dp
    val platformSelectorWidth = LibraryHistoryToolbarPolicy.COMPACT_PLATFORM_SELECTOR_WIDTH_DP.dp
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
                        enabled = refreshEnabled && (!refreshBusy || refreshEnabledWhileBusy),
                        busy = refreshBusy,
                        busyIndicatorSeparate = refreshIndicatorSeparate,
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
                onClick = onEnterSelection.takeIf { selectionSupported },
                description = "多选",
                icon = Icons.Outlined.Checklist,
                width = slotWidth,
                enabled = selectionEnabled,
            )
        }
        if (LibraryHistoryToolbarPolicy.showPlatformSelector(searchQuery != null)) {
            Box(
                modifier = Modifier.width(platformSelectorWidth).height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                ContentPlatformSelector(
                    selected = platform,
                    onSelect = onSelectPlatform,
                    compact = true,
                    compactButtonSize = 40.dp,
                    compactJmLabel = stringResource(KMR.strings.library_history_platform_jm_short),
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
            backgroundColor = backgroundColor,
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
            backgroundColor = backgroundColor,
        )
    }
}

@Composable
private fun ToolbarSlot(
    onClick: (() -> Unit)?,
    description: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    width: androidx.compose.ui.unit.Dp = 48.dp,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") busy: Boolean = false,
    @Suppress("UNUSED_PARAMETER") busyIndicatorSeparate: Boolean = false,
) {
    Box(
        modifier = Modifier.width(width).height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (onClick != null && icon != null) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.width(width)) {
                // 加载中不再显示转圈（曾被视为蓝色刷新动画），图标原样保留。
                Icon(imageVector = icon, contentDescription = description)
            }
        }
    }
}
