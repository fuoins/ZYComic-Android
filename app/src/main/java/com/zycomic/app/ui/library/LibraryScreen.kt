package com.zycomic.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.ui.components.EmptyView
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.LoadingFooter
import com.zycomic.app.ui.components.coverUrl
import com.zycomic.app.ui.theme.BluePrimary
import com.zycomic.app.ui.theme.OffWhite
import com.zycomic.app.ui.theme.TextSecondary

@Composable
fun LibraryScreen(
    onOpenManga: (Int) -> Unit,
    onRequireLogin: () -> Unit,
) {
    val vm = remember { LibraryViewModel() }
    val mainTab by vm.mainTab.collectAsState()
    val user by vm.user.collectAsState()

    androidx.compose.runtime.LaunchedEffect(vm.needLogin) {
        vm.needLogin.collect { if (it) onRequireLogin() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("书架", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        androidx.compose.material3.TabRow(selectedTabIndex = mainTab) {
            androidx.compose.material3.Tab(selected = mainTab == 0, onClick = { vm.selectMainTab(0) }, text = { Text("收藏", color = Color.Black) })
            androidx.compose.material3.Tab(selected = mainTab == 1, onClick = { vm.selectMainTab(1) }, text = { Text("阅读历史", color = Color.Black) })
        }

        if (user == null) {
            EmptyView("登录后查看收藏与阅读历史", modifier = Modifier.fillMaxSize())
            return@Column
        }

        if (mainTab == 0) FavContent(vm, onOpenManga, onRequireLogin)
        else HistoryContent(vm, onRequireLogin)
    }
}

@Composable
private fun FavContent(vm: LibraryViewModel, onOpenManga: (Int) -> Unit, onRequireLogin: () -> Unit) {
    val folders by vm.folders.collectAsState()
    val selectedFolder by vm.selectedFolderId.collectAsState()
    val isEnd by vm.isEnd.collectAsState()
    val isFull by vm.isFullVersion.collectAsState()
    val favs by vm.favMangas.collectAsState()
    val loading by vm.favLoading.collectAsState()
    val appending by vm.favAppending.collectAsState()
    val hasMore by vm.favHasMore.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val selectedTags by vm.selectedTags.collectAsState()

    var showMoveDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(if (selectionMode) "已选 ${selectedIds.size} 项" else "收藏", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (!selectionMode) {
                Text("管理", color = BluePrimary, modifier = Modifier.clickable { vm.enterSelection() }.padding(8.dp))
            } else {
                Text("取消", color = TextSecondary, modifier = Modifier.clickable { vm.exitSelection() }.padding(8.dp))
            }
        }

        // 分类夹横向滚动
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            FilterChip("全部收藏夹", selected = selectedFolder == 0, onClick = { vm.selectFolder(0) })
            folders.forEach { f ->
                Box(Modifier.padding(horizontal = 2.dp))
                FilterChip(f.name, selected = selectedFolder == f.id, onClick = { vm.selectFolder(f.id) })
            }
        }

        // 筛选
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
            listOf(-1 to "全部", 0 to "连载中", 1 to "完结").forEach { (v, l) ->
                FilterChip(l, selected = isEnd == v, onClick = { vm.selectIsEnd(v) })
            }
            FilterChip("只显示更新", selected = vm.showOnlyUpdated.collectAsState().value == 1, onClick = { vm.toggleOnlyUpdated() })
        }

        // 标签筛选行
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
            FilterChip("全部标签", selected = selectedTags.isEmpty(), onClick = { vm.clearTags() })
            selectedTags.forEach { tag ->
                FilterChip(tag, selected = true, onClick = { vm.toggleTag(tag) })
            }
            FilterChip("更多", selected = false, onClick = {
                vm.loadTagsIfNeeded()
                showTagDialog = true
            })
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            FilterChip(if (vm.order.collectAsState().value == 1) "更新时间" else "收藏时间", selected = false, onClick = { vm.toggleOrder() })
            FilterChip(if (vm.orderType.collectAsState().value == 0) "降序" else "升序", selected = false, onClick = { vm.toggleOrderType() })
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading && favs.isEmpty() -> LoadingFooter()
                favs.isEmpty() -> EmptyView("暂无收藏")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(favs, key = { it.id }) { m ->
                        FavRow(m, selected = selectedIds.contains(m.id), selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) vm.toggleSelect(m.id) else onOpenManga(m.id)
                            })
                    }
                    if (appending) item { LoadingFooter() }
                    else if (!hasMore) item {
                        Text("没有更多了", modifier = Modifier.fillMaxWidth().padding(16.dp), color = TextSecondary)
                    }
                }
            }
        }
    }

    if (selectionMode) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F2F5)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(onClick = { vm.batchRemove() }) { Text("取消收藏") }
            Button(onClick = { showMoveDialog = true }) { Text("移动收藏夹") }
        }
    }

    if (showMoveDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showMoveDialog = false }) {
            Column(Modifier.background(Color.White).padding(16.dp)) {
                Text("移动到收藏夹", style = MaterialTheme.typography.titleMedium)
                Text("移出分类", Modifier.fillMaxWidth().clickable { vm.moveSelectedTo(0); showMoveDialog = false }.padding(12.dp))
                folders.forEach { f ->
                    Text(f.name, Modifier.fillMaxWidth().clickable { vm.moveSelectedTo(f.id); showMoveDialog = false }.padding(12.dp))
                }
            }
        }
    }

    if (showTagDialog) {
        LibraryTagDialog(vm = vm, onDismiss = { showTagDialog = false })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LibraryTagDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val allTags by vm.allTags.collectAsState()
    val loading by vm.tagsLoading.collectAsState()
    val current by vm.selectedTags.collectAsState()

    var keyword by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf(current) }

    val filtered = remember(keyword, allTags) {
        if (keyword.isBlank()) allTags
        else allTags.filter { it.contains(keyword, ignoreCase = true) }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(16.dp),
        ) {
            Text("选择标签", style = MaterialTheme.typography.titleMedium)
            androidx.compose.material3.OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                singleLine = true,
                placeholder = { Text("搜索标签") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        filtered.forEach { tag ->
                            FilterChip(
                                text = tag,
                                selected = temp.contains(tag),
                                onClick = {
                                    temp = if (temp.contains(tag)) temp - tag else temp + tag
                                },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.TextButton(onClick = { temp = emptySet() }) {
                    Text("重置")
                }
                androidx.compose.material3.TextButton(onClick = {
                    vm.selectedTags.value = temp
                    vm.refreshFavorites()
                    onDismiss()
                }) {
                    Text("确定")
                }
            }
        }
    }
}

