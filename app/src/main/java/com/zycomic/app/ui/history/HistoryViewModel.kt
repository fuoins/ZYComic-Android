package com.zycomic.app.ui.history

import android.util.Log
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.data.dto.HistoryItem
import com.zycomic.app.data.repository.FavoriteRepository
import com.zycomic.app.data.repository.HistoryRepository
import com.zycomic.app.data.repository.NotLoggedInException
import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow()
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 阅读历史页独立 ViewModel（komikku 风格 HistoryScreen 配套）。
 *
 * - 分页加载：[refreshHistory] / [loadMoreHistory]
 * - 本地搜索：[searchQuery] 与 [history] 组合出 [filteredHistory]
 * - 多选：[selectionMode] / [selectedIds]，支持全选 / 反选（均作用于当前筛选结果）
 * - 删除：[deleteSingle] / [deleteSelected]
 *
 * 关键 id 语义（以服务端历史列表解密为准）：
 * - [HistoryItem.id] 是服务端"历史记录行主键"，仅用于列表展示/去重，**不参与删除**；
 * - [HistoryItem.bookId] 是漫画 id，删除接口 `ids=`、进详情/阅读、收藏都用它。
 * 因此多选集合 [selectedIds] 存的是 **bookId**；删除时把 bookId 用英文逗号拼成单次请求。
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

    // ---- 多选（集合内存 bookId）----
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    // ---- 删除进行中（防重复点击）----
    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    // ---- 一次性提示（Toast）----
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun consumeToast() { _toast.value = null }

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
        if (!UserRepository.isLoggedIn) return  // 未登录不加载
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
        // null=关闭搜索框，空字符串=搜索模式已激活但输入为空
        _searchQuery.value = query
    }

    // ---------- 多选（参数均为 bookId）----------
    fun enterSelection() {
        _selectionMode.value = true
        _selectedIds.value = emptySet()
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelect(bookId: String) {
        val s = _selectedIds.value.toMutableSet()
        if (!s.add(bookId)) s.remove(bookId)
        _selectedIds.value = s
    }

    /** 长按进入多选时，同时选中当前项（bookId）。 */
    fun enterSelectionAndSelect(bookId: String) {
        _selectionMode.value = true
        _selectedIds.value = setOf(bookId)
    }

    /** 全选当前筛选结果中的所有项（按 bookId）。 */
    fun selectAll() {
        _selectedIds.value = filteredHistory.value.map { it.bookId }.toSet()
    }

    /** 在当前筛选结果范围内反选（按 bookId）。 */
    fun invertSelection() {
        val current = _selectedIds.value.toMutableSet()
        filteredHistory.value.forEach {
            if (!current.add(it.bookId)) current.remove(it.bookId)
        }
        _selectedIds.value = current
    }

    /** 乐观地从本地列表移除指定 bookId（保留滚动位置），并同步清理选择集合。 */
    private fun removeLocally(bookIds: Set<String>) {
        _history.value = _history.value.filterNot { it.bookId in bookIds }
        _selectedIds.value = _selectedIds.value - bookIds
    }

    // ---------- 删除 ----------
    /**
     * 删除单条历史，参数为漫画 id（bookId）。
     * 先乐观移除本地行（保留滚动位）；服务端成功即一致，失败则刷新回滚并提示。
     */
    fun deleteSingle(bookId: String) {
        if (_deleting.value) return
        scope.launch {
            _deleting.value = true
            removeLocally(setOf(bookId))
            try {
                HistoryRepository.deleteHistory(bookId)
                _toast.value = "已删除"
            } catch (_: NotLoggedInException) {
                needLogin.value = true
                refreshHistory()
            } catch (e: Exception) {
                Log.e(TAG, "删除单条历史失败", e)
                _toast.value = "删除失败，请稍后重试"
                refreshHistory()
            } finally {
                _deleting.value = false
            }
        }
    }

    /**
     * 批量删除选中历史：把选中的 bookId 用英文逗号合并成**单次请求**。
     * 先乐观移除，失败则刷新回滚并提示。
     */
    fun deleteSelected() {
        val bookIds = _selectedIds.value
        if (bookIds.isEmpty() || _deleting.value) return
        scope.launch {
            _deleting.value = true
            val count = bookIds.size
            removeLocally(bookIds)
            exitSelection()
            try {
                HistoryRepository.deleteHistory(bookIds.joinToString(","))
                _toast.value = "已删除 $count 条"
            } catch (_: NotLoggedInException) {
                needLogin.value = true
                refreshHistory()
            } catch (e: Exception) {
                Log.e(TAG, "批量删除历史失败", e)
                _toast.value = "删除失败，请稍后重试"
                refreshHistory()
            } finally {
                _deleting.value = false
            }
        }
    }

    // ---------- 收藏 ----------
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    /** 收藏单本（bookId 为漫画 ID）。 */
    fun favoriteSingle(bookId: String) {
        scope.launch {
            try {
                FavoriteRepository.addFavorite(bookId.toInt())
                _toast.value = "已加入收藏"
            } catch (_: NotLoggedInException) {
                needLogin.value = true
            } catch (e: Exception) {
                Log.e(TAG, "收藏失败", e)
                _toast.value = "收藏失败，请稍后重试"
            }
        }
    }

    /** 批量收藏选中的历史记录到指定收藏夹（选中集合即 bookId，去重）。 */
    fun favoriteSelected(folderId: Int = 0) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            try {
                val bookIds = _history.value
                    .filter { it.bookId in ids }
                    .map { it.bookId }
                    .distinct()
                bookIds.forEach { FavoriteRepository.addFavorite(it.toInt(), folderId) }
                _toast.value = "已加入收藏"
            } catch (_: NotLoggedInException) {
                needLogin.value = true
            } catch (e: Exception) {
                Log.e(TAG, "批量收藏失败", e)
                _toast.value = "收藏失败，请稍后重试"
            }
        }
    }

    /** 加载收藏夹列表。 */
    fun loadFolders() {
        scope.launch {
            try {
                _folders.value = FavoriteRepository.getFolderList()
            } catch (_: NotLoggedInException) {
                needLogin.value = true
            } catch (_: Exception) {}
        }
    }
}
