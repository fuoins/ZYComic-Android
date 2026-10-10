package com.zycomic.app.ui.browse

import androidx.compose.runtime.mutableStateListOf
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

/**
 * 分类页 ViewModel（普通类，由 remember 持有）。
 * Tab 列表可由用户增删改并持久化；每个 FILTER tab 持有独立列表状态，
 * 筛选默认值来自预设 [BrowseTabItem.filter]；「重新筛选」的改动仅为当次会话临时态，不回存预设。
 */
class BrowseViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- 可持久化的有序 Tab 列表（Compose 可观察） ----
    val tabs = mutableStateListOf<BrowseTabItem>().apply { addAll(BrowseTabStore.load()) }

    /** 当前选中 tab 的下标。 */
    val mainTab = MutableStateFlow(0)

    /** 单个 tab 的列表状态，按 tab id 存放。 */
    private class TabState {
        val mangas = MutableStateFlow<List<Manga>>(emptyList())
        val loading = MutableStateFlow(false)
        val appending = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)
        val hasMore = MutableStateFlow(true)
        var currentPage = 1
        var loaded = false
    }

    private val stateById = HashMap<String, TabState>()

    private fun stateFor(page: Int): TabState {
        val id = tabs[page].id
        return stateById.getOrPut(id) { TabState() }
    }

    private val cur: TabState get() = stateFor(mainTab.value)

    fun mangasForTab(tab: Int): StateFlow<List<Manga>> = stateFor(tab).mangas
    fun loadingForTab(tab: Int): StateFlow<Boolean> = stateFor(tab).loading
    fun appendingForTab(tab: Int): StateFlow<Boolean> = stateFor(tab).appending
    fun hasMoreForTab(tab: Int): StateFlow<Boolean> = stateFor(tab).hasMore
    fun errorForTab(tab: Int): StateFlow<String?> = stateFor(tab).error

    // ---- 当前 FILTER tab 的会话临时筛选（进入 tab 时从预设拷贝，切走再回会重置，不持久化） ----
    val curFilter = MutableStateFlow(
        tabs.getOrNull(0)?.filter?.copy() ?: FilterSnapshot(),
    )

    val allTags = MutableStateFlow<List<String>>(AllTags.LIST)

    // ---- 最近更新（SPECIAL: LATEST） ----
    val newestDate = MutableStateFlow("")       // ""=7天
    val newestNums = MutableStateFlow(0)

    // ---- 排行（SPECIAL: RANKING）子类型：0 人气 / 1 新番 / 2 完结 ----
    val rankType = MutableStateFlow(0)

    val filterExpanded = MutableStateFlow(true)

    // ---- 显示模式：0紧凑 1舒适(默认) 2仅封面 ----（持久化）
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

    val user = UserRepository.userFlow

    private val requestSeq = java.util.concurrent.atomic.AtomicLong(0)
    private var currentJob: Job? = null

    val dateOptions: List<Pair<String, String>> = buildDateOptions()

    init {
        bindFilterFor(0)
        refresh()
    }

    /** 进入某个 FILTER tab 时，把会话筛选重置为该 tab 的预设默认值。 */
    private fun bindFilterFor(page: Int) {
        val item = tabs.getOrNull(page) ?: return
        if (item.kind == TabKind.FILTER) {
            curFilter.value = item.filter?.copy() ?: FilterSnapshot()
        }
    }

    fun selectMainTab(tab: Int) {
        if (mainTab.value == tab) return
        mainTab.value = tab
        bindFilterFor(tab)
        if (!stateFor(tab).loaded) refresh()
    }

    fun selectRankType(type: Int) {
        if (rankType.value == type) return
        rankType.value = type
        refresh()
    }

    // ---- 「重新筛选」：仅改当前 tab 的会话临时筛选，不写回预设 ----
    fun selectGender(v: Int) { curFilter.value = curFilter.value.copy(gender = v); refresh() }
    fun selectArea(v: Int) { curFilter.value = curFilter.value.copy(area = v); refresh() }
    fun selectEnd(v: Int) { curFilter.value = curFilter.value.copy(end = v); refresh() }
    fun selectSt(v: Int) { curFilter.value = curFilter.value.copy(st = v); refresh() }
    fun toggleFilterExpanded() { filterExpanded.value = !filterExpanded.value }

    fun toggleTag(tag: String) {
        val f = curFilter.value
        val set = f.tags.toMutableSet()
        if (!set.add(tag)) set.remove(tag)
        curFilter.value = f.copy(tags = set.toList())
        refresh()
    }

    fun clearTags() { curFilter.value = curFilter.value.copy(tags = emptyList()); refresh() }

    fun setSelectedTags(tags: Set<String>) {
        curFilter.value = curFilter.value.copy(tags = tags.toList())
        refresh()
    }

    /** 用一份完整快照替换当前 tab 的会话临时筛选并刷新（「重新筛选」面板用）。 */
    fun applyTempFilter(snapshot: FilterSnapshot) {
        curFilter.value = snapshot
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

    fun addToAllFolders(onDone: (Boolean, String) -> Unit) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) { onDone(false, "请先选择漫画"); return }
        scope.launch {
            try {
                ids.forEach { id -> FavoriteRepository.addFavorite(id.toIntOrNull() ?: 0, 0) }
                onDone(true, "已添加 ${ids.size} 本到全部收藏夹")
            } catch (e: Exception) {
                onDone(false, e.message ?: "收藏失败")
            }
        }
    }

    fun addToFolder(folderId: String, onDone: (Boolean, String) -> Unit) {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) { onDone(false, "请先选择漫画"); return }
        scope.launch {
            try {
                ids.forEach { id -> FavoriteRepository.addFavorite(id.toIntOrNull() ?: 0, folderId.toIntOrNull() ?: 0) }
                onDone(true, "已添加 ${ids.size} 本到收藏夹")
            } catch (e: Exception) {
                onDone(false, e.message ?: "收藏失败")
            }
        }
    }

    /** 从详情页标签点击返回：定位到第一个 FILTER tab，并把该标签作为当次临时筛选。 */
    fun applyPendingTag(tag: String) {
        val idx = tabs.indexOfFirst { it.kind == TabKind.FILTER }.coerceAtLeast(0)
        mainTab.value = idx
        bindFilterFor(idx)
        curFilter.value = (tabs[idx].filter ?: FilterSnapshot()).copy(tags = listOf(tag))
        refresh()
    }

    fun refresh() {
        currentJob?.cancel()
        currentJob = scope.launch { doLoad(reset = true) }
    }

    fun loadMore(tab: Int) {
        val ts = stateFor(tab)
        if (ts.loading.value || ts.appending.value || !ts.hasMore.value) return
        currentJob = scope.launch { doLoad(reset = false, tab = tab) }
    }

    private suspend fun doLoad(reset: Boolean, tab: Int = mainTab.value) {
        val ts = stateFor(tab)
        val item = tabs[tab]
        val reqId = requestSeq.incrementAndGet()
        if (reset) {
            ts.currentPage = 1
            ts.hasMore.value = true
            ts.loading.value = true
            ts.error.value = null
            ts.mangas.value = emptyList()
        } else {
            ts.appending.value = true
        }
        try {
            val target = if (reset) 30 else 15
            var lastInvoked = ts.currentPage

            val rawLoader: suspend (Int) -> List<Manga> = { p ->
                lastInvoked = p
                when (item.kind) {
                    TabKind.SPECIAL -> when (item.pageType) {
                        BrowsePageType.LATEST -> MangaRepository.getNewest(p, newestDate.value, 30).first
                        BrowsePageType.RANKING -> MangaRepository.getRank(rankType.value, p)
                        else -> MangaRepository.getNewest(p, newestDate.value, 30).first
                    }
                    TabKind.FILTER -> {
                        val f = curFilter.value
                        MangaRepository.getClasses(
                            page = p,
                            gender = f.gender,
                            tag = f.tags.joinToString(","),
                            area = f.area,
                            end = f.end,
                            st = f.st,
                        )
                    }
                }
            }

            val result = if (TagRepository.isFilterEnabled()) {
                MangaRepository.loadWithFilter(ts.currentPage, target, rawLoader)
            } else {
                rawLoader(ts.currentPage)
            }

            if (reqId != requestSeq.get()) return
            ts.currentPage = lastInvoked + 1

            if (item.kind == TabKind.SPECIAL && item.pageType == BrowsePageType.LATEST && reset) {
                newestNums.value = MangaRepository.getNewest(1, newestDate.value, 30).second
            }

            ts.mangas.value = if (reset) result else ts.mangas.value + result
            ts.loaded = true
            if (result.isEmpty()) ts.hasMore.value = false
        } catch (e: Exception) {
            if (reqId != requestSeq.get()) return
            if (reset) ts.error.value = e.message ?: "加载失败"
        } finally {
            if (reqId == requestSeq.get()) {
                ts.loading.value = false
                ts.appending.value = false
            }
        }
    }

    // ==================== Tab 管理（预设持久化） ====================

    /** 仅内置特殊页（最近更新）受保护不可删；内置筛选预设与自定义项均可删（可恢复默认）。 */
    fun isProtected(item: BrowseTabItem): Boolean =
        item.kind == TabKind.SPECIAL && item.builtin

    fun isNameTaken(name: String, ignoreId: String? = null): Boolean {
        val n = name.trim()
        return tabs.any { it.id != ignoreId && it.name == n }
    }

    private fun persist() = BrowseTabStore.save(tabs.toList())

    fun renameTab(id: String, name: String): Boolean {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return false
        val n = name.trim()
        if (n.isEmpty() || isNameTaken(n, id)) return false
        tabs[i] = tabs[i].copy(name = n)
        persist()
        return true
    }

    /** 修改某个 FILTER tab 的默认筛选（仅管理面板调用），并即时刷新当前页。 */
    fun updateTabDefaultFilter(id: String, snapshot: FilterSnapshot) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0 || tabs[i].kind != TabKind.FILTER) return
        tabs[i] = tabs[i].copy(filter = snapshot)
        persist()
        if (mainTab.value == i) {
            curFilter.value = snapshot.copy()
            refresh()
        }
    }

    fun deleteTab(id: String) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0 || isProtected(tabs[i])) return
        tabs.removeAt(i)
        stateById.remove(id)
        if (tabs.isEmpty()) tabs.addAll(BrowseTabStore.defaultTabs())
        persist()
        val target = when {
            i < mainTab.value -> mainTab.value - 1
            i == mainTab.value -> 0
            else -> mainTab.value
        }.coerceIn(0, tabs.lastIndex)
        mainTab.value = target
        bindFilterFor(target)
        if (!stateFor(target).loaded) refresh()
    }

    fun addFilterTab(name: String, snapshot: FilterSnapshot): Boolean {
        val n = name.trim()
        if (n.isEmpty() || isNameTaken(n)) return false
        val item = BrowseTabItem(
            id = "custom_${System.currentTimeMillis()}",
            name = n,
            kind = TabKind.FILTER,
            builtin = false,
            filter = snapshot,
        )
        tabs.add(item)
        persist()
        goTo(tabs.lastIndex)
        return true
    }

    fun canAddRanking(): Boolean = tabs.none { it.pageType == BrowsePageType.RANKING }

    /** 添加「gay排行」（整体排行）特殊 tab，固定 id，仅可添加一次。 */
    fun addRankingTab(): Boolean {
        if (!canAddRanking()) return false
        tabs.add(
            BrowseTabItem(
                id = BrowseTabStore.ID_RANKING,
                name = BrowseTabStore.RANKING_NAME,
                kind = TabKind.SPECIAL,
                builtin = false,
                pageType = BrowsePageType.RANKING,
            ),
        )
        persist()
        goTo(tabs.lastIndex)
        return true
    }

    fun resetToDefaults() {
        tabs.clear()
        stateById.clear()
        tabs.addAll(BrowseTabStore.reset())
        mainTab.value = 0
        bindFilterFor(0)
        refresh()
    }

    private fun goTo(index: Int) {
        mainTab.value = index
        bindFilterFor(index)
        if (!stateFor(index).loaded) refresh()
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
            BrowseTabStore.init(context)
        }
    }
}
