package com.zycomic.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.zycomic.app.ui.components.EmptyView
import com.zycomic.app.ui.components.ErrorView
import com.zycomic.app.ui.components.FilterChip
import com.zycomic.app.ui.components.LoadingFooter
import com.zycomic.app.ui.components.MangaCard
import com.zycomic.app.ui.theme.Divider
import com.zycomic.app.ui.theme.TextSecondary

@Composable
fun BrowseScreen(
    pendingTag: String?,
    onPendingTagConsumed: () -> Unit,
    onOpenManga: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onRequireLogin: () -> Unit,
) {
    val vm = remember { BrowseViewModel() }

    pendingTag?.let { tag ->
        LaunchedEffect(tag) {
            vm.applyPendingTag(tag)
            onPendingTagConsumed()
        }
    }

    val mainTab by vm.mainTab.collectAsState()
    val mangas by vm.mangas.collectAsState()
    val loading by vm.loading.collectAsState()
    val appending by vm.appending.collectAsState()
    val error by vm.error.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val newestNums by vm.newestNums.collectAsState()

    val gridState = rememberLazyGridState()

    // 滚动到底部自动加载更多
    LaunchedEffect(gridState.canScrollForward, mangas.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (mangas.isNotEmpty() && lastVisible >= mangas.size - 4 && hasMore && !loading) {
            vm.loadMore()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "分类",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        }

        // 主 Tab
        TabRow(selectedTabIndex = mainTab) {
            Tab(selected = mainTab == 0, onClick = { vm.selectMainTab(0) }, text = { Text("分类", color = androidx.compose.ui.graphics.Color.Black) })
            Tab(selected = mainTab == 1, onClick = { vm.selectMainTab(1) }, text = { Text("最近更新", color = androidx.compose.ui.graphics.Color.Black) })
            Tab(selected = mainTab == 2, onClick = { vm.selectMainTab(2) }, text = { Text("排行", color = androidx.compose.ui.graphics.Color.Black) })
        }

        when (mainTab) {
            0 -> FilterArea(vm)
            1 -> NewestArea(vm, newestNums)
            2 -> RankArea(vm)
        }

        // 内容
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading && mangas.isEmpty() -> LoadingFooter()
                error != null && mangas.isEmpty() -> ErrorView(message = error ?: "", onRetry = { vm.refresh() })
                mangas.isEmpty() && !loading -> EmptyView("没有漫画")
                else -> MangaGrid(
                    mangas = mangas,
                    gridState = gridState,
                    appending = appending,
                    hasMore = hasMore,
                    onOpenManga = onOpenManga,
                )
            }
        }
    }
}

@Composable
private fun MangaGrid(
    mangas: List<com.zycomic.app.data.dto.Manga>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    appending: Boolean,
    hasMore: Boolean,
    onOpenManga: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(mangas.size, key = { mangas[it].id * 100 + it }) { i ->
            val m = mangas[i]
            MangaCard(manga = m, onClick = { onOpenManga(m.id) })
        }
        if (appending) {
            item(span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
        } else if (!hasMore && mangas.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("没有更多了", modifier = Modifier.fillMaxWidth().padding(16.dp), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterArea(vm: BrowseViewModel) {
    val gender by vm.gender.collectAsState()
    val selectedTags by vm.selectedTags.collectAsState()
    val area by vm.area.collectAsState()
    val end by vm.end.collectAsState()
    val st by vm.st.collectAsState()
    val expanded by vm.filterExpanded.collectAsState()

    var showTagDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // 展开/收起
        Row(
            modifier = Modifier.fillMaxWidth().clickable { vm.toggleFilterExpanded() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("筛选", style = MaterialTheme.typography.titleSmall, color = TextSecondary)
            Icon(
                if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }

        if (expanded) {
            // 第1行 类型
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BrowseViewModel.GENDERS.forEach { (v, label) ->
                    FilterChip(text = label, selected = gender == v, onClick = { vm.selectGender(v) })
                }
            }
            // 第2行 标签
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(text = "全部", selected = selectedTags.isEmpty(), onClick = { vm.clearTags() })
                BrowseViewModel.QUICK_TAGS.forEach { tag ->
                    FilterChip(text = tag, selected = selectedTags.contains(tag), onClick = { vm.toggleTag(tag) })
                }
                FilterChip(text = "更多", selected = false, onClick = {
                    vm.loadTagsIfNeeded()
                    showTagDialog = true
                })
            }
            // 第3行 地区
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                BrowseViewModel.AREAS.forEach { (v, label) ->
                    FilterChip(text = label, selected = area == v, onClick = { vm.selectArea(v) })
                }
            }
            // 第4行 状态
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                BrowseViewModel.ENDS.forEach { (v, label) ->
                    FilterChip(text = label, selected = end == v, onClick = { vm.selectEnd(v) })
                }
            }
            // 第5行 排序
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                BrowseViewModel.STS.forEach { (v, label) ->
                    FilterChip(text = label, selected = st == v, onClick = { vm.selectSt(v) })
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
        }
    }

    if (showTagDialog) {
        TagSelectDialog(
            vm = vm,
            onDismiss = { showTagDialog = false },
        )
    }
}

@Composable
private fun NewestArea(vm: BrowseViewModel, newestNums: Int) {
    val date by vm.newestDate.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            vm.dateOptions.forEach { (label, key) ->
                FilterChip(text = label, selected = date == key, onClick = { vm.selectDate(key) })
            }
        }
        Text(
            "更新了${newestNums}本，持续更新中",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun RankArea(vm: BrowseViewModel) {
    val rankType by vm.rankType.collectAsState()
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        listOf(0 to "人气榜", 1 to "新番榜", 2 to "完结榜").forEach { (v, label) ->
            FilterChip(text = label, selected = rankType == v, onClick = { vm.selectRankType(v) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSelectDialog(
    vm: BrowseViewModel,
    onDismiss: () -> Unit,
) {
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
                .background(androidx.compose.ui.graphics.Color.White)
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
                androidx.compose.foundation.verticalScroll(
                    androidx.compose.foundation.rememberScrollState()
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    // 应用选择
                    vm.selectedTags.value = temp
                    vm.refresh()
                    onDismiss()
                }) {
                    Text("确定")
                }
            }
        }
    }
}
