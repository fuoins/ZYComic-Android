package com.zycomic.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.zycomic.app.ui.browse.CompactBrowseFilterChip
import kotlinx.coroutines.launch

@androidx.compose.runtime.Composable
fun YuzongRankContent(
    onSearchTitle: (String) -> Unit,
    modifier: Modifier = Modifier,
    gridColumns: Int = 3,
    displayMode: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var platform by remember { mutableStateOf(PlatformPrefs.loadPlatform(context)) }
    var subId by remember { mutableStateOf(PlatformPrefs.loadSub(context, platform)) }
    var items by remember { mutableStateOf<List<PlatformRankItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    fun reload(p: PlatformMeta, s: String) {
        loading = true
        error = null
        scope.launch {
            runCatching { PlatformRankRepo.fetch(p, s) }
                .onSuccess {
                    items = it
                    loading = false
                }
                .onFailure {
                    error = it.message ?: "加载失败"
                    loading = false
                }
        }
    }

    androidx.compose.runtime.LaunchedEffect(platform, subId) {
        reload(platform, subId)
    }

    Column(modifier = modifier.fillMaxSize()) {
        PlatformChipRow(
            selected = platform,
            onSelect = { p ->
                platform = p
                subId = p.defaultSub
                PlatformPrefs.save(context, p, subId)
            },
            onInfoClick = { showInfo = true },
        )
        SubChipRow(
            platform = platform,
            selectedSub = subId,
            onSelect = { s ->
                subId = s
                PlatformPrefs.save(context, platform, s)
            },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                error != null -> ErrorState(
                    message = error!!,
                    onRetry = { reload(platform, subId) },
                )

                items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无榜单数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> RankGrid(items = items, onSearchTitle = onSearchTitle, gridColumns = gridColumns, displayMode = displayMode)
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("说明") },
            text = {
                Text("所有排行榜会在打开或切换平台/分类时自动抓取最新官方数据。点击漫画会通过非官方图源进行搜索查询，不直接跳转漫画详情页；其他 Tab 正常跳转。")
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("确认") } },
        )
    }
}

@Composable
private fun PlatformChipRow(
    selected: PlatformMeta,
    onSelect: (PlatformMeta) -> Unit,
    onInfoClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlatformCatalog.platforms.forEach { p ->
            CompactBrowseFilterChip(
                selected = p.id == selected.id,
                onClick = { onSelect(p) },
                label = { Text(p.label) },
            )
        }
        IconButton(onClick = onInfoClick, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "宇总排行说明",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubChipRow(
    platform: PlatformMeta,
    selectedSub: String,
    onSelect: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scrollFraction by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val maxIndex = (layout.totalItemsCount - layout.visibleItemsInfo.size).coerceAtLeast(1)
            val firstItem = layout.visibleItemsInfo.firstOrNull()
            (
                listState.firstVisibleItemIndex.toFloat() / maxIndex +
                    (firstItem?.let { listState.firstVisibleItemScrollOffset.toFloat() / it.size / maxIndex } ?: 0f)
                ).coerceIn(0f, 1f)
        }
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(platform.subs.size) { i ->
            val sub = platform.subs[i]
            CompactBrowseFilterChip(
                selected = sub.id == selectedSub,
                onClick = { onSelect(sub.id) },
                label = { Text(sub.label) },
            )
        }
    }
    if (listState.canScrollBackward || listState.canScrollForward) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.width(32.dp).height(2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            ) {
                Box(
                    modifier = Modifier.offset(x = 19.dp * scrollFraction)
                        .width(13.dp).height(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)),
                )
            }
        }
    }
}

@Composable
private fun RankGrid(
    items: List<PlatformRankItem>,
    onSearchTitle: (String) -> Unit,
    gridColumns: Int,
    displayMode: Int,
) {
    LazyVerticalGrid(
        columns = if (displayMode == 3) {
            GridCells.Fixed(1)
        } else if (gridColumns == 0) {
            GridCells.Adaptive(128.dp)
        } else {
            GridCells.Fixed(gridColumns.coerceIn(1, 10))
        },
        contentPadding = PaddingValues(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = items, key = { it.rank }) { item ->
            RankCell(item = item, displayMode = displayMode, onClick = { onSearchTitle(item.title) })
        }
    }
}

@Composable
private fun RankCell(item: PlatformRankItem, displayMode: Int, onClick: () -> Unit) {
    if (displayMode == 3) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(68.dp, 96.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (item.coverUrl.isNotEmpty()) PlatformCoverImage(url = item.coverUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize())
                RankBadge(rank = item.rank, modifier = Modifier.align(Alignment.TopStart))
            }
            Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (item.coverUrl.isNotEmpty()) {
                PlatformCoverImage(
                    url = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            RankBadge(rank = item.rank, modifier = Modifier.align(Alignment.TopStart))
            if (displayMode == 0) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.62f)).padding(6.dp),
                )
            }
        }
        if (displayMode == 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    val bg = when (rank) {
        1 -> Color(0xFFFFC107)
        2 -> Color(0xFFB0BEC5)
        3 -> Color(0xFFCD7F32)
        else -> Color(0x80000000)
    }
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = rank.toString(),
            color = if (rank <= 3) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlatformCoverImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val loader = remember(context) { PlatformImageLoader.get(context) }
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        imageLoader = loader,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "加载失败：$message",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}
