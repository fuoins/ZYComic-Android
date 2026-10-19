package com.zycomic.app.ui.profile

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.zycomic.app.data.repository.JmDailyCalendar
import com.zycomic.app.data.repository.JmMonthlyAward
import com.zycomic.app.ui.components.JmCoverImageLoader
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun JmCalendarDetailScreen(viewModel: JmCheckInViewModel, onClose: () -> Unit) {
    val state by viewModel.calendar.collectAsState()
    BackHandler(enabled = true, onBack = onClose)
    LaunchedEffect(Unit) { viewModel.loadCalendar() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            ScreenHeader(title = stringResource(KMR.strings.jm_calendar_title), onBack = onClose, trailing = {
                IconButton(onClick = { viewModel.loadCalendar() }, enabled = !state.loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            })
            HorizontalDivider()
            when {
                state.loading -> CenterLoading()
                state.error != null && state.calendar == null -> ErrorBlock(state.error!!, viewModel::loadCalendar)
                state.calendar == null -> Text("暂无签到日历", modifier = Modifier.padding(20.dp))
                else -> {
                    state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                    CalendarContent(state.calendar!!)
                }
            }
        }
    }
}

@Composable
private fun CalendarContent(calendar: JmDailyCalendar) {
    val today = LocalDate.now()
    val monthDays = calendar.days.associateBy { it.date }
    val startOffset = calendar.month.atDay(1).dayOfWeek.value - 1
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(calendar.eventName ?: "${calendar.month.year}年${calendar.month.monthValue}月", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("连续签到进度 ${calendar.progress ?: "—"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text("活动 ID ${calendar.dailyId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("连续 3 天：${calendar.threeDayCoin ?: "—"} Jcoin / ${calendar.threeDayExp ?: "—"} EXP", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Text("连续 7 天：${calendar.sevenDayCoin ?: "—"} Jcoin / ${calendar.sevenDayExp ?: "—"} EXP", style = MaterialTheme.typography.bodySmall)
        Text(stringResource(KMR.strings.jm_calendar_legend), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        Text(stringResource(KMR.strings.jm_calendar_activity_marker), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Text(day, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        val cells = buildList<LocalDate?> {
            repeat(startOffset) { add(null) }
            for (day in 1..calendar.month.lengthOfMonth()) add(calendar.month.atDay(day))
            repeat((7 - size % 7) % 7) { add(null) }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
                        if (date == null) {
                            SignCalendarDayCell(null, false, false, false, false, isPlaceholder = true)
                        } else {
                            val day = monthDays[date]
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SignCalendarDayCell(
                                    dayNumber = date.dayOfMonth,
                                    isSigned = day?.signed == true,
                                    isMissed = day != null && date < today && day.signed != true,
                                    isToday = date == today,
                                    isFuture = date > today,
                                )
                                if (day?.bonus == true) Text("♥", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Text(stringResource(KMR.strings.jm_calendar_summary), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

private data class MakeupConfirmation(val calendar: JmDailyCalendar, val dates: Set<LocalDate>)

@Composable
internal fun JmMakeupScreen(viewModel: JmCheckInViewModel, context: Context, onClose: () -> Unit) {
    val state by viewModel.calendar.collectAsState()
    var confirmation by remember { mutableStateOf<MakeupConfirmation?>(null) }
    fun closeScreen() {
        viewModel.cancelMakeup()
        onClose()
    }
    BackHandler(enabled = true) {
        if (state.makeupLoading) viewModel.cancelMakeup() else closeScreen()
    }
    LaunchedEffect(Unit) { viewModel.loadCalendar() }
    Dialog(
        onDismissRequest = { if (state.makeupLoading) viewModel.cancelMakeup() else closeScreen() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            ScreenHeader(title = stringResource(KMR.strings.jm_makeup_title), onBack = ::closeScreen, trailing = {
                IconButton(onClick = { viewModel.loadCalendar() }, enabled = !state.loading && !state.makeupLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(KMR.strings.jm_makeup_reload))
                }
            })
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(stringResource(KMR.strings.jm_makeup_description), style = MaterialTheme.typography.bodyMedium)
                when {
                    state.loading -> CenterLoading()
                    state.error != null && state.calendar == null -> ErrorBlock(state.error!!, viewModel::loadCalendar)
                    state.calendar == null -> Text("暂无签到日历", modifier = Modifier.padding(vertical = 16.dp))
                    else -> {
                        val calendar = state.calendar!!
                        val today = LocalDate.now()
                        val candidates = calendar.makeupCandidates(today).map { it.date }.toSet()
                        Text("${calendar.month.year}年${calendar.month.monthValue}月", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                        Text(stringResource(KMR.strings.jm_makeup_preview_count, candidates.size), modifier = Modifier.padding(top = 8.dp))
                        Text(
                            if (candidates.isEmpty()) stringResource(KMR.strings.jm_makeup_empty) else candidates.sorted().joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(stringResource(KMR.strings.jm_makeup_preview_notice), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        Button(
                            enabled = !state.makeupLoading && !state.loading && state.error == null && candidates.isNotEmpty() && calendar.month == YearMonth.from(today),
                            onClick = { confirmation = MakeupConfirmation(calendar, candidates) },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        ) { Text(stringResource(KMR.strings.jm_makeup_confirm_all)) }
                    }
                }
                if (state.makeupLoading) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(KMR.strings.jm_makeup_progress, state.makeupProgress?.completed ?: 0, state.makeupProgress?.total ?: 0),
                            Modifier.weight(1f).padding(start = 10.dp),
                        )
                        TextButton(onClick = viewModel::cancelMakeup) { Text(stringResource(KMR.strings.jm_makeup_cancel)) }
                    }
                }
                state.makeupSummary?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary) }
                state.error?.let {
                    Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::loadCalendar, enabled = !state.makeupLoading && !state.loading) { Text(stringResource(KMR.strings.jm_makeup_reload)) }
                }
            }
        }
    }
    confirmation?.let { preview ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(KMR.strings.jm_makeup_confirm_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(KMR.strings.jm_makeup_description))
                    Text(stringResource(KMR.strings.jm_makeup_confirm_dates, preview.dates.size, preview.dates.sorted().joinToString("、")), Modifier.padding(top = 12.dp))
                    Text(stringResource(KMR.strings.jm_makeup_preview_notice), Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = {
                Button(enabled = !state.makeupLoading && !state.loading, onClick = {
                    confirmation = null
                    viewModel.confirmMakeup(context, preview.calendar, preview.dates)
                }) { Text(stringResource(KMR.strings.jm_makeup_start)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(KMR.strings.jm_makeup_back)) } },
        )
    }
}

@Composable
internal fun JmAnnualAwardsScreen(viewModel: JmCheckInViewModel, onClose: () -> Unit) {
    val state by viewModel.awards.collectAsState()
    BackHandler(enabled = true, onBack = onClose)
    LaunchedEffect(Unit) { viewModel.loadYears() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            ScreenHeader(stringResource(KMR.strings.jm_annual_awards_title), onClose)
            HorizontalDivider()
            if (state.loadingYears && state.years.isEmpty()) {
                CenterLoading()
            } else if (state.error != null && state.years.isEmpty()) {
                ErrorBlock(state.error!!, viewModel::loadYears)
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.years.forEach { year ->
                        if (year == state.selectedYear) {
                            Button(onClick = { viewModel.loadMonths(year) }) { Text(year) }
                        } else {
                            OutlinedButton(onClick = { viewModel.loadMonths(year) }, enabled = !state.loadingMonths) { Text(year) }
                        }
                    }
                }
                state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                if (state.loadingMonths) {
                    CenterLoading()
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Text("年度奖励图片为只读浏览；此页面不会调用按月份领取奖励的接口。", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        items((1..12).toList()) { month ->
                            val award = state.months.firstOrNull { it.year.toString() == state.selectedYear && it.month == month }
                            AwardMonthCard(month, award, state.selectedYear?.toIntOrNull())
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AwardMonthCard(month: Int, award: JmMonthlyAward?, year: Int?) {
    val context = LocalContext.current
    val imageLoader = remember(context) { JmCoverImageLoader.get(context) }
    var imageFailed by remember(award?.imageUrl) { mutableStateOf(false) }
    val now = YearMonth.now()
    val target = year?.let { runCatching { YearMonth.of(it, month) }.getOrNull() }
    val status = when {
        award?.imageUrl != null && imageFailed -> stringResource(KMR.strings.jm_award_image_load_failed)
        award?.imageUrl != null -> "全签奖励"
        award?.imagePath != null -> stringResource(KMR.strings.jm_award_image_url_failed)
        award == null -> stringResource(KMR.strings.jm_award_no_data)
        target == null -> "暂无数据"
        target.isBefore(now) -> stringResource(KMR.strings.jm_award_image_unavailable)
        target == now -> "进行中"
        else -> "该月还未经历"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$month 月", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (award?.imageUrl != null && !imageFailed) {
            AsyncImage(
                model = award.imageUrl,
                imageLoader = imageLoader,
                contentScale = ContentScale.FillWidth,
                onError = { imageFailed = true },
                contentDescription = "${award.year}年${award.month}月全签奖励",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        award?.let { Text("活动 ID ${it.id} · ${it.year}-${it.month}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 10.dp)) { Text("重试") }
    }
}
