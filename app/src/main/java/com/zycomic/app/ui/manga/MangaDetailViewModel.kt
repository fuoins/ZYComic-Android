package com.zycomic.app.ui.manga

import com.zycomic.app.data.dto.Chapter
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.data.dto.JmComment
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.BookBlockRepository
import com.zycomic.app.data.repository.FavoriteRepository
import com.zycomic.app.data.repository.HistoryRepository
import com.zycomic.app.data.repository.MangaRepository
import com.zycomic.app.data.repository.NotLoggedInException
import com.zycomic.app.data.repository.TagRepository
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.net.JmAuthStore
import com.zycomic.app.reader.JmSource
import com.zycomic.app.reader.ManwaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.repository.ChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.manga.repository.MangaRepository as LocalMangaRepository

class MangaDetailViewModel(private val bookId: String) {

    val isJm: Boolean = bookId.startsWith("jm:")
    private val contentId: String = bookId.removePrefix("jm:")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _detail = MutableStateFlow<Manga?>(null)
    val detail: StateFlow<Manga?> = _detail.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _chapterWarning = MutableStateFlow<String?>(null)
    val chapterWarning: StateFlow<String?> = _chapterWarning.asStateFlow()

    val activeTab = MutableStateFlow(0) // 0 章节 / 1 相关推荐
    val chapterAsc = MutableStateFlow(false) // 默认降序
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val favBusy = MutableStateFlow(false)
    val jmNeedLogin = MutableStateFlow(false)
    val jmActionBusy = MutableStateFlow(false)
    val jmCommentCount = MutableStateFlow<Int?>(null)
    val jmComments = MutableStateFlow<List<JmComment>>(emptyList())
    val jmCommentsLoading = MutableStateFlow(false)
    val jmCommentsError = MutableStateFlow<String?>(null)
    val jmCommentsHasMore = MutableStateFlow(true)
    private var jmCommentPage = 0
    private var jmStateRefreshRequired = false
    val jmAccountName = MutableStateFlow(JmAuthStore.accountName ?: if (JmAuthStore.token() != null) "已登录" else null)
    val jmLoginBusy = MutableStateFlow(false)
    val jmLoginError = MutableStateFlow<String?>(null)

    /** 未登录事件：UI 层收到后跳转登录页。 */
    val needLogin = MutableStateFlow(false)

    /** 收藏成功事件：UI 层收到后显示 toast。 */
    private val _favSuccess = MutableSharedFlow<String>()
    val favSuccess: SharedFlow<String> = _favSuccess.asSharedFlow()

    /**
     * 屏蔽操作结果事件：UI 层收到后显示 toast。
     * 内容直接采用服务端文案（"已加入屏蔽" / "已從屏蔽中移除"），
     * 因为接口是 toggle，只有服务端知道这次到底是屏蔽还是取消。
     */
    private val _blockMessage = MutableSharedFlow<String>()
    val blockMessage: SharedFlow<String> = _blockMessage.asSharedFlow()

    /** 屏蔽请求进行中，用于禁用按钮避免连点重复 toggle。 */
    val blockBusy = MutableStateFlow(false)

    /** 添加当前漫画标签到账号屏蔽列表的提交状态与结果事件。 */
    val addingBlacklist = MutableStateFlow(false)
    private val _blacklistMessage = MutableSharedFlow<String>()
    val blacklistMessage: SharedFlow<String> = _blacklistMessage.asSharedFlow()

    val user = UserRepository.userFlow

    init {
        load()
    }

