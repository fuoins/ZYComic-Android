package com.zycomic.app.ui.history

import android.util.Log
import com.zycomic.app.data.dto.HistoryItem
import com.zycomic.app.data.repository.HistoryRepository
import com.zycomic.app.data.repository.NotLoggedInException
import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 阅读历史页独立 ViewModel（komikku 风格 HistoryScreen 配套）。
 *
 * - 分页加载：[refreshHistory] / [loadMoreHistory]
 * - 本地搜索：[searchQuery] 与 [history] 组合出 [filteredHistory]
 * - 多选：[selectionMode] / [selectedIds]，支持全选 / 反选（均作用于当前筛选结果）
 * - 删除：[deleteSingle] / [deleteSelected]，ids 为历史记录 id（逗号分隔）
 */
class HistoryViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val TAG = "HistoryVM"

    // ---- 分页 ----
    private var historyPage = 1
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()
    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()
    private val _historyHasMore = MutableStateFlow(true)
    val historyHasMore: StateFlow<Boolean> = _historyHasMore.asStateFlow()

    // ---- 搜索（本地筛选）----
    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    val filteredHistory: StateFlow<List<HistoryItem>> =
        combine(_history, _searchQuery) { list, query ->
            if (query.isNullOrBlank()) {
                list
            } else {
                val q = query.trim()
                list.filter { it.bookName.contains(q, ignoreCase = true) }
            }
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    // ---- 多选 ----
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    // ---- 登录态 ----
    val user = UserRepository.userFlow
    val needLogin = MutableStateFlow(false)

    init {
        refreshHistory()
        // 监听登录态变化：登录成功后自动刷新，登出后清空
        scope.launch {
            UserRepository.userFlow.collect { u ->
                if (u != null) {
                    needLogin.value = false
                    refreshHistory()
                } else {
                    _history.value = emptyList()
                    _selectedIds.value = emptySet()
                    _selectionMode.value = false
                }
            }
        }
    }

    // ---------- 分页加载 ----------
    fun refreshHistory() {
        scope.launch { loadHist(reset = true) }
    }

    fun loadMoreHistory() {
        if (_historyLoading.value || !_historyHasMore.value) return
        scope.launch { loadHist(reset = false) }
    }

    private suspend fun loadHist(reset: Boolean) {
        if (reset) {
            historyPage = 1
            _historyHasMore.value = true
            _historyLoading.value = true
        }
        try {
            val (list, hasMore) = HistoryRepository.getHistory(historyPage)
            historyPage++
            _history.value = if (reset) list else _history.value + list
            _historyHasMore.value = hasMore
        } catch (_: NotLoggedInException) {
            needLogin.value = true
        } catch (e: Exception) {
            Log.e(TAG, "加载历史失败", e)
        } finally {
            _historyLoading.value = false
        }
    }

    // ---------- 搜索 ----------
    fun updateSearchQuery(query: String?) {
        // 空字符串视为 null（关闭搜索框）
        _searchQuery.value = query?.takeIf { it.isNotBlank() }
    }

    // ---------- 多选 ----------
    fun enterSelection() {
        _selectionMode.value = true
        _selectedIds.value = emptySet()
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelect(id: String) {
        val s = _selectedIds.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        _selectedIds.value = s
    }

    /** 长按进入多选时，同时选中当前项。 */
    fun enterSelectionAndSelect(id: String) {
        _selectionMode.value = true
        _selectedIds.value = setOf(id)
    }

    /** 全选当前筛选结果中的所有项。 */
    fun selectAll() {
        _selectedIds.value = filteredHistory.value.map { it.id }.toSet()
    }

    /** 在当前筛选结果范围内反选。 */
    fun invertSelection() {
        val current = _selectedIds.value.toMutableSet()
        filteredHistory.value.forEach {
            if (!current.add(it.id)) current.remove(it.id)
        }
        _selectedIds.value = current
    }

    // ---------- 删除 ----------
    /** 删除单条历史（id 为历史记录 id）。 */
    fun deleteSingle(id: String) {
        scope.launch {
            try {
                HistoryRepository.deleteHistory(id)
                _history.value = _history.value.filterNot { it.id == id }
            } catch (_: NotLoggedInException) {
                needLogin.value = true
            } catch (e: Exception) {
                Log.e(TAG, "删除单条历史失败", e)
            }
        }
    }

    /** 批量删除选中的历史，成功后退出多选模式。 */
    fun deleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            try {
                HistoryRepository.deleteHistory(ids.joinToString(","))
                _history.value = _history.value.filterNot { it.id in ids }
                exitSelection()
            } catch (_: NotLoggedInException) {
                needLogin.value = true
            } catch (e: Exception) {
                Log.e(TAG, "批量删除历史失败", e)
            }
        }
    }
}
