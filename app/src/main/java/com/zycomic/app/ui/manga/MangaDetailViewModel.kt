package com.zycomic.app.ui.manga

import com.zycomic.app.data.dto.Chapter
import com.zycomic.app.data.dto.Folder
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

class MangaDetailViewModel(private val bookId: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _detail = MutableStateFlow<Manga?>(null)
    val detail: StateFlow<Manga?> = _detail.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val activeTab = MutableStateFlow(0)            // 0 章节 / 1 相关推荐
    val chapterAsc = MutableStateFlow(false)       // 默认降序
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val favBusy = MutableStateFlow(false)

    /** 未登录事件：UI 层收到后跳转登录页。 */
    val needLogin = MutableStateFlow(false)

    val user = UserRepository.userFlow

    init { load() }

    fun load() {
        scope.launch {
            _loading.value = true
            _error.value = null
            try {
                _detail.value = MangaRepository.getDetail(bookId)
            } catch (e: Exception) {
                _error.value = e.message ?: "加载失败"
            } finally {
                _loading.value = false
            }
        }
    }

    fun selectTab(tab: Int) { activeTab.value = tab }
    fun toggleChapterSort() { chapterAsc.value = !chapterAsc.value }

    /** 已收藏状态：以 detail.fav 为准。 */
    val isFavorited: Boolean get() = _detail.value?.fav == 1

    /** 快速收藏/取消收藏。 */
    fun toggleFavorite() {
        scope.launch {
            favBusy.value = true
            try {
                val d = _detail.value ?: return@launch
                val bookIdInt = bookId.toIntOrNull() ?: return@launch
                if (d.fav == 1) FavoriteRepository.removeFavorite(bookIdInt)
                else FavoriteRepository.addFavorite(bookIdInt, 0)
                _detail.value = d.copy(fav = if (d.fav == 1) 0 else 1)
                UserRepository.refreshUserInfo()
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (_: Exception) {
            } finally {
                favBusy.value = false
            }
        }
    }

    /** 打开收藏夹选择：拉取分类列表。 */
    fun openFolderPicker() {
        scope.launch {
            try {
                folders.value = FavoriteRepository.getFolderList()
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (_: Exception) {
            }
        }
    }

    /** 收藏到指定分类。 */
    fun addToFolder(folderId: Int) {
        scope.launch {
            favBusy.value = true
            try {
                val bookIdInt = bookId.toIntOrNull() ?: return@launch
                FavoriteRepository.addFavorite(bookIdInt, folderId)
                _detail.value = _detail.value?.copy(fav = 1)
                UserRepository.refreshUserInfo()
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (_: Exception) {
            } finally {
                favBusy.value = false
            }
        }
    }

    /**
     * 添加到全部收藏夹（folderId=0）。
     * 逻辑同 [addToFolder](0)，但作为独立语义的操作入口。
     */
    fun addToAllFolders() {
        scope.launch {
            favBusy.value = true
            try {
                val bookIdInt = bookId.toIntOrNull() ?: return@launch
                FavoriteRepository.addFavorite(bookIdInt, 0)
                _detail.value = _detail.value?.copy(fav = 1)
                UserRepository.refreshUserInfo()
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (_: Exception) {
            } finally {
                favBusy.value = false
            }
        }
    }

    /** 删除当前漫画的阅读历史。bookId 为字符串，单本直接传入。 */
    fun deleteHistory() {
        scope.launch {
            try {
                HistoryRepository.deleteHistory(bookId)
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (_: Exception) {
            }
        }
    }

    /** 章节列表：服务端返回顺序即降序（最新在前），升序时反转。 */
    fun sortedChapters(): List<Chapter> {
        val list = _detail.value?.chapterList ?: return emptyList()
        return if (chapterAsc.value) list.reversed() else list.toList()
    }

    /**
     * 继续阅读章节 ID（字符串）。
     * 规则：detail.start 非空且不为 "0" 时直接用（上次阅读章节）；
     * 否则返回第一章（服务端降序返回，第一章在列表末尾）。
     */
    fun defaultChapterId(): String {
        val list = _detail.value?.chapterList ?: return ""
        val d = _detail.value!!
        if (d.start.isNotBlank() && d.start != "0") return d.start
        return list.lastOrNull()?.id ?: ""
    }
}
