package com.zycomic.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.zycomic.app.data.repository.BookBlockRepository
import com.zycomic.app.ui.components.CoverError
import com.zycomic.app.ui.components.coverUrl
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.util.selectedBackground

/**
 * 漫画屏蔽页面：过滤屏蔽漫画开关 + 添加说明 + 删除已屏蔽漫画。
 *
 * 与 [TagBlockScreen] 是同构的第二套屏蔽，差别在于屏蔽对象从"标签"变成"单本漫画"，
 * 且服务端的屏蔽接口是**开关（toggle）**：同一请求连调两次等于屏蔽再取消。
 * 因此本页所有"取消屏蔽"操作都必须走真实列表里的 id，不能凭猜测调用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookBlockScreen(
    vm: SettingsViewModel,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("漫画屏蔽") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { contentPadding ->
        BookBlockContent(vm = vm, modifier = Modifier.padding(contentPadding))
    }
}

@Composable
fun BookBlockContent(vm: SettingsViewModel, modifier: Modifier = Modifier) {
    val bookFilterEnabled by vm.bookFilterEnabled.collectAsState()
    val blockedBooks by vm.blockedBooks.collectAsState()
    var showAddHint by remember { mutableStateOf(false) }
    var showUnblock by remember { mutableStateOf(false) }

    // 进入页面即拉一次列表，"删除屏蔽漫画"的副标题要显示真实条数
    LaunchedEffect(Unit) { vm.loadBlockedBooks() }

    ScrollbarLazyColumn(modifier = modifier) {
        item {
            SwitchPreferenceWidget(
                title = "过滤屏蔽漫画",
                subtitle = "隐藏被屏蔽的漫画（关闭后正常会显示「已屏蔽此漫画」占位）。与「过滤屏蔽标签」互不影响。",
                checked = bookFilterEnabled,
                onCheckedChanged = { vm.setBookFilterEnabled(it) },
            )
        }
        item {
            TextPreferenceWidget(
                title = "添加屏蔽漫画",
                subtitle = "在漫画详情页点击「屏蔽」按钮",
                onPreferenceClick = { showAddHint = true },
            )
        }
        item {
            TextPreferenceWidget(
                title = "删除屏蔽漫画",
                subtitle = "已屏蔽 ${blockedBooks.size} 本漫画",
                onPreferenceClick = { showUnblock = true },
            )
        }
    }

    if (showAddHint) {
        AddBookBlockHintDialog(onDismiss = { showAddHint = false })
    }
    if (showUnblock) {
        BookUnblockDialog(vm = vm, onDismiss = { showUnblock = false })
    }
}

// ==================== 添加屏蔽漫画：说明弹窗 ====================

@Composable
private fun AddBookBlockHintDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加屏蔽漫画") },
        text = {
            Text(
                "打开任意漫画的详情页，在收藏按钮那一行最右侧点击「屏蔽」，确认后即加入屏蔽列表。\n\n" +
                    "注意：服务端的屏蔽接口是开关式的——如果这本漫画已经在屏蔽列表里，" +
                    "再次点击「屏蔽」会变成取消屏蔽。需要明确取消时，建议到「删除屏蔽漫画」里操作。",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确认") }
        },
    )
}

// ==================== 删除屏蔽漫画 ====================

@Composable
private fun BookUnblockDialog(
    vm: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val books by vm.blockedBooks.collectAsState()
    val loading by vm.loadingBooks.collectAsState()
    val removing by vm.removingBooks.collectAsState()

    var keyword by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var allSelected by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val filtered = remember(books, keyword) {
        if (keyword.isBlank()) books else books.filter { it.name.contains(keyword, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "删除屏蔽漫画（已选 ${selected.size}）",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(16.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            OutlinedTextField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    allSelected = false
                },
                placeholder = { Text("搜索已屏蔽漫画名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        allSelected = !allSelected
                        selected = if (allSelected) {
                            filtered.map { it.id }.toSet()
                        } else {
                            selected - filtered.map { it.id }.toSet()
                        }
                    },
                    enabled = filtered.isNotEmpty(),
                ) { Text(if (allSelected) "取消全选" else "全选(筛出${filtered.size})") }
                TextButton(
                    onClick = { confirmClearAll = true },
                    enabled = books.isNotEmpty() && !removing,
                ) {
                    Text("清空全部(${books.size})", color = MaterialTheme.colorScheme.error)
                }
            }

            when {
                loading && books.isEmpty() -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  加载中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                books.isEmpty() -> Text(
                    "暂无屏蔽漫画",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(filtered, key = { it.id }) { book ->
                        BookBlockRow(
                            book = book,
                            selected = selected.contains(book.id),
                            onClickRow = {
                                selected = if (selected.contains(book.id)) {
                                    selected - book.id
                                } else {
                                    selected + book.id
                                }
                                allSelected = false
                            },
                            onClickDelete = { vm.submitUnblockBooks(listOf(book)) {} },
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onDismiss) { Text("关闭") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = selected.isNotEmpty() && !removing,
                    onClick = {
                        val targets = books.filter { selected.contains(it.id) }
                        vm.submitUnblockBooks(targets) {
                            selected = emptySet()
                            allSelected = false
                        }
                    },
                ) { Text(if (removing) "删除中..." else "删除选中 (${selected.size})") }
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全部屏蔽漫画？") },
            text = {
                Text(
                    "将逐个取消 ${books.size} 本漫画的屏蔽，服务端没有批量接口，需要逐条提交，" +
                        "中途失败会保留已成功的部分。该操作不会删除收藏与历史记录。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearAll = false
                        vm.submitUnblockBooks(books.toList()) {
                            selected = emptySet()
                            allSelected = false
                        }
                    },
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 列表行：沿用历史页 [com.zycomic.app.ui.history.HistoryScreen] 的行样式
 * （封面 3:4 + 名称 + 次要信息 + 行尾图标），按需求去掉爱心（收藏）按钮，只保留删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookBlockRow(
    book: BookBlockRepository.BlockedBook,
    selected: Boolean,
    onClickRow: () -> Unit,
    onClickDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .selectedBackground(selected)
            .combinedClickable(onClick = onClickRow, onLongClick = onClickRow)
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SubcomposeAsyncImage(
            model = coverUrl(book.coverUrl),
            contentDescription = book.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = onClickRow, onLongClick = onClickRow),
            error = { CoverError(Modifier.fillMaxSize()) },
            loading = {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = book.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "bookId：${book.id}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        IconButton(onClick = onClickDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "取消屏蔽",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