    fun load() {
        scope.launch {
            _loading.value = true
            _error.value = null
            _chapterWarning.value = null
            try {
                if (isJm) {
                    val result = JmSource.getAlbum(contentId)
                    _detail.value = result.manga
                    _chapterWarning.value = result.chapterWarning
                    loadJmComments()
                } else {
                    _detail.value = MangaRepository.getDetail(bookId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _detail.value = null
                _error.value = e.message ?: "加载失败"
                if (isJm && JmAuthStore.token() == null && jmAccountName.value != null) {
                    jmAccountName.value = null
                    jmNeedLogin.value = true
                }
            } finally {
                _loading.value = false
            }
        }
    }

    fun selectTab(tab: Int) {
        activeTab.value = tab
    }
    fun toggleChapterSort() {
        chapterAsc.value = !chapterAsc.value
    }

    /** Merge local reader read status into the existing detail without reloading other fields. */
    fun syncChapterReadStatus() {
        if (_detail.value == null) return
        scope.launch {
            val readChapterUrls = try {
                withContext(Dispatchers.IO) {
                    val localManga = Injekt.get<LocalMangaRepository>()
                        .getMangaByUrlAndSourceId(
                            if (isJm) "/album/$contentId" else "/manga/$contentId",
                            if (isJm) JmSource.id else ManwaSource.id,
                        )
                        ?: return@withContext emptySet()
                    Injekt.get<ChapterRepository>()
                        .getChapterByMangaId(localManga.id)
                        .asSequence()
                        .filter { it.read }
                        .map { it.url }
                        .toSet()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (readChapterUrls.isEmpty()) return@launch
            val currentDetail = _detail.value ?: return@launch
            val updatedChapters = currentDetail.chapterList.map { chapter ->
                if ("/chapter/${chapter.id}" in readChapterUrls) chapter.copy(readed = 1) else chapter
            }
            if (updatedChapters != currentDetail.chapterList) {
                _detail.value = currentDetail.copy(chapterList = updatedChapters)
            }
        }
    }

    /** 已收藏状态：以 detail.fav 为准。 */
    val isFavorited: Boolean get() = _detail.value?.fav == 1

    /** 快速收藏/取消收藏。 */
    fun toggleFavorite() {
        if (isJm) {
            toggleJmFavorite()
            return
        }
        scope.launch {
            favBusy.value = true
            try {
                val d = _detail.value ?: return@launch
                val bookIdInt = bookId.toIntOrNull() ?: return@launch
                if (d.fav == 1) {
                    FavoriteRepository.removeFavorite(bookIdInt)
                } else {
                    FavoriteRepository.addFavorite(bookIdInt, 0)
                }
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

    private fun toggleJmFavorite() {
        if (!isJm) return
        if (!JmToggleActionPolicy.canStart(jmActionBusy.value, JmAuthStore.token() != null)) {
            if (JmAuthStore.token() == null) jmNeedLogin.value = true
            return
        }
        scope.launch {
            jmActionBusy.value = true
            try {
                if (jmStateRefreshRequired) {
                    if (refreshJmState()) {
                        jmStateRefreshRequired = false
                    } else {
                        _favSuccess.emit("收藏状态暂无法确认，请刷新后重试")
                    }
                    return@launch
                }
                val before = _detail.value?.fav == 1
                try {
                    JmSource.toggleFavorite(contentId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    jmStateRefreshRequired = true
                    val refreshed = refreshJmState()
                    if (refreshed) jmStateRefreshRequired = false
                    if (JmAuthStore.token() == null) jmNeedLogin.value = true
                    _favSuccess.emit(if (refreshed) "收藏请求未确认，已刷新当前状态" else "收藏请求结果不确定，刷新状态失败；请勿重复操作")
                    return@launch
                }
                jmStateRefreshRequired = true
                val refreshed = refreshJmState()
                jmStateRefreshRequired = !refreshed
                val after = _detail.value?.fav == 1
                _favSuccess.emit(
                    if (refreshed && after != before) {
                        if (after) "已收藏" else "已取消收藏"
                    } else {
                        "收藏状态未能确认，请刷新"
                    },
                )
            } finally {
                jmActionBusy.value = false
            }
        }
    }

    fun loadJmComments(nextPage: Boolean = false) {
        if (!isJm || jmCommentsLoading.value || (nextPage && !jmCommentsHasMore.value)) return
        val requested = if (nextPage) jmCommentPage + 1 else 1
        scope.launch {
            jmCommentsLoading.value = true
            jmCommentsError.value = null
            try {
                val result = JmSource.getComments(contentId, requested)
                jmCommentCount.value = result.total
                jmComments.value = (if (nextPage) jmComments.value + result.comments else result.comments).distinctBy { it.id }
                jmCommentPage = requested
                jmCommentsHasMore.value = result.hasMore
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                jmCommentsError.value = e.message ?: "评论加载失败"
            } finally {
                jmCommentsLoading.value = false
            }
        }
    }

    fun postJmComment(content: String, onSuccess: () -> Unit = {}) {
        if (!isJm || jmActionBusy.value || content.isBlank()) return
        if (JmAuthStore.token() == null) {
            jmNeedLogin.value = true
            return
        }
        scope.launch {
            jmActionBusy.value = true
            var posted = false
            try {
                JmSource.postComment(contentId, content.trim())
                posted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                jmCommentsError.value = e.message ?: "评论结果不确定，请刷新评论确认后再决定是否重试"
                if (JmAuthStore.token() == null) {
                    jmNeedLogin.value = true
                    jmAccountName.value = null
                }
                _favSuccess.emit(jmCommentsError.value ?: "评论发送失败")
            } finally {
                jmCommentPage = 0
                loadJmComments()
                jmActionBusy.value = false
                if (posted) {
                    _favSuccess.emit("评论已发送")
                    runCatching(onSuccess)
                }
            }
        }
    }

    fun toggleJmLike() {
        if (!isJm) return
        if (!JmToggleActionPolicy.canStart(jmActionBusy.value, JmAuthStore.token() != null)) {
            if (JmAuthStore.token() == null) jmNeedLogin.value = true
            return
        }
        scope.launch {
            jmActionBusy.value = true
            try {
                if (jmStateRefreshRequired) {
                    if (refreshJmState()) {
                        jmStateRefreshRequired = false
                    } else {
                        _favSuccess.emit("点赞状态暂无法确认，请刷新后重试")
                    }
                    return@launch
                }
                val before = _detail.value?.jmLiked
                try {
                    JmSource.toggleLike(contentId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    jmStateRefreshRequired = true
                    val refreshed = refreshJmState()
                    if (refreshed) jmStateRefreshRequired = false
                    if (JmAuthStore.token() == null) jmNeedLogin.value = true
                    _favSuccess.emit(if (refreshed) "点赞请求未确认，已刷新当前状态" else "点赞请求结果不确定，刷新状态失败；请勿重复操作")
                    return@launch
                }
                jmStateRefreshRequired = true
                val refreshed = refreshJmState()
                jmStateRefreshRequired = !refreshed
                val after = _detail.value?.jmLiked
                _favSuccess.emit(
                    if (refreshed && before != null && after != null && after != before) {
                        if (after) "已点赞" else "已取消点赞"
                    } else {
                        "点赞状态未能确认，请刷新"
                    },
                )
            } finally {
                jmActionBusy.value = false
            }
        }
    }

    fun loginJm(username: String, password: String) {
        if (!isJm || jmLoginBusy.value) return
        scope.launch {
            jmLoginBusy.value = true
            jmLoginError.value = null
            try {
                jmAccountName.value = JmSource.login(username.trim(), password)
                jmNeedLogin.value = false
                refreshJmState()
                jmCommentPage = 0
                loadJmComments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                jmLoginError.value = e.message ?: "JM 登录失败"
            } finally {
                jmLoginBusy.value = false
            }
        }
    }

    fun logoutJm() {
        if (!isJm) return
        JmSource.logout()
        jmAccountName.value = null
        jmNeedLogin.value = false
        scope.launch { refreshJmState() }
    }

    private suspend fun refreshJmState(): Boolean {
        return try {
            val fresh = JmSource.getAlbum(contentId).manga
            _detail.value = fresh.copy(chapterList = _detail.value?.chapterList ?: fresh.chapterList)
            if (JmAuthStore.token() == null && jmAccountName.value != null) {
                jmAccountName.value = null
                jmNeedLogin.value = true
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (JmAuthStore.token() == null && jmAccountName.value != null) {
                jmAccountName.value = null
                jmNeedLogin.value = true
            }
            false
        }
    }

    /** 打开收藏夹选择：拉取分类列表。 */
    fun openFolderPicker() {
        if (isJm) return
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
        if (isJm) return
        scope.launch {
            favBusy.value = true
            try {
                val bookIdInt = bookId.toIntOrNull() ?: return@launch
                FavoriteRepository.addFavorite(bookIdInt, folderId)
                _detail.value = _detail.value?.copy(fav = 1)
                UserRepository.refreshUserInfo()
                _favSuccess.emit("收藏成功")
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
        if (isJm) return
        scope.launch {
            favBusy.value = true
            try {
                val bookIdInt = bookId.toIntOrNull() ?: return@launch
                FavoriteRepository.addFavorite(bookIdInt, 0)
                _detail.value = _detail.value?.copy(fav = 1)
                UserRepository.refreshUserInfo()
                _favSuccess.emit("收藏成功")
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (_: Exception) {
            } finally {
                favBusy.value = false
            }
        }
    }

    /**
     * 对当前漫画执行屏蔽开关。
     *
     * 服务端 `updateBookBlacklist` 是 toggle：没屏蔽过→屏蔽，已屏蔽→取消。
     * 按钮文案固定叫「屏蔽」（按产品决策不额外拉列表判断状态），
     * 所以这里必须把服务端返回的真实结果用 toast 讲清楚，用户才知道发生了什么。
     */
    fun toggleBlock() {
        if (isJm) return
        scope.launch {
            blockBusy.value = true
            try {
                val id = bookId.toLongOrNull() ?: run {
                    _blockMessage.emit("漫画 ID 异常，无法屏蔽")
                    return@launch
                }
                val r = BookBlockRepository.toggleBlock(id)
                _blockMessage.emit(r.message.ifBlank { if (r.isBlacklisted) "已加入屏蔽" else "已从屏蔽中移除" })
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (e: Exception) {
                _blockMessage.emit(e.message ?: "屏蔽操作失败，请重试")
            } finally {
                blockBusy.value = false
            }
        }
    }

    /** 将详情页选中的漫画标签加入账号屏蔽列表。 */
    fun submitAddBlacklist(tags: List<String>, onSuccess: () -> Unit) {
        if (isJm) return
        if (tags.isEmpty() || addingBlacklist.value) return
        scope.launch {
            addingBlacklist.value = true
            try {
                TagRepository.addBlackTags(tags)
                _blacklistMessage.emit("已添加 ${tags.size} 个标签到屏蔽列表")
                onSuccess()
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (e: Exception) {
                android.util.Log.e("MangaDetailViewModel", "submitAddBlacklist failed", e)
                _blacklistMessage.emit(e.message ?: "添加失败，请重试")
            } finally {
                addingBlacklist.value = false
            }
        }
    }

    /** 删除当前漫画的阅读历史。bookId 为字符串，单本直接传入。 */
    fun deleteHistory() {
        if (isJm) return
        scope.launch {
            try {
                HistoryRepository.deleteHistory(bookId)
            } catch (e: NotLoggedInException) {
                needLogin.value = true
            } catch (_: Exception) {
            }
        }
    }

    /** 章节列表：按 sort 排序，默认降序（最新章节在最上面）。 */
    fun sortedChapters(): List<Chapter> {
        val list = _detail.value?.chapterList ?: return emptyList()
        return if (chapterAsc.value) list.sortedBy { it.sort } else list.sortedByDescending { it.sort }
    }

    /**
     * 继续阅读章节 ID（字符串）。
     * 规则：detail.start 非空且不为 "0" 时直接用（上次阅读章节）；
     * 否则返回第一章（服务端降序返回，第一章在列表末尾）。
     */
    fun defaultChapterId(): String {
        val list = _detail.value?.chapterList ?: return ""
        val d = _detail.value!!
        if (d.start.isNotBlank() && d.start != "0" && (!isJm || list.any { it.id == d.start })) return d.start
        return list.minByOrNull { it.sort }?.id ?: list.firstOrNull()?.id ?: ""
    }
}
