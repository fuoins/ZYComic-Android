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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val isFullVersion = MutableStateFlow(-1)           // -1 全部 / 1 高清 / 2 清水 / 3 未删减 / 4 完整
    val order = MutableStateFlow(2)                   // 1 更新日期 / 2 收藏日期（默认收藏日期）
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

    // ---- 本地筛选 / 显示（客户端筛选，不触发 API）----
    private val _searchQuery = MutableStateFlow<String?>(null)
    /** 本地搜索关键词；null 表示未进入搜索框（顶栏显示标题），非空表示搜索中 */
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()
    val isUnreadFilter = MutableStateFlow(-1)   // -1 全部 / 0 已读完 / 1 未读完
    val isReadFilter = MutableStateFlow(-1)     // -1 全部 / 0 未阅读过 / 1 阅读过
    val isEndFilter = MutableStateFlow(-1)      // -1 全部 / 0 连载中 / 1 已完结（客户端筛选）
    val onlyUpdatedFilter = MutableStateFlow(false) // false=关闭只显示更新 / true=只显示更新（客户端筛选）
    // 显示模式：0=紧凑网格 1=舒适网格 2=仅封面网格 3=列表1(封面+标题) 4=列表2(历史样式) 5=列表3(当前样式,默认)
    val displayMode = MutableStateFlow(5)
    val gridColumns = MutableStateFlow(3) // 网格列数，0=自动，1-10
    val showUnreadBadge = MutableStateFlow(true) // 封面左上角未读完标记
    val showUpdateBadge = MutableStateFlow(true) // 封面右下角 NEW 标记

    /**
     * 经过本地搜索 + 客户端筛选 + 本地排序后的收藏列表。
     * 服务端筛选（isFullVersion/folderId）在 [loadFav] 时传给 API；
     * order/orderType 也传给 API（请求返回已排好序），切换排序时本地重排不请求。
     */
    private data class FavFilterState(
        val list: List<FavoriteItem>,
        val query: String?,
        val unreadF: Int,
        val readF: Int,
        val endF: Int,
    )

    val filteredFavs: StateFlow<List<FavoriteItem>> =
        combine(
            combine(_favItems, _searchQuery, isUnreadFilter, isReadFilter, isEndFilter) { list, query, unreadF, readF, endF ->
                FavFilterState(list, query, unreadF, readF, endF)
            },
            onlyUpdatedFilter,
            order,
            orderType,
        ) { state, onlyUp, ord, ordType ->
            var result = state.list
            if (!state.query.isNullOrBlank()) {
                val q = state.query.trim()
                result = result.filter { it.bookName.contains(q, ignoreCase = true) }
            }
            // 未读完：chapterName 非空 且 readLast != chapterName
            fun FavoriteItem.isUnread(): Boolean =
                this.chapterName.isNotBlank() && this.readLast != this.chapterName
            when (state.unreadF) {
                1 -> result = result.filter { it.isUnread() }
                0 -> result = result.filterNot { it.isUnread() }
            }
            // 阅读过：readLast 非空
            when (state.readF) {
                1 -> result = result.filter { it.readLast.isNotBlank() }
                0 -> result = result.filter { it.readLast.isBlank() }
            }
            // 完结：end == "完结"
            when (state.endF) {
                1 -> result = result.filter { it.end == "完结" }
                0 -> result = result.filter { it.end != "完结" }
            }
            // 只显示更新：is_new == true
            if (onlyUp) {
                result = result.filter { it.isNew }
            }
            // 本地排序（切换排序时不请求，直接对已有数据排序）
            result = when (ord) {
                1 -> result.sortedByDescendingOrAscending({ it.lastTime }, ordType == 0)
                else -> result.sortedByDescendingOrAscending({ it.id.toLongOrNull() ?: 0L }, ordType == 0)
            }
            result
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

/** 按选择器排序，descending=true 降序，false 升序。 */
private fun <T, R : Comparable<R>> List<T>.sortedByDescendingOrAscending(selector: (T) -> R, descending: Boolean): List<T> =
    if (descending) this.sortedByDescending(selector) else this.sortedBy(selector)


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
    fun selectIsFull(v: Int) { isFullVersion.value = v; refreshFavorites() }

    /** 完结筛选：-1 全部 / 0 连载中 / 1 已完结（客户端筛选，不请求）。 */
    fun selectIsEnd(v: Int) { isEndFilter.value = v }

    /** 只显示更新：false=关闭 / true=只显示更新（客户端筛选，不请求）。 */
    fun selectOnlyUpdated(v: Boolean) { onlyUpdatedFilter.value = v }

    /** 排序：order=1 更新日期 / 2 收藏日期；orderType=0 降序 / 1 升序（本地排序，不请求）。 */
    fun selectSort(order: Int, orderType: Int) {
        this.order.value = order
        this.orderType.value = orderType
    }

    // ---------- 本地筛选 / 显示（不触发 API）----------
    /** 更新本地搜索词；空串视为 null（退出搜索框，回到标题）。 */
    fun updateSearchQuery(q: String?) {
        _searchQuery.value = q?.takeIf { it.isNotBlank() }
    }

    /** 未读完筛选：-1 全部 / 0 已读完 / 1 未读完（客户端筛选）。 */
    fun selectIsUnread(v: Int) { isUnreadFilter.value = v }

    /** 阅读过筛选：-1 全部 / 0 未阅读过 / 1 阅读过（客户端筛选）。 */
    fun selectIsRead(v: Int) { isReadFilter.value = v }

    /** 显示模式：0 默认列表 / 1 列表2。 */
    fun selectDisplayMode(m: Int) { displayMode.value = m }

    /** 设置网格列数，0=自动，1-10。 */
    fun setGridColumns(n: Int) { gridColumns.value = n }

    /** 切换封面左上角未读完标记显示。 */
    fun toggleShowUnreadBadge() { showUnreadBadge.value = !showUnreadBadge.value }

    /** 切换封面右下角 NEW 标记显示。 */
    fun toggleShowUpdateBadge() { showUpdateBadge.value = !showUpdateBadge.value }

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
                isFullVersion = isFullVersion.value,
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

    /** 长按进入多选时，同时选中当前项。 */
    fun enterSelectionAndSelect(bookId: String) {
        selectionMode.value = true
        selectedIds.value = setOf(bookId)
    }

    /** 全选当前筛选结果中的所有收藏。 */
    fun selectAll() {
        selectedIds.value = filteredFavs.value.map { it.bookId }.toSet()
    }

    /** 在当前筛选结果范围内反选。 */
    fun invertSelection() {
        val current = selectedIds.value.toMutableSet()
        filteredFavs.value.forEach {
            if (!current.add(it.bookId)) current.remove(it.bookId)
        }
        selectedIds.value = current
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
