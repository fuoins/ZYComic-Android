package com.zycomic.app.ui.manga

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.SubcomposeAsyncImage
import coil3.imageLoader
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.reader.ReaderLauncher
import com.zycomic.app.ui.components.EmptyView
import com.zycomic.app.ui.components.ErrorView
import com.zycomic.app.ui.components.coverUrl
import com.zycomic.app.ui.login.ManwaDetailLoginDialog
import com.zycomic.app.ui.settings.AddBlacklistDialog
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.drawHorizontalScrollbar

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MangaDetailScreen(
    bookId: String,
    onClose: () -> Unit,
    onOpenManga: (String) -> Unit,
    onSearchKeyword: (String) -> Unit,
    onTagClick: (String) -> Unit,
) {
    val context = LocalContext.current
    // 必须带 bookId 作为 key：相关推荐/搜索结果的漫画点击后只会换 bookId，
    // 不带 key 时 remember 会复用上一本的 ViewModel（页面停留在旧漫画，表现为"点了不跳转"），
    // 下面所有收集 vm 事件的 LaunchedEffect 也必须以 vm 为 key，否则会连着旧实例的流。
    val jmSessionRevision by JmAuthStore.sessionRevisionFlow.collectAsState()
    val detailSession = if (bookId.startsWith("jm:")) jmSessionRevision else null
    val vm = remember(bookId, detailSession) { MangaDetailViewModel(bookId, context.applicationContext) }
    DisposableEffect(vm) { onDispose { vm.dispose() } }
    val detail by vm.detail.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val chapterWarning by vm.chapterWarning.collectAsState()
    val chapterAsc by vm.chapterAsc.collectAsState()
    val folders by vm.folders.collectAsState()
    val needLogin by vm.needLogin.collectAsState()
    val addingBlacklist by vm.addingBlacklist.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                vm.syncChapterReadStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm, detail?.chapterList?.size) {
        if (detail != null) vm.syncChapterReadStatus()
    }
    val detailTags = remember(detail?.tags) {
        detail?.tags.orEmpty().map { it.name }.filter { it.isNotBlank() }.distinct()
    }

    val scope = rememberCoroutineScope()
    val jmImageLoader = if (vm.isJm) {
        com.zycomic.app.ui.components.JmCoverImageLoader.get(context)
    } else {
        context.imageLoader
    }

    /** 通过 komikku 原生阅读器打开指定章节。chapterId 为字符串数字。 */
    fun openReader(chapterId: String, page: Int = 0) {
        val d = detail ?: return
        if (chapterId.isBlank() || (vm.isJm && d.chapterList.none { it.id == chapterId })) {
            Toast.makeText(context, "没有有效的可读章节，请重试加载详情", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            try {
                ReaderLauncher.launch(
                    context,
                    d,
                    chapterId,
                    page = page,
                    sourceId = if (vm.isJm) com.zycomic.app.reader.JmSource.id else com.zycomic.app.reader.ManwaSource.id,
                )
            } catch (e: Exception) {
                Toast.makeText(context, "打开阅读器失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    var showManwaLogin by remember(bookId) { mutableStateOf(false) }
    var showFolderDialog by remember(bookId) { mutableStateOf(false) }
    var showCoverMenu by remember(bookId) { mutableStateOf(false) }
    var showCoverPreview by remember(bookId) { mutableStateOf(false) }
    var showAddBlacklistDialog by remember(bookId) { mutableStateOf(false) }
    var authorsExpanded by rememberSaveable(bookId) { mutableStateOf(false) }
    var titleExpanded by rememberSaveable(bookId) { mutableStateOf(false) }
    /** 屏蔽二次确认：服务端接口是 toggle，必须让用户知道重复点击会变成取消屏蔽 */
    var showBlockConfirm by remember(bookId) { mutableStateOf(false) }
    /** 屏蔽请求进行中：连点会真的连着 toggle 两次（等于没变化），所以期间屏蔽入口点击 */
    val blockBusy by vm.blockBusy.collectAsState()

    // Cover actions use the same platform-aware byte loader as the thumbnail.
    fun coverAction(share: Boolean) {
        val d = detail ?: return
        showCoverMenu = false
        scope.launch {
            try {
                val bytes = com.zycomic.app.net.CoverArtHelper.fetchCover(coverUrl(d), isJm = vm.isJm)
                if (share) {
                    val uri = com.zycomic.app.net.CoverArtHelper.shareToCache(bytes, d.name)
                    context.startActivity(uri.toShareIntent(context))
                } else {
                    com.zycomic.app.net.CoverArtHelper.save(bytes, d.name)
                    Toast.makeText(context, "封面已保存到相册", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(vm, needLogin) {
        if (needLogin) {
            showFolderDialog = false
            showBlockConfirm = false
            showAddBlacklistDialog = false
            showManwaLogin = true
            vm.needLogin.value = false
        }
    }

    LaunchedEffect(vm) {
        vm.favSuccess.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(vm) {
        vm.blockMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(vm) {
        vm.blacklistMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 进入动画：从底部滑入 + 淡入
    var animStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animStarted = true }
    val slideY by animateFloatAsState(
        targetValue = if (animStarted) 0f else 400f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 280, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "detailSlideY",
    )
    val fadeAlpha by animateFloatAsState(
        targetValue = if (animStarted) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "detailFadeAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .graphicsLayer {
                translationY = slideY
                alpha = fadeAlpha
            },
    ) {
        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            error != null -> ErrorView(message = error ?: "", onRetry = { vm.load() })
            detail == null -> EmptyView()
            else -> {
                val d = detail!!
                val scrollState = rememberScrollState()
                val scope = rememberCoroutineScope()
                var recommendY by remember { mutableStateOf(0) }

                // 1. 模糊封面背景
                MangaDetailBackdrop(
                    coverModel = coverUrl(d),
                    imageLoader = jmImageLoader,
                    modifier = Modifier.matchParentSize(),
                )

                // 内容主体
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    // 头部：封面 + 信息（顶部预留顶栏高度，内容延伸到状态栏下实现全屏）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 56.dp)
                            .padding(top = 56.dp, start = 16.dp, end = 16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        SubcomposeAsyncImage(
                            model = coverUrl(d),
                            contentDescription = d.name,
                            imageLoader = jmImageLoader,
                            modifier = Modifier
                                .width(100.dp)
                                .aspectRatio(2f / 3f)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(onClick = { showCoverPreview = true }, onLongClick = { showCoverMenu = true }),
                            contentScale = ContentScale.Crop,
                            error = { com.zycomic.app.ui.components.CoverError(Modifier.fillMaxSize()) },
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = d.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = detailTitleMaxLines(titleExpanded),
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onSearchKeyword(d.name) },
                            )
                            Spacer(Modifier.height(8.dp))
                            val authors = (if (vm.isJm) d.jmAuthors else emptyList())
                                .ifEmpty { listOf(d.author) }
                                .map(String::trim)
                                .filter(String::isNotBlank)
                                .distinct()
                            val visibleAuthors = detailAuthorsForDisplay(authors, authorsExpanded)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FlowRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    visibleAuthors.forEach { author ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                                .clickable { onSearchKeyword(author) }
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.PersonOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                author,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    stringResource(if (titleExpanded) KMR.strings.detail_title_collapse else KMR.strings.detail_title_expand),
                                    modifier = Modifier
                                        .clickable { titleExpanded = !titleExpanded }
                                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (authors.size > 1) {
                                Text(
                                    stringResource(if (authorsExpanded) KMR.strings.detail_authors_collapse else KMR.strings.detail_authors_expand),
                                    modifier = Modifier
                                        .clickable { authorsExpanded = !authorsExpanded }
                                        .padding(top = 4.dp, bottom = 2.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (shouldShowJmAuthorNote(vm.isJm, authorsExpanded)) {
                                Text(
                                    stringResource(KMR.strings.jm_author_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            if (vm.isJm) {
                                Text(
                                    stringResource(KMR.strings.jm_album_id, d.id),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (d.jmViews != null) {
                                    Text(stringResource(KMR.strings.jm_views, d.jmViews.toString()), style = MaterialTheme.typography.bodySmall)
                                }
                                if (d.jmUpdateTime.isNotBlank()) {
                                    Text(stringResource(KMR.strings.jm_updated, d.jmUpdateTime), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            // Manwa-only status and region fields stay isolated.
                            if (!vm.isJm) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (d.state == "完结") Icons.Filled.DoneAll else Icons.Outlined.Schedule,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        d.state.ifBlank { "未知" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "·",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val src = listOf(d.categoryName, areaText(d.bookArea))
                                        .filter { it.isNotBlank() }
                                        .joinToString("·")
                                    Text(
                                        src.ifBlank { "未知" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (vm.isJm) {
                        JmDetailActions(
                            vm = vm,
                            manga = d,
                            onSaveCover = { coverAction(false) },
                            onShareCover = { coverAction(true) },
                        )
                    }
                    // Manwa account actions must never be used for JM.
                    if (!vm.isJm) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val isFav = d.fav == 1
                            // 收藏按钮：未收藏点击→添加到全部收藏夹；已收藏点击→取消收藏
                            ActionItem(
                                modifier = Modifier.weight(1f),
                                icon = if (isFav) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                label = if (isFav) "已收藏" else "未收藏",
                                tint = if (isFav) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                onClick = { vm.toggleFavorite() },
                            )
                            // 添加到全部收藏夹：已收藏时toast提示
                            ActionItem(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.BookmarkAdd,
                                label = "全部收藏夹",
                                tint = MaterialTheme.colorScheme.onSurface,
                                onClick = {
                                    if (vm.ensureAccountLogin()) {
                                        if (isFav) {
                                            Toast.makeText(context, "已收藏请勿再次点击收藏", Toast.LENGTH_SHORT).show()
                                        } else {
                                            vm.addToAllFolders()
                                        }
                                    }
                                },
                            )
                            // 添加到其他收藏夹：已收藏时toast提示
                            ActionItem(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Outlined.CreateNewFolder,
                                label = "其他收藏夹",
                                tint = MaterialTheme.colorScheme.onSurface,
                                onClick = {
                                    if (vm.ensureAccountLogin()) {
                                        if (isFav) {
                                            Toast.makeText(context, "已收藏请勿再次点击收藏", Toast.LENGTH_SHORT).show()
                                        } else {
                                            vm.openFolderPicker()
                                            showFolderDialog = true
                                        }
                                    }
                                },
                            )
                            // 屏蔽：按需求放在"其他收藏夹"右侧。服务端接口是开关式 toggle，
                            // 文案固定叫"屏蔽"，本次到底是屏蔽还是取消，以 blockMessage 的 toast 为准。
                            ActionItem(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Outlined.Block,
                                label = "屏蔽",
                                tint = MaterialTheme.colorScheme.onSurface,
                                onClick = { if (!blockBusy && vm.ensureAccountLogin()) showBlockConfirm = true },
                            )
                        }

                        if (showBlockConfirm) {
                            AlertDialog(
                                onDismissRequest = { showBlockConfirm = false },
                                title = { Text("屏蔽这本漫画？") },
                                text = {
                                    Text(
                                        "屏蔽后这本漫画会从分类、搜索、排行里隐藏。\n\n" +
                                            "注意：该漫画已在屏蔽列表时，重复点击会取消屏蔽。",
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showBlockConfirm = false
                                            vm.toggleBlock()
                                        },
                                    ) { Text("确认") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showBlockConfirm = false }) { Text("取消") }
                                },
                            )
                        }
                    }

                    // 3. 可展开简介
                    if (d.text.isNotBlank()) {
                        MangaDetailDescription(text = d.text)
                    }

                    // JM groups retain their authoritative namespace and each starts a new row.
                    if (vm.isJm) {
                        JmTagGroup(stringResource(KMR.strings.jm_tags), d.tags.map { it.name }, onSearchKeyword)
                        JmTagGroup(stringResource(KMR.strings.jm_actors), d.jmActors, onSearchKeyword)
                        JmTagGroup(stringResource(KMR.strings.jm_works), d.jmWorks, onSearchKeyword)
                    }
                    // Manwa tags keep their existing behavior.
                    if (!vm.isJm && d.tags.isNotEmpty()) {
                        MangaDetailTagChips(
                            tags = d.tags.map { it.name },
                            onTagClick = { tag ->
                                onTagClick(tag)
                                Toast.makeText(context, "已根据${tag}标签进行搜索", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            trailingContent = {
                                val blockChipShape = RoundedCornerShape(8.dp)
                                Box(
                                    modifier = Modifier
                                        .clip(blockChipShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                        .border(1.dp, MaterialTheme.colorScheme.primary, blockChipShape)
                                        .clickable {
                                            if (vm.ensureAccountLogin()) {
                                                showAddBlacklistDialog = true
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            "屏蔽标签+",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                        )
                    }

                    // 5. 章节列表头
                    val chapters = vm.sortedChapters()
                    if (chapterWarning != null || (vm.isJm && chapters.isEmpty())) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                text = chapterWarning ?: "暂无可读章节（series 为空）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { vm.load() }) { Text("重试加载章节") }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "共 ${chapters.size} 章",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier = Modifier.clickable { vm.toggleChapterSort() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.SwapVert,
                                contentDescription = "切换排序",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (chapterAsc) "升序" else "降序",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    MangaDetailChapterGrid(
                        chapters = chapters,
                        onChapterClick = { chapter -> openReader(chapter.id) },
                    )

                    // 6. 相关推荐
                    if (!vm.isJm || d.loveList.isNotEmpty()) {
                        Text(
                            "相关推荐",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                                .onGloballyPositioned { coordinates ->
                                    val parent = coordinates.parentLayoutCoordinates
                                    if (parent != null) recommendY = parent.localPositionOf(coordinates, Offset.Zero).y.toInt()
                                },
                        )
                        if (d.loveList.isEmpty()) {
                            Text(
                                "暂无相关推荐",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            val lazyRowState = rememberLazyListState()
                            LazyRow(
                                state = lazyRowState,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 16.dp,
                                    vertical = 8.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawHorizontalScrollbar(lazyRowState),
                            ) {
                                items(d.loveList) { m ->
                                    MangaDetailRecommendationCard(manga = m, imageLoader = jmImageLoader, onClick = {
                                        onOpenManga(relatedMangaRoute(m.id, vm.isJm))
                                    })
                                }
                            }
                        }
                    }

                    // 底部留白，避免被 FAB 遮挡
                    Spacer(Modifier.height(96.dp))
                }

                // 7. 顶部栏始终透明；statusBarsPadding 保留状态栏安全区域。
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(Modifier.weight(1f))
                    if (!vm.isJm || detail?.loveList?.isNotEmpty() == true) {
                        TextButton(onClick = {
                            scope.launch { scrollState.animateScrollTo(recommendY) }
                        }) {
                            Text(
                                "相关推荐",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        // 9. FAB 继续阅读
        if (detail != null && !loading && error == null && (!vm.isJm || detail!!.chapterList.isNotEmpty())) {
            FloatingActionButton(
                onClick = {
                    val chapterId = vm.defaultChapterId()
                    openReader(chapterId, vm.resumePage(chapterId))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "开始阅读",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }

    if (showManwaLogin) {
        ManwaDetailLoginDialog(onDismiss = { showManwaLogin = false })
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

    if (showCoverMenu) {
        CoverActionDialog(
            onDismiss = { showCoverMenu = false },
            onSave = { coverAction(false) },
            onShare = { coverAction(true) },
        )
    }
    if (showCoverPreview && detail != null) {
        DetailCoverPreview(
            title = detail!!.name,
            url = coverUrl(detail!!),
            imageLoader = jmImageLoader,
            onDismiss = { showCoverPreview = false },
            onSave = { coverAction(false) },
            onShare = { coverAction(true) },
        )
    }
    if (!vm.isJm && showAddBlacklistDialog) {
        AddBlacklistDialog(
            tags = detailTags,
            adding = addingBlacklist,
            onDismiss = { showAddBlacklistDialog = false },
            onSubmit = { tags ->
                vm.submitAddBlacklist(tags) { showAddBlacklistDialog = false }
            },
        )
    }
}

private fun areaText(area: String): String = area.ifEmpty { "未知" }

/** 操作按钮行中的单个条目（图标 + 文字，weight(1f)）。 */
@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = tint,
        )
    }
}

/** 封面点击弹出的简化操作 Dialog。 */
@Composable
private fun CoverActionDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 8.dp),
        ) {
            Text(
                "保存封面",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSave)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                "分享",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShare)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                "取消",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun FolderPickDialog(
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding(),
        ) {
            // 顶部标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "选择收藏夹",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
            HorizontalDivider()

            // 收藏夹列表
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                // 默认收藏夹
                item {
                    FolderRow(
                        icon = Icons.Filled.Bookmark,
                        name = "默认收藏夹",
                        count = null,
                        onClick = { onPick(0) },
                    )
                }
                // 用户收藏夹
                items(folders) { f ->
                    FolderRow(
                        icon = Icons.Outlined.Folder,
                        name = f.name,
                        count = f.count,
                        onClick = { onPick(f.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    icon: ImageVector,
    name: String,
    count: Int?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                "$count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
) {
    if (bookId.startsWith("picacg:")) {
        com.zycomic.app.ui.picacg.PicacgDetailOverlay(bookId, onClose)
    } else {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onClose,
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            MangaDetailScreen(
                bookId = bookId,
                onClose = onClose,
                onOpenManga = onOpenManga,
                onSearchKeyword = onSearchKeyword,
                onTagClick = onTagClick,
            )
        }
    }
}
