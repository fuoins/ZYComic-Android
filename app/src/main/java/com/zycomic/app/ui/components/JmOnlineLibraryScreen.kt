package com.zycomic.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.zycomic.app.data.repository.JmOnlineLibraryItem
import com.zycomic.app.data.repository.JmOnlineLibraryPolicy
import com.zycomic.app.data.repository.JmOnlineLibraryRepository
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.net.JmRouteManager
import kotlinx.coroutines.CancellationException

/** Account history is album-level only; no remote mutation or invented reading timestamps. */
@Composable
fun JmOnlineLibraryScreen(
    history: Boolean,
    onRequireLogin: () -> Unit,
    onOpenManga: (String) -> Unit,
    onTotalChanged: (Int?) -> Unit = {},
    onLoadedCountChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val loggedIn by JmAuthStore.loggedInFlow.collectAsState()
    val accountName by JmAuthStore.accountNameFlow.collectAsState()
    key(loggedIn, accountName, history) {
        JmOnlineLibraryContent(history, loggedIn, onRequireLogin, onOpenManga, onTotalChanged, onLoadedCountChanged, modifier)
    }
}

@Composable
private fun JmOnlineLibraryContent(
    history: Boolean,
    loggedIn: Boolean,
    onRequireLogin: () -> Unit,
    onOpenManga: (String) -> Unit,
    onTotalChanged: (Int?) -> Unit,
    onLoadedCountChanged: (Int) -> Unit,
    modifier: Modifier,
) {
    var order by remember { mutableStateOf("mr") }
    var remoteItems by remember { mutableStateOf<List<JmOnlineLibraryItem>>(emptyList()) }
    var localItems by remember { mutableStateOf<List<JmOnlineLibraryItem>>(emptyList()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var remoteLoading by remember { mutableStateOf(false) }
    var localLoading by remember { mutableStateOf(history) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var refreshRequest by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val items = remember(history, localItems, remoteItems) {
        if (history) JmOnlineLibraryPolicy.mergeHistory(localItems, remoteItems) else remoteItems
    }
    val loading = remoteLoading || localLoading
    val visibleError = listOfNotNull(error, localError).joinToString("\n").ifBlank { null }

    suspend fun loadPage() {
        if (remoteLoading || !hasMore || !loggedIn) return
        remoteLoading = true
        error = null
        try {
            val requestedPage = page + 1
            val result = if (history) JmOnlineLibraryRepository.history(requestedPage) else JmOnlineLibraryRepository.favorites(requestedPage, order)
            val known = remoteItems.mapTo(mutableSetOf()) { it.id }
            hasMore = JmOnlineLibraryPolicy.hasMore(result.total, known, result.items.map { it.id })
            val novel = result.items.filter { known.add(it.id) }
            remoteItems = remoteItems + novel
            total = result.total
            page = requestedPage
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = if (!JmAuthStore.isLoggedIn) "请重新登录 JM 账号" else failure.message ?: "JM 列表加载失败"
        } finally {
            remoteLoading = false
        }
    }

    LaunchedEffect(history, refreshRequest) {
        if (!history) {
            localItems = emptyList()
            localLoading = false
            localError = null
            return@LaunchedEffect
        }
        localLoading = true
        localError = null
        try {
            localItems = JmOnlineLibraryRepository.localHistory()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            localError = failure.message ?: "本地 JM 阅读历史加载失败"
        } finally {
            localLoading = false
        }
    }

    // A single coroutine serializes initial load and paging. Loading changes must not cancel their own request.
    LaunchedEffect(loggedIn, order, refreshRequest) {
        remoteItems = emptyList()
        total = null
        page = 0
        hasMore = true
        error = null
        onTotalChanged(null)
        if (!loggedIn) return@LaunchedEffect
        listState.scrollToItem(0)
        loadPage()
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val loadedCount = if (history) JmOnlineLibraryPolicy.mergeHistory(localItems, remoteItems).size else remoteItems.size
            Triple(last, loadedCount, error == null)
        }.collect { (last, size, healthy) ->
            if (healthy && size > 0 && last >= (size - 3).coerceAtLeast(0) && hasMore) loadPage()
        }
    }
    LaunchedEffect(total, items.size) {
        onTotalChanged(total)
        onLoadedCountChanged(items.size)
    }

    if (!loggedIn) {
        if (!history) {
            Column(
                modifier = modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("登录 JM 账号后查看收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequireLogin) { Text("JM 登录") }
            }
            return
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (!history) {
                Text("排序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { order = if (order == "mr") "mp" else "mr" }, enabled = !loading) {
                    Text(if (order == "mr") "最新" else "最多图片", maxLines = 1)
                }
            }
            IconButton(onClick = { refreshRequest++ }, enabled = !loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新 JM${if (history) "历史" else "收藏"}")
            }
        }
        if (history && !loggedIn) {
            TextButton(onClick = onRequireLogin, modifier = Modifier.align(Alignment.End)) {
                Text("登录 JM 同步账号观看记录")
            }
        }
        val contentState = JmOnlineLibraryPolicy.contentState(
            hasItems = items.isNotEmpty(),
            loading = loading,
            error = visibleError,
        )
        when {
            history && !loggedIn && items.isEmpty() && !localLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(localError ?: "登录 JM 账号后可同步账号观看历史", color = if (localError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRequireLogin) { Text("JM 登录") }
            }
            contentState == com.zycomic.app.data.repository.JmOnlineLibraryContentState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            contentState == com.zycomic.app.data.repository.JmOnlineLibraryContentState.ERROR -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(visibleError.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refreshRequest++ }) { Text("重试") }
            }
            contentState == com.zycomic.app.data.repository.JmOnlineLibraryContentState.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (history) "暂无 JM 阅读历史" else "暂无 JM 收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items, key = { "jm-${if (history) "history" else "favorite"}-${it.id}" }) { item ->
                    JmLibraryRow(item, onClick = { onOpenManga("jm:${item.id}") })
                }
                item(key = "jm-page-footer") {
                    when {
                        loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp))
                        }
                        visibleError != null -> TextButton(onClick = { refreshRequest++ }) {
                            Text(visibleError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        }
                        !hasMore -> Text("已加载 ${items.size}${if (history) "" else total?.let { "/$it" }.orEmpty()} 条", modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun JmLibraryRow(item: JmOnlineLibraryItem, onClick: () -> Unit) {
    val imageUrl = item.image.ifBlank { "/media/albums/${item.id}_3x4.jpg" }.let { raw ->
        if (raw.startsWith("https://") || raw.startsWith("http://")) raw else "https://${JmRouteManager.activeImageDomain}/${raw.trimStart('/')}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubcomposeAsyncImage(
            model = imageUrl,
            imageLoader = JmCoverImageLoader.get(LocalContext.current),
            contentDescription = item.name,
            modifier = Modifier.width(68.dp).aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)),
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) } },
            error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("封面") } },
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            if (item.author.isNotBlank()) {
                Text(item.author, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (item.description.isNotBlank()) {
                Text(item.description, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
