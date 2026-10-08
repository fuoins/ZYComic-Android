package com.zycomic.app.ui.search

import coil3.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.ui.components.DisplaySettingsSection
import com.zycomic.app.ui.components.EmptyView
import com.zycomic.app.ui.components.LoadingFooter
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import kotlinx.collections.immutable.persistentListOf

@Composable
fun SearchScreen(
    initialKeyword: String,
    onClose: () -> Unit,
    onOpenManga: (String) -> Unit,
) {
    val vm = remember { SearchViewModel() }
    val keyword by vm.keyword.collectAsState()
    val mangas by vm.mangas.collectAsState()
    val loading by vm.loading.collectAsState()
    val appending by vm.appending.collectAsState()
    val hasMore by vm.hasMore.collectAsState()

    val displayMode by vm.displayMode.collectAsState()
    val gridColumns by vm.gridColumns.collectAsState()
    var showDisplaySheet by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showDisplaySheet) { showDisplaySheet = false }
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    LaunchedEffect(orientation) { vm.loadGridColumnsForOrientation(orientation) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // 顶部搜索栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = { vm.updateKeyword(it) },
                placeholder = { Text("搜索漫画") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { vm.doSearch() },
                ),
            )
            // 显示模式切换：点击弹出底部"显示"设置弹窗
            IconButton(onClick = { showDisplaySheet = true }) {
                Icon(
                    when (displayMode) {
                        0 -> Icons.Filled.ViewCompact
                        1 -> Icons.Filled.GridView
                        else -> Icons.Filled.ViewAgenda
                    },
                    contentDescription = "显示模式",
                )
            }
        }
        HorizontalDivider()

        // 搜索结果
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading && mangas.isEmpty() -> LoadingFooter()
                mangas.isEmpty() && !loading && keyword.isNotBlank() -> EmptyView("没有搜索到「$keyword」")
                mangas.isEmpty() && !loading -> EmptyView("输入关键词开始搜索")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(if (gridColumns > 0) gridColumns else 3),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(mangas.size, key = { mangas[it].id + "_$it" }) { i ->
                        val m = mangas[i]
                        SearchMangaGridItem(
                            manga = m,
                            displayMode = displayMode,
                            onClick = { onOpenManga(m.id) },
                        )
                    }
                    if (appending) item(span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
                    else if (!hasMore && mangas.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "没有更多了",
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // "显示"设置底部弹窗（与分类页 TabbedDialog 同款）
    if (showDisplaySheet) {
        TabbedDialog(
            onDismissRequest = { showDisplaySheet = false },
            tabTitles = persistentListOf("显示"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(TabbedDialogPaddings.Horizontal),
            ) {
                DisplaySettingsSection(
                    displayMode = displayMode,
                    gridColumns = gridColumns,
                    orientation = orientation,
                    onSetDisplayMode = vm::setDisplayMode,
                    onSetGridColumns = { vm.setGridColumns(it, orientation) },
                )
            }
        }
    }
}

/** 搜索结果漫画卡片，支持三种显示模式。 */
@Composable
private fun SearchMangaGridItem(
    manga: Manga,
    displayMode: Int,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = manga.picx.ifBlank { manga.pic },
                contentDescription = manga.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 紧凑网格：标题叠加封面底部
            if (displayMode == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.35f)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color(0xAA000000),
                            ),
                        ),
                ) {
                    Text(
                        text = manga.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    )
                }
            }
        }
        // 舒适网格显示标题；紧凑网格标题已叠加；仅封面网格不显示
        if (displayMode == 1) {
            Text(
                text = manga.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 搜索覆盖层（full-screen Dialog）。 */
@Composable
fun SearchOverlay(
    initialKeyword: String,
    onClose: () -> Unit,
    onOpenManga: (String) -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SearchScreen(
            initialKeyword = initialKeyword,
            onClose = onClose,
            onOpenManga = onOpenManga,
        )
    }
}
