package com.zycomic.app.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zycomic.app.data.dto.WelfareData
import com.zycomic.app.data.dto.SignDay
import com.zycomic.app.data.repository.UserRepository
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onRequireLogin: () -> Unit,
    onNavigateToTab: (Int) -> Unit,
    onOpenTagBlock: () -> Unit,
    onBlockGayTags: () -> Unit,
) {
    val context = LocalContext.current
    val user by UserRepository.userFlow.collectAsState()
    var showPointLogs by remember { mutableStateOf(false) }
    var showChangePwd by remember { mutableStateOf(false) }
    var showRewardDialog by remember { mutableStateOf(false) }
    var rewarding by remember { mutableStateOf(false) }
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
                        title = if (rewarding) "领取中..." else "奖励破解领取",
                        icon = Icons.Outlined.Code,
                        onPreferenceClick = { if (!rewarding) showRewardDialog = true },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = "修改密码",
                        icon = Icons.Outlined.Lock,
                        onPreferenceClick = { showChangePwd = true },
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

            item { HorizontalDivider() }
        }
    }

    if (showPointLogs) {
        PointLogsOverlay(onClose = { showPointLogs = false })
    }

    if (showChangePwd) {
        var pwd1 by remember { mutableStateOf("") }
        var pwd2 by remember { mutableStateOf("") }
        var pwdErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showChangePwd = false },
            title = { Text("修改密码") },
            text = {
                Column {
                    OutlinedTextField(value = pwd1, onValueChange = { pwd1 = it }, label = { Text("新密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = pwd2, onValueChange = { pwd2 = it }, label = { Text("确认新密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    pwdErr?.let { Text(it, color = Color(0xFFE53935)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pwd1.isEmpty() || pwd1 != pwd2) { pwdErr = "两次密码不一致"; return@TextButton }
                    scope.launch {
                        try {
                            UserRepository.changePassword(pwd1)
                            showChangePwd = false
                            Toast.makeText(context, "密码修改成功，请重新登录", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) { pwdErr = e.message ?: "修改失败" }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showChangePwd = false }) { Text("取消") } },
        )
    }

    if (showRewardDialog) {
        AlertDialog(
            onDismissRequest = { showRewardDialog = false },
            title = { Text("奖励破解领取") },
            text = { Text("确认领取今日广告奖励？") },
            confirmButton = {
                Button(
                    enabled = !rewarding,
                    onClick = {
                        scope.launch {
                            rewarding = true
                            showRewardDialog = false
                            runCatching { UserRepository.getWelfare() }
                                .onSuccess { Toast.makeText(context, "领取成功", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message ?: "领取失败", Toast.LENGTH_SHORT).show() }
                            rewarding = false
                        }
                    },
                ) {
                    if (rewarding) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRewardDialog = false }) { Text("取消") }
            },
        )
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
                item { SignCalendarSection(welfare = welfare, loading = welfareLoading, error = welfareError, onReload = { month -> vm.loadWelfare(month) }) }

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

/** 签到日历区域。 */
@Composable
private fun SignCalendarSection(
    welfare: WelfareData?,
    loading: Boolean,
    error: String?,
    onReload: (String) -> Unit,
) {
    val today = remember { LocalDate.now().toString() }
    val firstDate = welfare?.sign_list?.firstOrNull()?.date
    val currentMonthLd = remember(firstDate) {
        firstDate?.let { runCatching { LocalDate.parse(it).withDayOfMonth(1) }.getOrNull() }
            ?: LocalDate.now().withDayOfMonth(1)
    }
    val monthText = "${currentMonthLd.year}年${currentMonthLd.monthValue}月"
    val currentMonthStr = String.format("%04d-%02d-01", currentMonthLd.year, currentMonthLd.monthValue)
    val prevMonthStr = run {
        val p = currentMonthLd.minusMonths(1)
        String.format("%04d-%02d-01", p.year, p.monthValue)
    }
    val nextMonthStr = run {
        val n = currentMonthLd.plusMonths(1)
        String.format("%04d-%02d-01", n.year, n.monthValue)
    }
    val consecutive = welfare?.consecutive_sign?.takeIf { it > 0 } ?: welfare?.user_data?.consecutiveDays ?: 0
    val point = welfare?.user_data?.point ?: 0

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onReload(prevMonthStr) }, enabled = !loading) { Text("上一月") }
            Text(
                monthText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onReload(nextMonthStr) }, enabled = !loading) { Text("下一月") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "连续 $consecutive 天",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                "积分 $point",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Text(
                    day,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "签到数据加载失败：$error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onReload(currentMonthStr) }) { Text("重试") }
            }
            welfare == null || welfare.sign_list.isEmpty() -> Text(
                "暂无签到数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> {
                welfare.sign_list.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            Box(modifier = Modifier.weight(1f)) { SignDayCell(day = day, isToday = day.date == today) }
                        }
                        repeat(7 - week.size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
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
private fun SignDayCell(day: SignDay, isToday: Boolean) {
    Box(
        modifier = Modifier
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (day.isPlaceholder) {
            Box(Modifier.size(32.dp))
            return@Box
        }
        val bg = when {
            day.isSigned -> MaterialTheme.colorScheme.primary
            day.isMissed -> MaterialTheme.colorScheme.surfaceVariant
            day.isFuture -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(
                    if (isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier,
                )
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (day.isSigned) "✓" else (day.dayNum?.toString() ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (day.isSigned) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
