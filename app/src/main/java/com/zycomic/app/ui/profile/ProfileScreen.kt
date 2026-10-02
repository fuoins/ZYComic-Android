package com.zycomic.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.repository.UserRepository
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onRequireLogin: () -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenTagBlock: () -> Unit,
    onBlockGayTags: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    onOpenDataStorage: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val user by UserRepository.userFlow.collectAsState()
    var showPointLogs by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    tachiyomi.presentation.core.components.material.Scaffold { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding) {
            item { LogoHeader() }

            // ---- 用户信息 ----
            item {
                if (user == null) {
                    TextPreferenceWidget(
                        title = "点击登录",
                        subtitle = "登录后可使用收藏、评论等功能",
                        icon = Icons.Outlined.Login,
                        onPreferenceClick = onRequireLogin,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${user!!.nickname.firstOrNull() ?: "?"}",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(
                                user!!.nickname.ifBlank { "未设置昵称" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                "Lv${user!!.level}  收藏${user!!.favoriteCount}  积分${pointLimitText(user!!.level, user!!.point)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---- 积分明细 / 退出 ----
            if (user != null) {
                item {
                    TextPreferenceWidget(
                        title = "积分明细",
                        onPreferenceClick = { showPointLogs = true },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = "退出登录",
                        icon = Icons.Outlined.Logout,
                        onPreferenceClick = {
                            scope.launch { UserRepository.logout() }
                        },
                    )
                }
            }

            item { HorizontalDivider() }

            // ---- 快捷入口 ----
            item {
                TextPreferenceWidget(
                    title = "收藏",
                    icon = Icons.Outlined.CollectionsBookmark,
                    onPreferenceClick = { onNavigateToTab(1) },
                )
            }
            item {
                TextPreferenceWidget(
                    title = "阅读历史",
                    icon = Icons.Outlined.History,
                    onPreferenceClick = { onNavigateToTab(2) },
                )
            }

            item { HorizontalDivider() }

            // ---- 设置入口 ----
            item {
                TextPreferenceWidget(
                    title = "外观",
                    icon = Icons.Outlined.Palette,
                    onPreferenceClick = onOpenAppearance,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "阅读器",
                    icon = Icons.AutoMirrored.Outlined.ChromeReaderMode,
                    onPreferenceClick = onOpenReader,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "标签屏蔽",
                    icon = Icons.AutoMirrored.Outlined.Label,
                    onPreferenceClick = onOpenTagBlock,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "gay标签一键屏蔽",
                    icon = Icons.Outlined.Block,
                    onPreferenceClick = onBlockGayTags,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "测速日志",
                    icon = Icons.Outlined.Speed,
                    onPreferenceClick = onOpenSpeedTest,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "数据与存储",
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = onOpenDataStorage,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "高级",
                    icon = Icons.Outlined.Code,
                    onPreferenceClick = onOpenAdvanced,
                )
            }
            item {
                TextPreferenceWidget(
                    title = "关于",
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onOpenAbout,
                )
            }

            item { HorizontalDivider() }

            // ---- 赞助 ----
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://zy520.de5.net/juanzeng/"))
                        context.startActivity(intent)
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("赞助", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (showPointLogs) {
        PointLogsOverlay(onClose = { showPointLogs = false })
    }
}

@Composable
private fun PointLogsOverlay(onClose: () -> Unit) {
    val vm = remember { PointLogsViewModel() }
    val logs by vm.logs.collectAsState()
    val loading by vm.loading.collectAsState()

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text("积分明细", style = MaterialTheme.typography.titleMedium)
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text("等级划分：", style = MaterialTheme.typography.titleSmall)
                Text("Lv1: 0-500", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Lv2: 501-3000", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Lv3: 3001-6000", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Lv4: 6001-10000", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Lv5: 10001+", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "等级和积分仅开发者对接口的设置,属于自用功能,无视即可",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs.size) { i ->
                        val l = logs[i]
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(l.remark, modifier = Modifier.weight(1f))
                            Text(
                                if (l.point >= 0) "+${l.point}" else "${l.point}",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