@Composable
private fun FavRow(m: Manga, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.background(if (selected) Color(0xFFE3F0FF) else Color.White).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverUrl(m),
            contentDescription = m.name,
            modifier = Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(OffWhite),
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(if (m.start > 0) "继续阅读 #${m.start}" else "最新章节", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun HistoryContent(vm: LibraryViewModel, onRequireLogin: () -> Unit) {
    val history by vm.history.collectAsState()
    val loading by vm.historyLoading.collectAsState()
    val hasMore by vm.historyHasMore.collectAsState()
    val selMode by vm.historySelectionMode.collectAsState()
    val selIds by vm.historySelectedIds.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(listState.canScrollForward, history.size) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (history.isNotEmpty() && last >= history.size - 3 && hasMore) vm.loadMoreHistory()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Box(modifier = Modifier.weight(1f))
            if (!selMode) {
                Text("删除", color = BluePrimary, modifier = Modifier.clickable { vm.enterHistorySelection() }.padding(8.dp))
            } else {
                Text("取消", color = TextSecondary, modifier = Modifier.clickable { vm.exitHistorySelection() }.padding(8.dp))
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(history, key = { it.id }) { h ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (selMode) vm.toggleHistorySelect(h.id)
                        }.background(if (selIds.contains(h.id)) Color(0xFFE3F0FF) else Color.White).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = coverUrl(com.zycomic.app.data.dto.Manga(id = h.id, name = h.name, picx = h.picx)),
                            contentDescription = h.name,
                            modifier = Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(OffWhite),
                        )
                        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(h.chapterName, style = MaterialTheme.typography.bodySmall, color = BluePrimary)
                            Text("更新于 ${h.addtime}", style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                if (loading) item { LoadingFooter() }
                else if (!hasMore && history.isNotEmpty()) item {
                    Text("没有更多了", modifier = Modifier.fillMaxWidth().padding(16.dp), color = TextSecondary)
                }
            }
        }
        if (selMode) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F2F5)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 ${selIds.size} 项", modifier = Modifier.weight(1f))
                Text("全选", color = BluePrimary, modifier = Modifier.clickable { vm.selectAllLoadedHistory() }.padding(horizontal = 12.dp))
                Button(onClick = { vm.deleteSelectedHistory() }) { Text("删除") }
            }
        }
    }
}
