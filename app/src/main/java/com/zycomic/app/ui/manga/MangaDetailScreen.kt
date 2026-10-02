package com.zycomic.app.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import coil3.compose.AsyncImage
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.reader.ReaderLauncher
import com.zycomic.app.ui.components.EmptyView
import com.zycomic.app.ui.components.LoadingFooter
import com.zycomic.app.ui.components.MangaCard
import com.zycomic.app.ui.theme.BluePrimary
import com.zycomic.app.ui.theme.OffWhite
import com.zycomic.app.ui.theme.TextSecondary
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun MangaDetailScreen(
    bookId: String,
    onClose: () -> Unit,
    onOpenManga: (String) -> Unit,
    onSearchKeyword: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onRequireLogin: () -> Unit,
) {
    val vm = remember { MangaDetailViewModel(bookId) }
    val detail by vm.detail.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val activeTab by vm.activeTab.collectAsState()
    val chapterAsc by vm.chapterAsc.collectAsState()
    val folders by vm.folders.collectAsState()
    val needLogin by vm.needLogin.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 通过 komikku 原生阅读器打开指定章节。chapterId 为字符串数字。 */
    fun openReader(chapterId: String) {
        val d = detail ?: return
        if (chapterId.isBlank()) return
        scope.launch {
            try {
                ReaderLauncher.launch(context, d, chapterId)
            } catch (e: Exception) {
                Toast.makeText(context, "打开阅读器失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    var showFolderDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(needLogin) {
        if (needLogin) {
            onRequireLogin()
            vm.needLogin.value = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        when {
            loading -> LoadingFooter()
            error != null -> com.zycomic.app.ui.components.ErrorView(
                message = error ?: "", onRetry = { vm.load() },
            )
            detail == null -> EmptyView()
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 顶部栏
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        text = detail!!.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSearchKeyword(detail!!.name) },
                    )
                }

                // 封面 + 基本信息
                Row(modifier = Modifier.padding(16.dp)) {
                    val url = com.zycomic.app.ui.components.coverUrl(detail!!)
                    AsyncImage(
                        model = url,
                        contentDescription = detail!!.name,
                        modifier = Modifier
                            .size(width = 100.dp, height = 140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(OffWhite),
                    )
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                            detail!!.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val author = detail!!.author
                        if (author.isNotBlank()) {
                            Text(
                                "作者：$author",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BluePrimary,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clickable { onSearchKeyword(author) },
                            )
                        }
                        Text(
                            "类别：${detail!!.categoryName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "地区：${areaText(detail!!.bookArea)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "状态：${detail!!.state.ifBlank { "未知" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                // 收藏按钮（点击切换收藏/取消收藏，带状态动画）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val isFav = detail!!.fav == 1
                    androidx.compose.animation.AnimatedVisibility(visible = isFav) {
                        Text("已收藏", style = MaterialTheme.typography.bodyMedium, color = BluePrimary, modifier = Modifier.padding(end = 8.dp))
                    }
                    IconButton(onClick = { vm.toggleFavorite() }) {
                        Icon(
                            if (isFav) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "收藏",
                            tint = if (isFav) BluePrimary else TextSecondary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                // 简介
                if (detail!!.text.isNotBlank()) {
                    Text(
                        detail!!.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                // 标签
                if (detail!!.tags.isNotEmpty()) {
                    com.zycomic.app.ui.components.FlowChipRow {
                        detail!!.tags.forEach { tag ->
                            com.zycomic.app.ui.components.FilterChip(
                                text = tag.name,
                                selected = false,
                                onClick = { onTagClick(tag.name) },
                            )
                        }
                    }
                }

                // Tab
                TabRow(selectedTabIndex = activeTab) {
                    Tab(selected = activeTab == 0, onClick = { vm.selectTab(0) }, text = { Text("章节") })
                    Tab(selected = activeTab == 1, onClick = { vm.selectTab(1) }, text = { Text("相关推荐") })
                }

                if (activeTab == 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (chapterAsc) "升序" else "降序",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                        IconButton(onClick = { vm.toggleChapterSort() }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "切换排序")
                        }
                    }
                    val chapters = vm.sortedChapters()
                    chapters.forEach { ch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openReader(ch.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(ch.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (ch.readed == 1) {
                                Text("已读", style = MaterialTheme.typography.bodySmall, color = BluePrimary)
                            }
                        }
                    }
                } else {
                    val related = detail!!.loveList
                    if (related.isEmpty()) {
                        EmptyView("暂无相关推荐", modifier = Modifier.padding(32.dp))
                    } else {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            items(related.size) { i ->
                                MangaCard(manga = related[i], onClick = { onOpenManga(related[i].id) })
                            }
                        }
                    }
                }
            }
        }

        // 悬浮播放按钮
        if (detail != null && !loading) {
            FloatingActionButton(
                onClick = { openReader(vm.defaultChapterId()) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                containerColor = BluePrimary,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "开始阅读", tint = Color.White)
            }
        }
    }

    if (showFolderDialog) {
        FolderPickDialog(
            folders = folders,
            onDismiss = { showFolderDialog = false },
            onPick = { folderId ->
                vm.addToFolder(folderId)
                showFolderDialog = false
            },
        )
    }
}

private fun areaText(area: String): String = area.ifEmpty { "未知" }

@Composable
private fun FolderPickDialog(
    folders: List<com.zycomic.app.data.dto.Folder>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(16.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            Text("选择收藏夹", style = MaterialTheme.typography.titleMedium)
            Text(
                "默认收藏夹",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onPick(0) }.padding(vertical = 12.dp),
            )
            folders.forEach { f ->
                Text(
                    "${f.name} (${f.count})",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(vertical = 12.dp),
                )
            }
        }
    }
}

/** 详情覆盖层（full-screen Dialog）。 */
@Composable
fun MangaDetailOverlay(
    bookId: String,
    onClose: () -> Unit,
    onOpenManga: (String) -> Unit,
    onSearchKeyword: (String) -> Unit,
    onTagClick: (String) -> Unit,
    onRequireLogin: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        MangaDetailScreen(
            bookId = bookId,
            onClose = onClose,
            onOpenManga = onOpenManga,
            onSearchKeyword = onSearchKeyword,
            onTagClick = onTagClick,
            onRequireLogin = onRequireLogin,
        )
    }
}
