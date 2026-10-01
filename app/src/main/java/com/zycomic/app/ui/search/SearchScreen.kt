package com.zycomic.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zycomic.app.ui.components.EmptyView
import com.zycomic.app.ui.components.LoadingFooter
import com.zycomic.app.ui.components.MangaCard
import com.zycomic.app.ui.theme.TextSecondary

@Composable
fun SearchScreen(
    initialKeyword: String,
    onClose: () -> Unit,
    onOpenManga: (Int) -> Unit,
) {
    val vm = remember { SearchViewModel() }
    val keyword by vm.keyword.collectAsState()
    val mangas by vm.mangas.collectAsState()
    val loading by vm.loading.collectAsState()
    val appending by vm.appending.collectAsState()
    val hasMore by vm.hasMore.collectAsState()

    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        if (initialKeyword.isNotBlank()) {
            vm.updateKeyword(initialKeyword)
            vm.doSearch()
        }
    }

    LaunchedEffect(gridState.canScrollForward, mangas.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (mangas.isNotEmpty() && lastVisible >= mangas.size - 4 && hasMore && !loading) {
            vm.loadMore()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            OutlinedTextField(
                value = keyword,
                onValueChange = { vm.updateKeyword(it) },
                placeholder = { Text("输入漫画名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onDone = { vm.doSearch() },
                    onSearch = { vm.doSearch() },
                ),
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading && mangas.isEmpty() -> LoadingFooter()
                mangas.isEmpty() && !loading && keyword.isNotBlank() -> EmptyView("没有搜索到「$keyword」")
                mangas.isEmpty() && !loading -> EmptyView("输入关键词开始搜索")
                else -> LazyVerticalGrid(
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
                    if (appending) item(span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
                    else if (!hasMore && mangas.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("没有更多了", modifier = Modifier.fillMaxWidth().padding(16.dp), color = TextSecondary)
                    }
                }
            }
        }
    }
}

/** 搜索覆盖层（full-screen Dialog）。 */
@Composable
fun SearchOverlay(
    initialKeyword: String,
    onClose: () -> Unit,
    onOpenManga: (Int) -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.White),
        ) {
            SearchScreen(
                initialKeyword = initialKeyword,
                onClose = onClose,
                onOpenManga = onOpenManga,
            )
        }
    }
}
