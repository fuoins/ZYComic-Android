package com.zycomic.app.ui.browse

import com.zycomic.app.data.AllTags
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.FavoriteRepository
import com.zycomic.app.data.repository.MangaRepository
import com.zycomic.app.data.repository.TagRepository
import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 分类页 ViewModel（普通类，由 remember 持有）。三个 tab 独立数据。 */
class BrowseViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- 主 Tab：0 分类 / 1 最近更新 / 2 排行 ----
    val mainTab = MutableStateFlow(0)

    /** 单个 tab 的列表状态 */
    private class TabState {
        val mangas = MutableStateFlow<List<Manga>>(emptyList())
        val loading = MutableStateFlow(false)
        val appending = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)
        val hasMore = MutableStateFlow(true)
        var currentPage = 1
        var loaded = false  // 该 tab 是否已经加载过数据
    }

    private val tabStates = Array(3) { TabState() }

    /** 当前 tab 的状态（随 mainTab 变化） */
    private val cur: TabState get() = tabStates[mainTab.value]

    val mangas: StateFlow<List<Manga>> get() = cur.mangas
    val loading: StateFlow<Boolean> get() = cur.loading
    val appending: StateFlow<Boolean> get() = cur.appending
    val error: StateFlow<String?> get() = cur.error
    val hasMore: StateFlow<Boolean> get() = cur.hasMore

    fun mangasForTab(tab: Int): StateFlow<List<Manga>> = tabStates[tab].mangas
    fun loadingForTab(tab: Int): StateFlow<Boolean> = tabStates[tab].loading
    fun appendingForTab(tab: Int): StateFlow<Boolean> = tabStates[tab].appending
    fun hasMoreForTab(tab: Int): StateFlow<Boolean> = tabStates[tab].hasMore
    fun errorForTab(tab: Int): StateFlow<String?> = tabStates[tab].error

    // ---- 分类筛选状态 ----
    val gender = MutableStateFlow(2)            // 默认 2 一般向
    val selectedTags = MutableStateFlow<Set<String>>(emptySet()) // 空=全部
    val area = MutableStateFlow(0)
    val end = MutableStateFlow(0)
    val st = MutableStateFlow(2)                // 默认 2 收藏
    val filterExpanded = MutableStateFlow(true)

    // 全部标签（内置，"更多"弹窗）
    val allTags = MutableStateFlow<List<String>>(AllTags.LIST)

    // ---- 最近更新 ----
    val newestDate = MutableStateFlow("")       // ""=7天
    val newestNums = MutableStateFlow(0)

    // ---- 排行子 Tab：0 人气 / 1 新番 / 2 完结 ----
    val rankType = MutableStateFlow(0)

    // ---- 显示模式：0紧凑网格 1舒适网格(默认) 2仅封面网格 ----（持久化）
    val displayMode = MutableStateFlow(prefs.getInt("browse_display_mode", 1))
    val gridColumns = MutableStateFlow(3)

    init { migrateGridColumnsIfNeeded("browse") }

    fun setDisplayMode(mode: Int) {
        displayMode.value = mode
        prefs.edit().putInt("browse_display_mode", mode).apply()
    }
    fun loadGridColumnsForOrientation(orientation: Int) {
        val k = if (orientation == 2) "browse_grid_columns_landscape" else "browse_grid_columns_portrait"
        gridColumns.value = prefs.getInt(k, 3)
    }
    fun setGridColumns(cols: Int, orientation: Int) {
        gridColumns.value = cols
        val k = if (orientation == 2) "browse_grid_columns_landscape" else "browse_grid_columns_portrait"
        prefs.edit().putInt(k, cols).apply()
    }
    private fun migrateGridColumnsIfNeeded(page: String) {
        val old = prefs.getInt("${page}_grid_columns", -1)
        if (old >= 0 && !prefs.contains("${page}_grid_columns_portrait")) {
            prefs.edit().putInt("${page}_grid_columns_portrait", old).remove("${page}_grid_columns").apply()
        }
    }

    // ---- 多选模式 ----
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    // ---- 收藏夹列表 ----
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()
    private val _foldersLoading = MutableStateFlow(false)
    val foldersLoading: StateFlow<Boolean> = _foldersLoading.asStateFlow()

    // ---- 登录状态 ----
    val user = UserRepository.userFlow

    /** 请求序号：每次自增，用于丢弃过期请求的结果（竞态防护）。 */
    private val requestSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 当前加载任务：启动新请求前取消旧的，避免旧页面残留。 */
    private var currentJob: Job? = null

    /** 日期选项：7天 + 今天 + 前6天。 */
    val dateOptions: List<Pair<String, String>> = buildDateOptions()

    init {
        // 初始化时只加载第一个 tab（分类），其他 tab 切换时再加载
        refresh()
    }

    fun selectMainTab(tab: Int) {
        if (mainTab.value == tab) return
        mainTab.value = tab
        // 切换到该 tab 时，如果还没加载过数据，自动加载
        if (!tabStates[tab].loaded) {
            refresh()
        }
    }

    fun selectRankType(type: Int) {
        if (rankType.value == type) return
        rankType.value = type
        refresh()
    }

    fun selectGender(v: Int) { gender.value = v; refresh() }
    fun selectArea(v: Int) { area.value = v; refresh() }
    fun selectEnd(v: Int) { end.value = v; refresh() }
    fun selectSt(v: Int) { st.value = v; refresh() }
    fun toggleFilterExpanded() { filterExpanded.value = !filterExpanded.value }

    fun toggleTag(tag: String) {
        val curTags = selectedTags.value.toMutableSet()
        if (!curTags.add(tag)) curTags.remove(tag)
        selectedTags.value = curTags
        refresh()
    }

    fun clearTags() { selectedTags.value = emptySet(); refresh() }

    /** 从"更多"弹窗直接设置完整选择集合。 */
    fun setSelectedTags(tags: Set<String>) {
        selectedTags.value = tags
        refresh()
    }

    fun selectDate(date: String) { newestDate.value = date; refresh() }

    // ---- 多选 ----
    fun enterSelection() { selectionMode.value = true; selectedIds.value = emptySet() }
    fun exitSelection() { selectionMode.value = false; selectedIds.value = emptySet() }
    fun toggleSelect(id: String) {
        val curIds = selectedIds.value.toMutableSet()
        if (!curIds.add(id)) curIds.remove(id)
        selectedIds.value = curIds
    }
    fun selectAll() { selectedIds.value = cur.mangas.value.map { it.id }.toSet() }
    fun invertSelection() {
        val all = cur.mangas.value.map { it.id }.toSet()
        selectedIds.value = all - selectedIds.value
    }

    // ---- 收藏夹 ----
    fun loadFolders() {
        if (_foldersLoading.value) return
        _foldersLoading.value = true
        scope.launch {
            try {
                _folders.value = FavoriteRepository.getFolderList()
            } catch (_: Exception) {
            } finally {
                _foldersLoading.value = false
            }
        }
    }

    /** 添加选中漫画到全部收藏夹（folder_id=0） */
    fun addToAllFolders(onDone: (Boolean, String) -> Unit) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) { onDone(false, "请先选择漫画"); return }
        scope.launch {
            try {
                ids.forEach { id ->
                    FavoriteRepository.addFavorite(id.toIntOrNull() ?: 0, 0)
                }
                onDone(true, "已添加 ${ids.size} 本到全部收藏夹")
            } catch (e: Exception) {
                onDone(false, e.message ?: "收藏失败")
            }
        }
    }

    /** 添加选中漫画到指定收藏夹 */
    fun addToFolder(folderId: String, onDone: (Boolean, String) -> Unit) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) { onDone(false, "请先选择漫画"); return }
        scope.launch {
            try {
                ids.forEach { id ->
                    FavoriteRepository.addFavorite(id.toIntOrNull() ?: 0, folderId.toIntOrNull() ?: 0)
                }
                onDone(true, "已添加 ${ids.size} 本到收藏夹")
            } catch (e: Exception) {
                onDone(false, e.message ?: "收藏失败")
            }
        }
    }

    /** 从详情页标签点击返回：选中该标签并切到分类 Tab。 */
    fun applyPendingTag(tag: String) {
        mainTab.value = 0
        selectedTags.value = setOf(tag)
        refresh()
    }

    fun refresh() {
        // 取消旧任务，启动新请求前确保只有一个加载任务
        currentJob?.cancel()
        currentJob = scope.launch { doLoad(reset = true) }
    }

    fun loadMore(tab: Int) {
        val ts = tabStates[tab]
        if (ts.loading.value || ts.appending.value || !ts.hasMore.value) return
        currentJob = scope.launch { doLoad(reset = false, tab = tab) }
    }

    private suspend fun doLoad(reset: Boolean, tab: Int = mainTab.value) {
        val ts = tabStates[tab]
        // 自增请求序号；用于判断本次结果是否已被更新的请求取代
        val reqId = requestSeq.incrementAndGet()
        if (reset) {
            ts.currentPage = 1
            ts.hasMore.value = true
            ts.loading.value = true
            ts.error.value = null
            // 重置时先清空列表，避免旧数据残留
            ts.mangas.value = emptyList()
        } else {
            ts.appending.value = true
        }
        try {
            val target = if (reset) 30 else 15
            var lastInvoked = ts.currentPage

            val rawLoader: suspend (Int) -> List<Manga> = { p ->
                lastInvoked = p
                when (tab) {
                    1 -> MangaRepository.getNewest(p, newestDate.value, 30).first
                    2 -> MangaRepository.getRank(rankType.value, p)
                    else -> MangaRepository.getClasses(
                        page = p,
                        gender = gender.value,
                        tag = selectedTags.value.joinToString(","),
                        area = area.value,
                        end = end.value,
                        st = st.value,
                    )
                }
            }

            val result = if (TagRepository.isFilterEnabled()) {
                MangaRepository.loadWithFilter(ts.currentPage, target, rawLoader)
            } else {
                rawLoader(ts.currentPage)
            }

            // 竞态防护：期间若有更新的请求，丢弃本次结果
            if (reqId != requestSeq.get()) return
            ts.currentPage = lastInvoked + 1

            if (tab == 1 && reset) {
                newestNums.value = MangaRepository.getNewest(1, newestDate.value, 30).second
            }

            ts.mangas.value = if (reset) result else ts.mangas.value + result
            ts.loaded = true
            if (result.isEmpty()) ts.hasMore.value = false
        } catch (e: Exception) {
            // 过期请求的异常不更新 UI
            if (reqId != requestSeq.get()) return
            if (reset) ts.error.value = e.message ?: "加载失败"
        } finally {
            // 仅当仍是最新请求时才复位加载态，避免旧任务覆盖新任务的状态
            if (reqId == requestSeq.get()) {
                ts.loading.value = false
                ts.appending.value = false
            }
        }
    }

    private fun buildDateOptions(): List<Pair<String, String>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val labelFmt = SimpleDateFormat("MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        val opts = mutableListOf<Pair<String, String>>()
        opts.add("7天" to "")
        for (i in 0..6) {
            val d = Date(cal.timeInMillis - i * 86400000L)
            val key = fmt.format(d)
            val label = when (i) {
                0 -> "今天(${labelFmt.format(d)})"
                1 -> "昨天(${labelFmt.format(d)})"
                else -> labelFmt.format(d)
            }
            opts.add(label to key)
        }
        return opts
    }

    companion object {
        val GENDERS = listOf(-1 to "全部", 2 to "一般向", 1 to "禁漫", 0 to "BL向", 3 to "TL向", 4 to "GL向")
        val AREAS = listOf(0 to "全部", 1 to "韩国", 2 to "日漫", 3 to "国漫", 4 to "台漫", 5 to "其他", 6 to "未分类")
        val ENDS = listOf(0 to "全部", 1 to "连载", 2 to "完结")
        val STS = listOf(2 to "收藏", 0 to "最新", 1 to "最旧", 3 to "新漫")

        private lateinit var prefs: android.content.SharedPreferences
        fun init(context: android.content.Context) {
            prefs = context.getSharedPreferences("zycomic_display", android.content.Context.MODE_PRIVATE)
        }
    }
}
