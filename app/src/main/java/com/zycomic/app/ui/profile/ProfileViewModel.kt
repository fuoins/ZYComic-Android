package com.zycomic.app.ui.profile

import android.content.Context
import com.zycomic.app.data.dto.PointLog
import com.zycomic.app.data.dto.WelfareData
import com.zycomic.app.data.repository.JmCheckInRepository
import com.zycomic.app.data.repository.JmDailyCalendar
import com.zycomic.app.data.repository.JmMakeupProgress
import com.zycomic.app.data.repository.JmMonthlyAward
import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

internal data class JmCalendarScreenState(
    val calendar: JmDailyCalendar? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selectedDates: Set<LocalDate> = emptySet(),
    val makeupLoading: Boolean = false,
    val makeupProgress: JmMakeupProgress? = null,
    val makeupSummary: String? = null,
)

internal data class JmAwardsScreenState(
    val years: List<String> = emptyList(),
    val selectedYear: String? = null,
    val months: List<JmMonthlyAward> = emptyList(),
    val loadingYears: Boolean = false,
    val loadingMonths: Boolean = false,
    val error: String? = null,
)

/** Screen requests are opt-in; construction performs no JM request and never checks in automatically. */
internal class JmCheckInViewModel {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
    private val _calendar = MutableStateFlow(JmCalendarScreenState())
    val calendar: StateFlow<JmCalendarScreenState> = _calendar.asStateFlow()
    private val _awards = MutableStateFlow(JmAwardsScreenState())
    val awards: StateFlow<JmAwardsScreenState> = _awards.asStateFlow()
    private var makeupJob: Job? = null

    fun loadCalendar() {
        if (_calendar.value.loading) return
        scope.launch {
            _calendar.value = _calendar.value.copy(loading = true, error = null)
            try {
                _calendar.value = _calendar.value.copy(calendar = JmCheckInRepository.loadCalendar(), selectedDates = emptySet())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _calendar.value = _calendar.value.copy(error = error.message ?: "加载签到日历失败")
            } finally {
                _calendar.value = _calendar.value.copy(loading = false)
            }
        }
    }

    fun toggleDate(date: LocalDate) {
        val state = _calendar.value
        val eligible = state.calendar?.makeupCandidates(LocalDate.now())?.any { it.date == date } == true
        if (!eligible || state.makeupLoading) return
        _calendar.value = state.copy(selectedDates = if (date in state.selectedDates) state.selectedDates - date else state.selectedDates + date)
    }

    fun confirmMakeup(context: Context) {
        val state = _calendar.value
        val preview = state.calendar ?: return
        if (state.makeupLoading || state.selectedDates.isEmpty()) return
        makeupJob = scope.launch {
            _calendar.value = _calendar.value.copy(makeupLoading = true, makeupProgress = null, makeupSummary = null, error = null)
            try {
                val result = JmCheckInRepository.makeup(context, preview, state.selectedDates) { progress ->
                    _calendar.value = _calendar.value.copy(makeupProgress = progress)
                }
                val summary = when {
                    result.error != null -> "已补签 ${result.completed.size} 天；${result.failedDate} 失败：${result.error}"
                    else -> "补签完成：本次成功 ${result.completed.size} 天"
                }
                _calendar.value = _calendar.value.copy(selectedDates = emptySet(), makeupSummary = summary)
                loadCalendar()
            } catch (error: CancellationException) {
                val savedCount = _calendar.value.makeupProgress?.completed ?: 0
                _calendar.value = _calendar.value.copy(makeupSummary = "补签已取消；已完成 $savedCount 天（可重新加载核对）")
                throw error
            } catch (error: Exception) {
                val savedCount = _calendar.value.makeupProgress?.completed ?: 0
                _calendar.value = _calendar.value.copy(
                    makeupSummary = "补签未完成；本次已完成 $savedCount 天（可重新加载核对）",
                    error = error.message ?: "补签失败；已完成部分已保存",
                )
            } finally {
                _calendar.value = _calendar.value.copy(makeupLoading = false)
            }
        }
    }

    fun cancelMakeup() {
        makeupJob?.cancel()
    }

    fun close() {
        cancelMakeup()
        scope.cancel()
    }

    fun loadYears() {
        if (_awards.value.loadingYears || _awards.value.years.isNotEmpty()) return
        scope.launch {
            _awards.value = _awards.value.copy(loadingYears = true, error = null)
            try {
                val years = JmCheckInRepository.loadAwardYears()
                val selected = years.firstOrNull()
                _awards.value = _awards.value.copy(years = years, selectedYear = selected)
                if (selected != null) loadMonths(selected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _awards.value = _awards.value.copy(error = error.message ?: "加载年份失败")
            } finally {
                _awards.value = _awards.value.copy(loadingYears = false)
            }
        }
    }

    fun loadMonths(year: String) {
        if (_awards.value.loadingMonths) return
        scope.launch {
            _awards.value = _awards.value.copy(selectedYear = year, months = emptyList(), loadingMonths = true, error = null)
            try {
                _awards.value = _awards.value.copy(months = JmCheckInRepository.loadMonthlyAwards(year))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _awards.value = _awards.value.copy(error = error.message ?: "加载月度奖励失败")
            } finally {
                _awards.value = _awards.value.copy(loadingMonths = false)
            }
        }
    }
}

class PointLogsViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _logs = MutableStateFlow<List<PointLog>>(emptyList())
    val logs: StateFlow<List<PointLog>> = _logs.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _welfare = MutableStateFlow<WelfareData?>(null)
    val welfare: StateFlow<WelfareData?> = _welfare.asStateFlow()
    private val _welfareLoading = MutableStateFlow(true)
    val welfareLoading: StateFlow<Boolean> = _welfareLoading.asStateFlow()
    private val _welfareError = MutableStateFlow<String?>(null)
    val welfareError: StateFlow<String?> = _welfareError.asStateFlow()

    private var page = 1

    init {
        load()
        loadWelfare()
    }

    fun load() {
        scope.launch {
            _loading.value = true
            try {
                _logs.value = UserRepository.getPointLogs(page)
                page++
            } catch (_: Exception) {
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadWelfare(month: String? = null) {
        scope.launch {
            _welfareLoading.value = true
            _welfareError.value = null
            try {
                _welfare.value = UserRepository.getWelfare(month)
            } catch (e: Exception) {
                _welfareError.value = e.message ?: "加载失败"
            } finally {
                _welfareLoading.value = false
            }
        }
    }
}

/** 根据等级返回积分上限文案。 */
fun pointLimitText(level: Int, point: Int): String = when (level) {
    1 -> "$point / 500"
    2 -> "$point / 3000"
    3 -> "$point / 6000"
    4 -> "$point / 10000"
    else -> "$point / ∞" // Lv5 及以上
}
