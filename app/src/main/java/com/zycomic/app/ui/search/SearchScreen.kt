package com.zycomic.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.imageLoader
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.picacg.PicacgAuthStore
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.ui.components.DisplaySettingsSection
import com.zycomic.app.ui.components.EmptyView
import com.zycomic.app.ui.components.LoadingFooter
import com.zycomic.app.ui.components.coverUrl
import com.zycomic.app.ui.picacg.PicacgThemes
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import kotlinx.collections.immutable.persistentListOf

@Composable
fun SearchScreen(
    initialKeyword: String,
    initialProvider: SearchProvider? = null,
    onClose: () -> Unit,
    onOpenManga: (String) -> Unit,
) {
    val vm = remember(initialProvider) { SearchViewModel(initialProvider) }
    DisposableEffect(vm) {
        onDispose { vm.dispose() }
    }
    val keyword by vm.keyword.collectAsState()
    val mangas by vm.mangas.collectAsState()
    val error by vm.error.collectAsState()
    val loading by vm.loading.collectAsState()
    val appending by vm.appending.collectAsState()
    val hasMore by vm.hasMore.collectAsState()

    val provider by vm.provider.collectAsState()
    val picacgLoggedIn by PicacgAuthStore.loggedIn.collectAsState()
    var showPicacgLogin by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resultImageLoader = remember(context, provider) {
        when (provider) {
            SearchProvider.MANWA -> context.imageLoader
            SearchProvider.PICACG -> com.zycomic.app.ui.components.PicacgImageLoader.get(context)
            SearchProvider.JM -> com.zycomic.app.ui.components.JmCoverImageLoader.get(context)
        }
    }
    val displayMode by vm.displayMode.collectAsState()
    val jmSortCode by vm.jmSortCode.collectAsState()
    val picacgCategories by vm.picacgCategories.collectAsState()
    var showPicacgCategories by remember { mutableStateOf(false) }
    val picacgSortCode by vm.picacgSortCode.collectAsState()
    /** 删除模式必须 collect 成状态，直读 .value 不会随点击重组 */
    val historyDeleting by vm.historyDeleting.collectAsState()
    val gridColumns by vm.gridColumns.collectAsState()
    var showDisplaySheet by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showDisplaySheet) { showDisplaySheet = false }
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    LaunchedEffect(orientation, provider) { vm.loadGridColumnsForOrientation(orientation) }
    val gridState = rememberLazyGridState()
    /** 列表档（displayMode == 3）用独立滚动状态，触底加载逻辑与网格档一致 */
    val listState = rememberLazyListState()
    var showJmSortMenu by remember { mutableStateOf(false) }
    var showPicacgSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (initialKeyword.isNotBlank()) {
            vm.updateKeyword(initialKeyword)
            vm.doSearch()
        }
    }

    LaunchedEffect(gridState.canScrollForward, mangas.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (mangas.isNotEmpty() && lastVisible >= mangas.size - 4 && hasMore && !loading && (provider != SearchProvider.PICACG || error == null)) {
            vm.loadMore()
        }
    }

    LaunchedEffect(listState.canScrollForward, mangas.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (mangas.isNotEmpty() && lastVisible >= mangas.size - 4 && hasMore && !loading && (provider != SearchProvider.PICACG || error == null)) {
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
            // 搜索历史垃圾桶：放最右，只在关键词为空（显示历史）时出现。
            // 点一下进删除模式，每条历史右侧露出 ❌ 逐条删；再点退出。清空走历史区的"清空"按钮。
            if (keyword.isBlank()) {
                IconButton(onClick = { vm.historyDeleting.value = !historyDeleting }) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = if (historyDeleting) "退出删除搜索记录" else "删除搜索记录",
                        tint = if (historyDeleting) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.zycomic.app.platform.ContentPlatformSelector(
                selected = com.zycomic.app.platform.ContentPlatform.fromId(provider.storageValue),
                onSelect = {
                    if (StartupPlatformPolicy.checkSelection(context, it)) {
                        vm.selectProvider(SearchProvider.fromStorage(it.id))
                    }
                },
                compact = true,
            )
            if (provider == SearchProvider.JM) {
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { showJmSortMenu = true }) {
                        val sortLabel = JmSearchSortOptions.firstOrNull { it.second == jmSortCode }?.first ?: "最新"
                        Text("排序：$sortLabel")
                    }
                    DropdownMenu(
                        expanded = showJmSortMenu,
                        onDismissRequest = { showJmSortMenu = false },
                    ) {
                        JmSearchSortOptions.forEach { (label, code) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    showJmSortMenu = false
                                    vm.setJmSortCode(code)
                                },
                            )
                        }
                    }
                }
            } else if (provider == SearchProvider.PICACG) {
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { showPicacgSortMenu = true }) {
                        val label = PicacgSearchSortOptions.firstOrNull { it.second == picacgSortCode }?.first ?: "最新"
                        Text("排序：$label")
                    }
                    DropdownMenu(expanded = showPicacgSortMenu, onDismissRequest = { showPicacgSortMenu = false }) {
                        PicacgSearchSortOptions.forEach { (label, code) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                showPicacgSortMenu = false
                                vm.setPicacgSortCode(code)
                            })
                        }
                    }
                }
            }
        }
        if (provider == SearchProvider.PICACG) {
            TextButton(onClick = { showPicacgCategories = true }) {
                Text(tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.kmk.KMR.strings.picacg_search_categories) + " · ${picacgCategories.size}")
            }
        }
        HorizontalDivider()

        // 搜索结果
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                provider == SearchProvider.PICACG && !picacgLoggedIn -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.kmk.KMR.strings.picacg_session_required))
                    TextButton(onClick = { showPicacgLogin = true }) {
                        Text(tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.kmk.KMR.strings.picacg_login_action))
                    }
                }
                loading && mangas.isEmpty() -> LoadingFooter()
                mangas.isEmpty() && !loading && keyword.isNotBlank() && error != null -> Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("搜索失败，可能是网络请求异常", style = MaterialTheme.typography.titleMedium)
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.doSearch() }) { Text("点击重试") }
                }
                mangas.isEmpty() && !loading && keyword.isNotBlank() -> EmptyView("没有搜索到「$keyword」")
                keyword.isBlank() -> SearchHistorySection(vm = vm)
                mangas.isEmpty() && !loading -> EmptyView("输入关键词开始搜索")
                displayMode >= 3 -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // key 必须带下标：搜索结果跨页可能返回同一 id，纯 id 作 key 会抛
                    // "Key "..." was already used" 直接崩掉列表（网格档原本就是这么规避的）
                    itemsIndexed(mangas, key = { i, m -> m.id + "_$i" }) { _, m ->
                        SearchMangaListRow(
                            manga = m,
                            imageLoader = resultImageLoader,
                            picacg = provider == SearchProvider.PICACG,
                            onClick = { onOpenManga(searchDetailId(provider, m.id)) },
                        )
                    }
                    if (provider == SearchProvider.PICACG && error != null && !appending) {
                        item {
                            Column {
                                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = vm::loadMore) { Text(tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.kmk.KMR.strings.picacg_retry)) }
                            }
                        }
                    } else if (appending) {
                        item { LoadingFooter() }
                    } else if (!hasMore && mangas.isNotEmpty()) {
                        item {
                            Text(
                                "没有更多了",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
                            imageLoader = resultImageLoader,
                            picacg = provider == SearchProvider.PICACG,
                            onClick = { onOpenManga(searchDetailId(provider, m.id)) },
                        )
                    }
                    if (provider == SearchProvider.PICACG && error != null && !appending) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = vm::loadMore) { Text(tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.kmk.KMR.strings.picacg_retry)) }
                            }
                        }
                    } else if (appending) {
                        item(span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
                    } else if (!hasMore && mangas.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
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
                    modes = listOf(
                        0 to "紧凑网格",
                        1 to "舒适网格",
                        2 to "仅封面网格",
                        3 to "列表",
                    ),
                )
            }
        }
    }
    if (showPicacgCategories && provider == SearchProvider.PICACG) {
        PicacgCategoryPickerDialog(
            selected = picacgCategories,
            onApply = {
                vm.setPicacgCategories(it)
                showPicacgCategories = false
            },
            onClose = { showPicacgCategories = false },
        )
    }
    if (showPicacgLogin) com.zycomic.app.ui.picacg.PicacgLoginDialog { showPicacgLogin = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PicacgCategoryPickerDialog(
    selected: List<String>,
    onApply: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    var chosen by remember(selected) { mutableStateOf(selected.toSet()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("选择分类") },
        text = {
            Column {
                Text("选择要包含在搜索结果中的主题")
                FlowRow(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PicacgThemes.titles.forEach { title ->
                        FilterChip(
                            selected = title in chosen,
                            onClick = { chosen = if (title in chosen) chosen - title else chosen + title },
                            label = { Text(title) },
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(PicacgThemes.titles.filter { it in chosen }) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/**
 * 连载状态角标文案。服务端 serialize 实测简繁混用（"连载" / "完結"），
 * 这里统一归一成简体的 连载 / 完结；认不出来（空或别的值）就不显示角标，不硬猜。
 */
private fun serializeLabel(manga: Manga): String? = when {
    manga.state.isBlank() -> null
    manga.state.contains("完") -> "完结"
    manga.state.contains("载") || manga.state.contains("連") -> "连载"
    else -> null
}

/**
 * 连载/完结小角标。网格档贴在封面左上角、列表档放行尾，样式对齐收藏页
 * （LibraryScreen 的"未读"标记：primary 底色 + onPrimary 文字 + labelSmall）。
 * 连载用 primary，完结用中性的 surfaceVariant——完结不是错误状态，不配 error 红。
 */
@Composable
private fun SerializeBadge(text: String, modifier: Modifier = Modifier) {
    // 连载 = primary（蓝），完结 = error（红）；形状与内边距完全一致，只换配色
    val color = if (text == "连载") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val onColor = if (text == "连载") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            color = onColor,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 搜索结果漫画卡片，支持三种网格显示模式。 */
@Composable
private fun SearchMangaGridItem(
    manga: Manga,
    displayMode: Int,
    imageLoader: coil3.ImageLoader,
    picacg: Boolean,
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
            SubcomposeAsyncImage(
                model = if (picacg) manga.picx.ifBlank { manga.pic } else coverUrl(manga),
                imageLoader = imageLoader,
                contentDescription = manga.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = { com.zycomic.app.ui.components.CoverError(Modifier.fillMaxSize()) },
            )
            // 连载 / 完结：与收藏页"未读"一样放封面左上角
            serializeLabel(manga)?.let {
                SerializeBadge(it, modifier = Modifier.align(Alignment.TopStart))
            }
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

/**
 * 列表档（displayMode == 3）单行：左侧封面 + 右侧书名/作者/简介，连载·完结放在最右面。
 * 结构对齐收藏页的列表项（封面 3:4、名称加粗两行、次要信息一行、行尾标记组）。
 */
@Composable
private fun SearchMangaListRow(
    manga: Manga,
    imageLoader: coil3.ImageLoader,
    picacg: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubcomposeAsyncImage(
            model = if (picacg) manga.picx.ifBlank { manga.pic } else coverUrl(manga),
            imageLoader = imageLoader,
            contentDescription = manga.name,
            modifier = Modifier
                .width(66.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
            error = { com.zycomic.app.ui.components.CoverError(Modifier.fillMaxSize()) },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 6.dp),
        ) {
            Text(
                text = manga.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (manga.author.isNotBlank()) {
                Text(
                    text = manga.author,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (manga.text.isNotBlank()) {
                Text(
                    text = manga.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // 连载 / 完结：按需求放在最右面（收藏页列表项的标记组同位置）
        serializeLabel(manga)?.let {
            SerializeBadge(it, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * 搜索历史：关键词为空时整页显示历史列表（最新在前）。
 * 历史词以可换行胶囊显示；正常模式点击胶囊搜索，删除模式通过尾部独立按钮单条删除。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistorySection(vm: SearchViewModel) {
    val history by vm.searchHistory.collectAsState()
    val deleting by vm.historyDeleting.collectAsState()

    if (history.isEmpty()) {
        EmptyView(if (deleting) "没有可删除的搜索记录" else "还没有搜索记录")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    if (deleting) "删除搜索记录（点条目右侧 ❌）" else "搜索历史",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (deleting) {
                    TextButton(onClick = { vm.clearHistory() }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                history.forEach { k ->
                    Row(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = !deleting) { vm.searchHistoryItem(k) }
                            .padding(start = 12.dp, end = if (deleting) 2.dp else 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = k,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (deleting) {
                            IconButton(onClick = { vm.removeHistoryItem(k) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "删除记录 $k",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
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
    initialProvider: SearchProvider? = null,
    onClose: () -> Unit,
    onOpenManga: (String) -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SearchScreen(
            initialKeyword = initialKeyword,
            initialProvider = initialProvider,
            onClose = onClose,
            onOpenManga = onOpenManga,
        )
    }
}
