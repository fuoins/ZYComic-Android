package com.zycomic.app.ui.library

import com.zycomic.app.data.dto.Folder
import com.zycomic.app.data.dto.HistoryItem
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.FavoriteRepository
import com.zycomic.app.data.repository.HistoryRepository
import com.zycomic.app.data.repository.MangaRepository
import com.zycomic.app.data.repository.NotLoggedInException
import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val mainTab = MutableStateFlow(0)   // 0 收藏 / 1 阅读历史

    // ---- 收藏 ----
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val selectedFolderId = MutableStateFlow(0)
    val isEnd = MutableStateFlow(-1)            // -1 全部 / 0 连载 / 1 完结
    val isFullVersion = MutableStateFlow(-1)    // -1 全部 / 0 高清清水 / 1 未删减完整版
    val showOnlyUpdated = MutableStateFlow(-1)  // -1 全部 / 1 只显示更新
    val order = MutableStateFlow(1)             // 1 更新时间 / 2 收藏时间
    val orderType = MutableStateFlow(0)         // 0 降序 / 1 升序

    // ---- 标签筛选 ----
    val selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val allTags = MutableStateFlow<List<String>>(emptyList())
    val tagsLoading = MutableStateFlow(false)

    private val _favMangas = MutableStateFlow<List<Manga>>(emptyList())
    val favMangas: StateFlow<List<Manga>> = _favMangas.asStateFlow()
    private val _favLoading = MutableStateFlow(false)
    val favLoading: StateFlow<Boolean> = _favLoading.asStateFlow()
    private val _favAppending = MutableStateFlow(false)
    val favAppending: StateFlow<Boolean> = _favAppending.asStateFlow()
    private val _favHasMore = MutableStateFlow(true)
    val favHasMore: StateFlow<Boolean> = _favHasMore.asStateFlow()

    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<Int>>(emptySet())

    // ---- 历史 ----
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()
    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()
    private val _historyHasMore = MutableStateFlow(true)
    val historyHasMore: StateFlow<Boolean> = _historyHasMore.asStateFlow()
    val historySelectionMode = MutableStateFlow(false)
    val historySelectedIds = MutableStateFlow<Set<Int>>(emptySet())

    val needLogin = MutableStateFlow(false)
    val user = UserRepository.userFlow

    private var favPage = 1
    private var historyPage = 1

    init { loadFolders(); refreshFavorites(); refreshHistory() }

    fun selectMainTab(t: Int) { mainTab.value = t }

    // ---------- 收藏 ----------
    fun loadFolders() {
        scope.launch {
            try { folders.value = FavoriteRepository.getFolderList() }
            catch (_: NotLoggedInException) { needLogin.value = true }
            catch (_: Exception) {}
        }
    }

    fun selectFolder(id: Int) { selectedFolderId.value = id; refreshFavorites() }
    fun selectIsEnd(v: Int) { isEnd.value = v; refreshFavorites() }
    fun selectIsFull(v: Int) { isFullVersion.value = v; refreshFavorites() }
    fun toggleOnlyUpdated() {
        showOnlyUpdated.value = if (showOnlyUpdated.value == 1) -1 else 1; refreshFavorites()
    }
    fun toggleOrder() { order.value = if (order.value == 1) 2 else 1; refreshFavorites() }
    fun toggleOrderType() { orderType.value = if (orderType.value == 0) 1 else 0; refreshFavorites() }

    // ---------- 标签筛选 ----------
    fun loadTagsIfNeeded() {
        if (allTags.value.isNotEmpty() || tagsLoading.value) return
        scope.launch {
            tagsLoading.value = true
            try {
                val groups = MangaRepository.getTags()
                allTags.value = groups.flatten().flatMap { grp -> grp.list.map { it.name } }.distinct()
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "loadTagsIfNeeded failed", e)
            } finally {
                tagsLoading.value = false
            }
        }
    }

    fun toggleTag(tag: String) {
        val cur = selectedTags.value.toMutableSet()
        if (!cur.add(tag)) cur.remove(tag)
        selectedTags.value = cur
        refreshFavorites()
    }

    fun clearTags() { selectedTags.value = emptySet(); refreshFavorites() }

    fun refreshFavorites() {
        scope.launch { loadFav(reset = true) }
    }

    fun loadMoreFavorites() {
        if (_favLoading.value || _favAppending.value || !_favHasMore.value) return
        scope.launch { loadFav(reset = false) }
    }

    private suspend fun loadFav(reset: Boolean) {
        if (!UserRepository.isLoggedIn) { needLogin.value = true; return }
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
                tag = selectedTags.value.joinToString(","),
            )
            favPage++
            _favMangas.value = if (reset) list else _favMangas.value + list
            if (list.isEmpty()) _favHasMore.value = false
        } catch (_: NotLoggedInException) {
            needLogin.value = true
        } catch (_: Exception) {
        } finally {
            _favLoading.value = false
            _favAppending.value = false
        }
    }

    fun enterSelection() { selectionMode.value = true; selectedIds.value = emptySet() }
    fun exitSelection() { selectionMode.value = false; selectedIds.value = emptySet() }
    fun toggleSelect(id: Int) {
        val s = selectedIds.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        selectedIds.value = s
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

    fun moveSelectedTo(folderId: Int) {
        val ids = selectedIds.value.joinToString(",")
        if (ids.isEmpty()) return
        scope.launch {
            try {
                FavoriteRepository.moveToFolder(ids, folderId)
                exitSelection()
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
        } catch (_: Exception) {
        } finally {
            _historyLoading.value = false
        }
    }

    fun enterHistorySelection() { historySelectionMode.value = true; historySelectedIds.value = emptySet() }
    fun exitHistorySelection() { historySelectionMode.value = false; historySelectedIds.value = emptySet() }
    fun toggleHistorySelect(id: Int) {
        val s = historySelectedIds.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        historySelectedIds.value = s
    }
    fun selectAllLoadedHistory() {
        historySelectedIds.value = _history.value.map { it.id }.toSet()
    }

    fun deleteSelectedHistory() {
        val ids = historySelectedIds.value.joinToString(",")
        if (ids.isEmpty()) return
        scope.launch {
            try {
                HistoryRepository.deleteHistory(ids)
                exitHistorySelection()
                refreshHistory()
            } catch (_: Exception) {}
        }
    }
}
