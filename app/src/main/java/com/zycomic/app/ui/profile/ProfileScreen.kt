package com.zycomic.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
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

    tachiyomi.presentation.core.components.material.Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { contentPadding ->
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
    val welfare by vm.welfare.collectAsState()
    val welfareLoading by vm.welfareLoading.collectAsState()
    val welfareError by vm.welfareError.collectAsState()

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            // 顶部标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("积分明细", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                // 1. 等级划分（可展开，默认收起）
                item { LevelExpandSection() }

                // 2. 签到日历
                item { SignCalendarSection(welfare = welfare, loading = welfareLoading, error = welfareError) }

                // 3. 积分明细标题
                item {
                    Text(
                        "积分明细",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                    HorizontalDivider()
                }

                // 4. 积分明细列表
                if (loading) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    }
                } else {
                    items(logs.size) { i ->
                        val l = logs[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(l.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    l.create_time,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (l.point >= 0) "+${l.point}" else "${l.point}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (l.point >= 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

/** 等级划分可展开区域，默认收起。 */
@Composable
private fun LevelExpandSection() {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "等级划分",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            listOf(
                "Lv1: 0-500",
                "Lv2: 501-3000",
                "Lv3: 3001-6000",
                "Lv4: 6001-10000",
                "Lv5: 10001+",
            ).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Text(
                "等级和积分仅开发者对接口的设置,属于自用功能,无视即可",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    HorizontalDivider()
}

/** 签到日历区域，参考 komikku 日历样式。 */
@Composable
private fun SignCalendarSection(
    welfare: com.zycomic.app.data.dto.WelfareData?,
    loading: Boolean = false,
    error: String? = null,
) {
    val context = LocalContext.current
    val signMap = remember(welfare) {
        welfare?.sign_list?.associate { it.date to (it.status == "signedin") } ?: emptyMap()
    }
    val currentMonth = welfare?.current_month ?: ""
    val consecutive = welfare?.consecutive_sign ?: 0

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        // 标题行：月份 + 连续签到
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "2026年${currentMonth}月签到",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "连续签到 $consecutive 天",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))

        // 星期标题
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { day ->
                Text(
                    day,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // 日期格子
        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.CircularProgressIndicator() }
            error != null -> Text(
                "签到数据加载失败：$error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            welfare == null || welfare.sign_list.isEmpty() -> Text(
                "暂无签到数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> {
                val firstDay = welfare.sign_list.first().date
                val firstDayOfWeek = java.time.LocalDate.parse(firstDay).dayOfWeek.value % 7
                val days = welfare.sign_list

            // 第一行前面的空格
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(firstDayOfWeek) {
                    Spacer(modifier = Modifier.weight(1f))
                }
                days.take(7 - firstDayOfWeek).forEach { day ->
                    SignDayCell(day = day, signed = signMap[day.date] ?: false)
                }
            }
            // 剩余行
            days.drop(7 - firstDayOfWeek).chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        SignDayCell(day = day, signed = signMap[day.date] ?: false)
                    }
                    repeat(7 - week.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
            }

        Spacer(Modifier.height(8.dp))
        // 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(4.dp))
                Text("已签到", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(4.dp))
                Text("未签到", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    HorizontalDivider()
}

/** 签到日历中的单个日期格子。 */
@Composable
private fun SignDayCell(day: com.zycomic.app.data.dto.SignDay, signed: Boolean) {
    val dayNum = try {
        java.time.LocalDate.parse(day.date).dayOfMonth
    } catch (_: Exception) {
        day.index
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (signed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                dayNum.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = if (signed) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
