package com.zycomic.app.ui.profile

import com.zycomic.app.data.dto.PointLog
import com.zycomic.app.data.dto.WelfareData
import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    fun loadWelfare() {
        scope.launch {
            _welfareLoading.value = true
            _welfareError.value = null
            try {
                _welfare.value = UserRepository.getWelfare()
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
    else -> "$point / ∞"   // Lv5 及以上
}
