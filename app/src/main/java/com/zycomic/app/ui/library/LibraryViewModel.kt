package com.zycomic.app.ui.library

import android.util.Log
import com.zycomic.app.data.dto.FavoriteItem
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 书架（收藏 + 阅读历史）ViewModel。
 *
 * @param mode 0=只加载收藏, 1=只加载历史, 2=都加载（兼容旧用法）
 */
class LibraryViewModel(val mode: Int = 2) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val TAG = "LibraryVM"

    val mainTab = MutableStateFlow(0)   // 0 收藏 / 1 阅读历史

    // ---- 收藏夹 ----
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val selectedFolderId = MutableStateFlow(0)        // 0 = 全部收藏夹
    val isEnd = MutableStateFlow(-1)                  // -1 全部 / 0 连载 / 1 完结
    val isFullVersion = MutableStateFlow(-1)           // -1 全部 / 1 高清 / 2 清水 / 3 未删减 / 4 完整
    val showOnlyUpdated = MutableStateFlow(-1)        // -1 全部 / 1 只显示更新
    val order = MutableStateFlow(2)                   // 1 更新时间 / 2 收藏时间（默认收藏时间）
    val orderType = MutableStateFlow(0)               // 0 降序 / 1 升序

    // ---- 收藏列表 ----
    private val _favItems = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val favItems: StateFlow<List<FavoriteItem>> = _favItems.asStateFlow()
    private val _favLoading = MutableStateFlow(false)
    val favLoading: StateFlow<Boolean> = _favLoading.asStateFlow()
    private val _favAppending = MutableStateFlow(false)
    val favAppending: StateFlow<Boolean> = _favAppending.asStateFlow()
    private val _favHasMore = MutableStateFlow(true)
    val favHasMore: StateFlow<Boolean> = _favHasMore.asStateFlow()

    // ---- 收藏多选 ----
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())   // bookId 集合

    // ---- 历史 ----
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()
    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()
    private val _historyHasMore = MutableStateFlow(true)
    val historyHasMore: StateFlow<Boolean> = _historyHasMore.asStateFlow()
    val historySelectionMode = MutableStateFlow(false)
    val historySelectedIds = MutableStateFlow<Set<String>>(emptySet())   // 历史记录 id 集合

    val needLogin = MutableStateFlow(false)
    val user = UserRepository.userFlow

    private var favPage = 1
    private var historyPage = 1

    init {
        if (mode == 0 || mode == 2) {
            loadFolders()
            refreshFavorites()
        }
        if (mode == 1 || mode == 2) {
            refreshHistory()
        }
        // 监听登录态变化：登录成功后自动重新加载，登出后清空
        scope.launch {
            UserRepository.userFlow.collect { user ->
                if (user != null) {
                    needLogin.value = false
                    if (mode == 0 || mode == 2) {
                        loadFolders()
                        refreshFavorites()
                    }
                    if (mode == 1 || mode == 2) {
                        refreshHistory()
                    }
                } else {
                    _favItems.value = emptyList()
                    _history.value = emptyList()
                    folders.value = emptyList()
                }
            }
        }
    }

    fun selectMainTab(t: Int) { mainTab.value = t }

    // ---------- 收藏夹 ----------
    fun loadFolders() {
        scope.launch {
            try { folders.value = FavoriteRepository.getFolderList() }
            catch (_: NotLoggedInException) { /* 未登录不提示，UI 层已处理 */ }
            catch (e: Exception) { Log.e(TAG, "加载收藏夹失败", e) }
        }
    }

    fun selectFolder(id: Int) { selectedFolderId.value = id; refreshFavorites() }
    fun selectIsEnd(v: Int) { isEnd.value = v; refreshFavorites() }
    fun selectIsFull(v: Int) { isFullVersion.value = v; refreshFavorites() }
    fun selectShowOnlyUpdated(v: Int) { showOnlyUpdated.value = v; refreshFavorites() }

    /** 排序：order=1 更新时间 / 2 收藏时间；orderType=0 降序 / 1 升序 */
    fun selectSort(order: Int, orderType: Int) {
        this.order.value = order
        this.orderType.value = orderType
        refreshFavorites()
    }

    // ---------- 收藏列表 ----------
    fun refreshFavorites() {
        scope.launch { loadFav(reset = true) }
    }

    fun loadMoreFavorites() {
        if (_favLoading.value || _favAppending.value || !_favHasMore.value) return
        scope.launch { loadFav(reset = false) }
    }

    private suspend fun loadFav(reset: Boolean) {
        if (!UserRepository.isLoggedIn) return  // 未登录不加载，UI 层用 user==null 显示"请先登录"
        if (reset) {
            favPage = 1
            _favHasMore.value = true
            _favLoading.value = true
        } else _favAppending.value = true
        try {
            val list = FavoriteRepository.getFavorites(
                page = favPage,
                order = order.value,
                orderType = orderType.value,
                folderId = selectedFolderId.value,
                isEnd = isEnd.value,
                isFullVersion = isFullVersion.value,
                showOnlyUpdated = showOnlyUpdated.value,
            )
            favPage++
            _favItems.value = if (reset) list else _favItems.value + list
            if (list.isEmpty()) _favHasMore.value = false
        } catch (_: NotLoggedInException) {
            needLogin.value = true
        } catch (e: Exception) {
            Log.e(TAG, "加载收藏失败", e)
        } finally {
            _favLoading.value = false
            _favAppending.value = false
        }
    }

    // ---------- 收藏多选 ----------
    fun enterSelection() { selectionMode.value = true; selectedIds.value = emptySet() }
    fun exitSelection() { selectionMode.value = false; selectedIds.value = emptySet() }
    fun toggleSelect(bookId: String) {
        val s = selectedIds.value.toMutableSet()
        if (!s.add(bookId)) s.remove(bookId)
        selectedIds.value = s
    }

    /** 全选已加载出的收藏；若已全选则取消全选 */
    fun toggleSelectAllLoadedFavorites() {
        val all = _favItems.value.map { it.bookId }.toSet()
        selectedIds.value = if (selectedIds.value == all) emptySet() else all
    }

    fun batchRemove() {
        val ids = selectedIds.value.joinToString(",")
        if (ids.isEmpty()) return
        scope.launch {
            try {
                FavoriteRepository.batchRemove(ids)
                exitSelection()
                refreshFavorites()
                UserRepository.refreshUserInfo()
            } catch (_: NotLoggedInException) { needLogin.value = true }
            catch (_: Exception) {}
        }
    }

    /** 移动所选收藏到收藏夹；folderId 为 null 表示移出收藏夹（全部收藏）。 */
    fun moveSelectedTo(folderId: Int?) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        scope.launch {
            try {
                if (folderId == null) FavoriteRepository.moveOutFolder(ids)
                else FavoriteRepository.moveToFolder(ids, folderId)
                exitSelection()
                refreshFavorites()
                loadFolders()
            } catch (_: NotLoggedInException) { needLogin.value = true }
            catch (_: Exception) {}
        }
    }

    // ---------- 收藏夹操作 ----------
    fun createFolder(name: String) {
        if (name.isBlank()) return
        scope.launch {
            try {
                FavoriteRepository.createFolder(name)
                loadFolders()
            } catch (_: NotLoggedInException) { needLogin.value = true }
            catch (_: Exception) {}
        }
    }

    fun renameFolder(folderId: String, name: String) {
        if (name.isBlank()) return
        scope.launch {
            try {
                FavoriteRepository.renameFolder(folderId, name)
                loadFolders()
            } catch (_: NotLoggedInException) { needLogin.value = true }
            catch (_: Exception) {}
        }
    }

    fun deleteFolder(folderId: String) {
        scope.launch {
            try {
                FavoriteRepository.deleteFolder(folderId)
                if (selectedFolderId.value.toString() == folderId) selectedFolderId.value = 0
                loadFolders()
                refreshFavorites()
            } catch (_: NotLoggedInException) { needLogin.value = true }
            catch (_: Exception) {}
        }
    }

    // ---------- 历史 ----------
    fun refreshHistory() { scope.launch { loadHist(reset = true) } }

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
        } catch (e: Exception) {
            Log.e(TAG, "加载历史失败", e)
        } finally {
            _historyLoading.value = false
        }
    }

    fun enterHistorySelection() { historySelectionMode.value = true; historySelectedIds.value = emptySet() }
    fun exitHistorySelection() { historySelectionMode.value = false; historySelectedIds.value = emptySet() }
    fun toggleHistorySelect(id: String) {
        val s = historySelectedIds.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        historySelectedIds.value = s
    }

    /** 全选已加载出的历史；若已全选则取消全选 */
    fun toggleSelectAllLoadedHistory() {
        val all = _history.value.map { it.id }.toSet()
        historySelectedIds.value = if (historySelectedIds.value == all) emptySet() else all
    }

    fun deleteSelectedHistory() {
        val ids = historySelectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            try {
                HistoryRepository.deleteHistory(ids.joinToString(","))
                // 删除后从列表移除
                _history.value = _history.value.filterNot { it.id in ids }
                exitHistorySelection()
            } catch (_: Exception) {}
        }
    }
}
