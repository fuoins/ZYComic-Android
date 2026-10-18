package com.zycomic.app.ui.browse

import androidx.compose.runtime.mutableStateListOf
import com.zycomic.app.data.AllTags
import com.zycomic.app.data.dto.Folder
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.FavoriteRepository
import com.zycomic.app.data.repository.MangaRepository
import com.zycomic.app.data.repository.UserRepository
import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.platform.ContentPlatformAccentPrefs
import com.zycomic.app.platform.ContentPlatformPrefs
import com.zycomic.app.platform.StartupPlatformPolicy
import com.zycomic.app.reader.JmSource
import kotlinx.coroutines.CancellationException
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

    // ---- 可持久化的共享 Tab 列表（Compose 可观察） ----
    private val initialPlatform = ContentPlatformPrefs.load("browse")
    val tabs = mutableStateListOf<BrowseTabItem>().apply { addAll(BrowseTabStore.loadAll()) }
    val currentPlatform = MutableStateFlow(initialPlatform)

    /** 当前选中 tab 的下标；旧版按平台保存的下标迁移到共享序列。 */
    val mainTab = MutableStateFlow(initialTabIndex(initialPlatform))

    private fun initialTabIndex(platform: ContentPlatform): Int {
        BrowseTabStore.loadSelectedTabOrNull()?.let { index ->
            val item = tabs.getOrNull(index)
            if (item != null && (BrowseTabPolicy.isIndependentRanking(item) || item.platformId == platform)) return index
        }
        val oldIndex = BrowseTabStore.loadSelectedTab(platform)
        val platformTabs = tabs.withIndex().filter { it.value.platformId == platform }
        return platformTabs.getOrNull(oldIndex)?.index
            ?: tabs.indexOfFirst { it.platformId == platform }.takeIf { it >= 0 }
            ?: 0
    }

    /** 单个 tab 的列表状态，按 tab id 存放。 */
    private class TabState {
        val mangas = MutableStateFlow<List<Manga>>(emptyList())
        val loading = MutableStateFlow(false)
        val appending = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)
        val hasMore = MutableStateFlow(true)
        val jmFilters: MutableStateFlow<JmBrowsePolicy.Filters>
        var jmJob: Job? = null
        var jmRequestId = 0L
        var previousJmPageFirstId: String? = null
        var currentPage = 1
        var loaded = false

        constructor(initialJmFilters: JmBrowsePolicy.Filters = JmBrowsePolicy.Filters()) {
            jmFilters = MutableStateFlow(initialJmFilters)
        }
    }

    private val stateById = HashMap<String, TabState>()
    private val sessionFilters = SessionTabFilters<FilterSnapshot>()

    private fun stateFor(page: Int): TabState {
        val item = tabs.getOrNull(page) ?: return TabState()
        val key = "${item.platformId.id}:${item.id}"
        return stateById.getOrPut(key) {
            val initial = if (item.platformId == ContentPlatform.JM) {
                val stored = BrowseTabStore.loadJmFilters(item.id) ?: JmBrowsePolicy.initialFilters(item.id)
                JmBrowsePolicy.effectiveFilters(item.id, stored).also { normalized ->
                    if (normalized != stored) BrowseTabStore.saveJmFilters(item.id, normalized)
                }
            } else {
                JmBrowsePolicy.Filters()
            }
            TabState(initial)
        }
    }

    internal fun defaultJmFiltersForTab(tab: Int): JmBrowsePolicy.Filters {
        val item = tabs.getOrNull(tab) ?: return JmBrowsePolicy.Filters()
        return JmBrowsePolicy.effectiveFilters(item.id, BrowseTabStore.loadJmFilters(item.id) ?: JmBrowsePolicy.initialFilters(item.id))
    }

    private fun saveJmFilters(tab: Int, filters: JmBrowsePolicy.Filters) {
        tabs.getOrNull(tab)
            ?.takeIf { it.platformId == ContentPlatform.JM }
            ?.let { BrowseTabStore.saveJmFilters(it.id, filters) }
    }

    private val cur: TabState get() = stateFor(mainTab.value)

    fun mangasForTab(tab: Int): StateFlow<List<Manga>> = stateFor(tab).mangas
    fun loadingForTab(tab: Int): StateFlow<Boolean> = stateFor(tab).loading
    fun appendingForTab(tab: Int): StateFlow<Boolean> = stateFor(tab).appending
    fun hasMoreForTab(tab: Int): StateFlow<Boolean> = stateFor(tab).hasMore
    fun errorForTab(tab: Int): StateFlow<String?> = stateFor(tab).error

    // ---- 当前 FILTER tab 的临时筛选；按平台和 Tab 保存，不改写预设 ----
    val curFilter = MutableStateFlow(FilterSnapshot())

    data class JmWeekChoice(val id: String, val title: String)

    private val _jmWeekIssues = MutableStateFlow<List<JmWeekChoice>>(emptyList())
    val jmWeekIssues: StateFlow<List<JmWeekChoice>> = _jmWeekIssues.asStateFlow()
    private val _jmWeekIssuesLoading = MutableStateFlow(false)
    val jmWeekIssuesLoading: StateFlow<Boolean> = _jmWeekIssuesLoading.asStateFlow()
    private val _jmWeekIssuesError = MutableStateFlow<String?>(null)
    val jmWeekIssuesError: StateFlow<String?> = _jmWeekIssuesError.asStateFlow()
    private var jmWeekIssuesLoaded = false
    private var jmWeekIssuesJob: Job? = null

    private val _jmPromoteSections = MutableStateFlow<List<JmSource.PromoteSection>>(emptyList())
    val jmPromoteSections: StateFlow<List<JmSource.PromoteSection>> = _jmPromoteSections.asStateFlow()
    private val _jmPromoteSectionsLoading = MutableStateFlow(false)
    val jmPromoteSectionsLoading: StateFlow<Boolean> = _jmPromoteSectionsLoading.asStateFlow()
    private val _jmPromoteSectionsError = MutableStateFlow<String?>(null)
    val jmPromoteSectionsError: StateFlow<String?> = _jmPromoteSectionsError.asStateFlow()
    private var jmPromoteSectionsLoaded = false
    private var jmPromoteSectionsJob: Job? = null

    internal fun jmFiltersForTab(tab: Int): StateFlow<JmBrowsePolicy.Filters> = stateFor(tab).jmFilters.asStateFlow()

    fun loadJmPromoteSections(retry: Boolean = false) {
        if (_jmPromoteSectionsLoading.value || (jmPromoteSectionsLoaded && !retry)) return
        jmPromoteSectionsJob?.cancel()
        _jmPromoteSectionsLoading.value = true
        _jmPromoteSectionsError.value = null
        jmPromoteSectionsJob = scope.launch {
            try {
                _jmPromoteSections.value = JmSource.getPromoteSections()
                jmPromoteSectionsLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _jmPromoteSectionsError.value = e.message ?: "JM 首页板块加载失败"
            } finally {
                _jmPromoteSectionsLoading.value = false
            }
        }
    }

    fun openJmPromoteSection(section: JmSource.PromoteSection, tab: Int = mainTab.value) {
        if (tab !in tabs.indices || tabs[tab].platformId != ContentPlatform.JM) return
        val filters = stateFor(tab).jmFilters.value
        val route = com.zycomic.app.reader.JmBrowseParameters.promoteRoute(
            type = section.type,
            id = section.id,
            slug = section.slug,
            page = 1,
            today = JmBrowsePolicy.today(),
        )
        val updated = when (route.path) {
            "/serialization" -> filters.copy(
                mode = JmBrowsePolicy.MODE_SERIALIZATION,
                query = "",
                serializationType = "all",
                serializationDate = JmBrowsePolicy.today(),
                promoteId = section.id,
                promoteTitle = section.title,
            )
            "/promote_list" -> filters.copy(
                mode = JmBrowsePolicy.MODE_PROMOTE_LIST,
                query = "",
                promoteId = section.id,
                promoteTitle = section.title,
            )
            "/categories/filter" -> filters.copy(
                mode = JmBrowsePolicy.MODE_CATEGORY,
                query = "",
                category = section.slug,
                order = "mr",
                promoteId = section.id,
                promoteTitle = section.title,
            )
            else -> filters.copy(
                mode = JmBrowsePolicy.MODE_UNAVAILABLE_SECTION,
                query = "",
                promoteId = section.id,
                promoteTitle = section.title,
            )
        }
        stateFor(tab).jmFilters.value = updated
        refresh(tab)
    }

    fun selectJmMode(mode: Int, tab: Int = mainTab.value) {
        if (mode !in setOf(JmBrowsePolicy.MODE_RECOMMENDATIONS, JmBrowsePolicy.MODE_CATEGORY, JmBrowsePolicy.MODE_WEEK, JmBrowsePolicy.MODE_SERIALIZATION) || tab !in tabs.indices || tabs[tab].platformId != ContentPlatform.JM) return
        if (tabs[tab].id == JmBrowsePolicy.ALL_HOT_TAB_ID && mode != JmBrowsePolicy.MODE_CATEGORY) return
        val state = stateFor(tab)
        val current = state.jmFilters.value
        if (current.mode == mode && current.query.isBlank()) return
        state.jmFilters.value = current.copy(
            mode = mode,
            query = "",
            order = if (mode == JmBrowsePolicy.MODE_CATEGORY && current.order.isBlank()) "mr" else current.order,
            weekType = if (mode == JmBrowsePolicy.MODE_WEEK && current.weekType.isBlank()) "manga" else current.weekType,
        )
        refresh(tab)
    }

    fun searchJm(query: String, tab: Int = mainTab.value) {
        val value = query.trim()
        if (value.isBlank() || tab !in tabs.indices || tabs[tab].platformId != ContentPlatform.JM || tabs[tab].id == JmBrowsePolicy.ALL_HOT_TAB_ID) return
        val current = stateFor(tab).jmFilters.value
        stateFor(tab).jmFilters.value = current.copy(mode = JmBrowsePolicy.MODE_SEARCH, query = value)
        refresh(tab)
    }

    fun updateJmFilters(tab: Int, order: String? = null, category: String? = null, weekId: String? = null, weekType: String? = null, serializationType: String? = null, serializationDate: Int? = null) {
        if (tab !in tabs.indices || tabs[tab].platformId != ContentPlatform.JM) return
        val current = stateFor(tab).jmFilters.value
        stateFor(tab).jmFilters.value = current.copy(
            order = order?.trim() ?: current.order,
            category = category?.trim() ?: current.category,
            weekId = weekId?.trim() ?: current.weekId,
            weekType = weekType?.trim() ?: current.weekType,
            serializationType = serializationType ?: current.serializationType,
            serializationDate = serializationDate ?: current.serializationDate,
        )
        refresh(tab)
    }

    fun loadJmWeekIssues(retry: Boolean = false) {
        if (_jmWeekIssuesLoading.value || (jmWeekIssuesLoaded && !retry)) return
        jmWeekIssuesJob?.cancel()
        _jmWeekIssuesLoading.value = true
        _jmWeekIssuesError.value = null
        jmWeekIssuesJob = scope.launch {
            try {
                _jmWeekIssues.value = JmSource.getWeekIssues().map { JmWeekChoice(it.id, it.title) }
                jmWeekIssuesLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _jmWeekIssuesError.value = e.message ?: "周榜期数加载失败"
            } finally {
                _jmWeekIssuesLoading.value = false
            }
        }
    }

    val allTags = MutableStateFlow<List<String>>(AllTags.LIST)

    // ---- 最近更新（SPECIAL: LATEST） ----
    val newestDate = MutableStateFlow("") // ""=7天
    val newestNums = MutableStateFlow(0)

    // ---- 排行（SPECIAL: RANKING）子类型：0 人气 / 1 新番 / 2 完结 ----
    val rankType = MutableStateFlow(0)
    private var rankTypeInitialized = false
    fun initDefaultRankType(default: Int) {
        if (!rankTypeInitialized) {
            rankType.value = default
            rankTypeInitialized = true
        }
    }
    fun applyDefaultRankType(t: Int) {
        rankTypeInitialized = true
        if (rankType.value != t) {
            rankType.value = t
            refresh()
        }
    }

    val filterExpanded = MutableStateFlow(prefs.getBoolean("browse_filter_expanded_${currentPlatform.value.id}", true))

    // ---- 显示模式：0紧凑 1舒适(默认) 2仅封面 ----（持久化）
    val displayMode = MutableStateFlow(prefs.getInt("browse_display_mode", 1))
    val gridColumns = MutableStateFlow(3)

    init {
        migrateGridColumnsIfNeeded("browse")
    }

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
        if (tabs.isNotEmpty()) mainTab.value = mainTab.value.coerceIn(0, tabs.lastIndex)
        tabs.getOrNull(mainTab.value)?.let { bindPlatform(it.platformId, allowIndependentRanking = isIndependentRanking(it)) }
        BrowseTabStore.saveSelectedTab(mainTab.value)
        bindFilterFor(mainTab.value)
        refresh()
    }

    private fun isIndependentRanking(item: BrowseTabItem): Boolean =
        BrowseTabPolicy.isIndependentRanking(item)

    private fun canSelect(item: BrowseTabItem): Boolean = isIndependentRanking(item) || StartupPlatformPolicy.isEnabled(item.platformId)

    /** 会话内按平台和固定 Tab ID 保留临时值，重启后重新读取持久默认值。 */
    private fun bindFilterFor(page: Int) {
        val item = tabs.getOrNull(page) ?: return
        if (item.kind == TabKind.FILTER) {
            curFilter.value = sessionFilters.get("${item.platformId.id}:${item.id}") { item.filter?.copy() ?: FilterSnapshot() }
        }
    }

    fun selectMainTab(tab: Int) {
        if (tabs.isEmpty() || tab !in tabs.indices) return
        val item = tabs[tab]
        if (!canSelect(item)) return
        if (mainTab.value == tab && currentPlatform.value == item.platformId) return
        val platformChanged = currentPlatform.value != item.platformId
        bindPlatform(item.platformId, allowIndependentRanking = isIndependentRanking(item))
        if (platformChanged) {
            selectionMode.value = false
            selectedIds.value = emptySet()
        }
        mainTab.value = tab
        BrowseTabStore.saveSelectedTab(tab)
        bindFilterFor(tab)
        if (!stateFor(tab).loaded) refresh()
    }

    private fun bindPlatform(platform: ContentPlatform, allowIndependentRanking: Boolean = false) {
        if (!allowIndependentRanking && !StartupPlatformPolicy.isEnabled(platform)) return
        if (currentPlatform.value == platform) return
        currentJob?.cancel()
        requestSeq.incrementAndGet()
        val previous = currentPlatform.value
        if (previous == ContentPlatform.MANWA) {
            tabs.indices.filter { tabs[it].platformId == previous }.forEach { tab ->
                stateFor(tab).also {
                    it.loading.value = false
                    it.appending.value = false
                }
            }
        }
        prefs.edit()
            .putBoolean("browse_filter_expanded_${previous.id}", filterExpanded.value)
            .apply()
        currentPlatform.value = platform
        if (!allowIndependentRanking || StartupPlatformPolicy.isEnabled(platform)) ContentPlatformPrefs.save("browse", platform)
        filterExpanded.value = prefs.getBoolean("browse_filter_expanded_${platform.id}", true)
    }

    fun selectRankType(type: Int) {
        if (rankType.value == type) return
        rankType.value = type
        refresh()
    }

    // ---- 「重新筛选」：仅改当前 tab 的会话临时筛选，不写回预设 ----
    private fun updateCurrentFilter(snapshot: FilterSnapshot) {
        curFilter.value = snapshot
        tabs.getOrNull(mainTab.value)?.let { item ->
            sessionFilters.set("${item.platformId.id}:${item.id}", snapshot)
        }
    }

    fun selectGender(v: Int) {
        updateCurrentFilter(curFilter.value.copy(gender = v))
        refresh()
    }
    fun selectArea(v: Int) {
        updateCurrentFilter(curFilter.value.copy(area = v))
        refresh()
    }
    fun selectEnd(v: Int) {
        updateCurrentFilter(curFilter.value.copy(end = v))
        refresh()
    }
    fun selectSt(v: Int) {
        updateCurrentFilter(curFilter.value.copy(st = v))
        refresh()
    }
    fun toggleFilterExpanded() {
        filterExpanded.value = !filterExpanded.value
        prefs.edit().putBoolean("browse_filter_expanded_${currentPlatform.value.id}", filterExpanded.value).apply()
    }

    fun toggleTag(tag: String) {
        val f = curFilter.value
        val set = f.tags.toMutableSet()
        if (!set.add(tag)) set.remove(tag)
        updateCurrentFilter(f.copy(tags = set.toList()))
        refresh()
    }

    fun clearTags() {
        updateCurrentFilter(curFilter.value.copy(tags = emptyList()))
        refresh()
    }

    fun setSelectedTags(tags: Set<String>) {
        updateCurrentFilter(curFilter.value.copy(tags = tags.toList()))
        refresh()
    }

    /** 用一份完整快照替换当前 tab 的会话临时筛选并刷新（「重新筛选」面板用）。 */
    fun applyTempFilter(snapshot: FilterSnapshot) {
        updateCurrentFilter(snapshot)
        refresh()
    }

    fun selectDate(date: String) {
        newestDate.value = date
        refresh()
    }

    // ---- 多选 ----
    fun enterSelection() {
        if (currentPlatform.value != ContentPlatform.MANWA || tabs.isEmpty()) return
        selectionMode.value = true
        selectedIds.value = emptySet()
    }
    fun exitSelection() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }
    fun toggleSelect(id: String) {
        if (currentPlatform.value != ContentPlatform.MANWA) return
        val curIds = selectedIds.value.toMutableSet()
        if (!curIds.add(id)) curIds.remove(id)
        selectedIds.value = curIds
    }
    fun selectAll() {
        if (currentPlatform.value != ContentPlatform.MANWA) return
        selectedIds.value = cur.mangas.value.map { it.id }.toSet()
    }
    fun invertSelection() {
        if (currentPlatform.value != ContentPlatform.MANWA) return
        val all = cur.mangas.value.map { it.id }.toSet()
        selectedIds.value = all - selectedIds.value
    }

    // ---- 收藏夹 ----
    fun loadFolders() {
        if (currentPlatform.value != ContentPlatform.MANWA) return
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

    private suspend fun refreshFavoriteCount() {
        try {
            UserRepository.refreshUserInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 收藏已成功；用户信息刷新失败不应改变收藏操作结果。
        }
    }

    fun addToAllFolders(onDone: (Boolean, String) -> Unit) {
        if (currentPlatform.value != ContentPlatform.MANWA) {
            onDone(false, "JM 批量收藏尚未支持：缺少登录态与收藏写接口样例")
            return
        }
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) {
            onDone(false, "请先选择漫画")
            return
        }
        scope.launch {
            try {
                ids.forEach { id -> FavoriteRepository.addFavorite(id.toIntOrNull() ?: 0, 0) }
                refreshFavoriteCount()
                onDone(true, "已添加 ${ids.size} 本到全部收藏夹")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(false, e.message ?: "收藏失败")
            }
        }
    }

    fun addToFolder(folderId: String, onDone: (Boolean, String) -> Unit) {
        if (currentPlatform.value != ContentPlatform.MANWA) {
            onDone(false, "JM 批量收藏尚未支持：缺少登录态与收藏夹写接口样例")
            return
        }
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) {
            onDone(false, "请先选择漫画")
            return
        }
        scope.launch {
            try {
                ids.forEach { id -> FavoriteRepository.addFavorite(id.toIntOrNull() ?: 0, folderId.toIntOrNull() ?: 0) }
                refreshFavoriteCount()
                onDone(true, "已添加 ${ids.size} 本到收藏夹")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDone(false, e.message ?: "收藏失败")
            }
        }
    }

    /** 从详情页标签点击返回：定位到第一个 FILTER tab，并把该标签作为当次临时筛选。 */
    fun applyPendingTag(tag: String) {
        if (tabs.isEmpty() || !StartupPlatformPolicy.isEnabled(ContentPlatform.MANWA)) return
        val idx = tabs.indexOfFirst { it.kind == TabKind.FILTER && it.platformId == ContentPlatform.MANWA }
        if (idx < 0) return
        bindPlatform(ContentPlatform.MANWA)
        mainTab.value = idx
        BrowseTabStore.saveSelectedTab(idx)
        bindFilterFor(idx)
        updateCurrentFilter((tabs[idx].filter ?: FilterSnapshot()).copy(tags = listOf(tag)))
        refresh()
    }

    fun refresh(tab: Int = mainTab.value) {
        if (tabs.isEmpty() || tab !in tabs.indices) return
        if (isIndependentRanking(tabs[tab])) return
        if (!StartupPlatformPolicy.isEnabled(tabs[tab].platformId)) return
        if (tabs[tab].platformId == ContentPlatform.JM) {
            val ts = stateFor(tab)
            ts.jmJob?.cancel()
            ts.loading.value = false
            ts.appending.value = false
            ts.jmJob = scope.launch { doLoadJm(reset = true, tab = tab) }
        } else {
            currentJob?.cancel()
            currentJob = scope.launch { doLoad(reset = true, tab = tab) }
        }
    }

    fun loadMore(tab: Int) {
        if (tab !in tabs.indices) return
        if (isIndependentRanking(tabs[tab])) return
        if (!StartupPlatformPolicy.isEnabled(tabs[tab].platformId)) return
        val ts = stateFor(tab)
        if (ts.loading.value || ts.appending.value || !ts.hasMore.value) return
        if (tabs[tab].platformId == ContentPlatform.JM) {
            ts.jmJob?.cancel()
            ts.jmJob = scope.launch { doLoadJm(reset = false, tab = tab) }
        } else {
            currentJob = scope.launch { doLoad(reset = false, tab = tab) }
        }
    }

    private suspend fun doLoad(reset: Boolean, tab: Int = mainTab.value) {
        if (tab !in tabs.indices) return
        if (isIndependentRanking(tabs[tab])) return
        if (!StartupPlatformPolicy.isEnabled(tabs[tab].platformId)) return
        if (tabs[tab].platformId == ContentPlatform.JM) {
            doLoadJm(reset, tab)
            return
        }
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
                        val f = sessionFilters.get("${item.platformId.id}:${item.id}") { item.filter ?: FilterSnapshot() }
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

            val result = if (MangaRepository.isAnyFilterEnabled()) {
                MangaRepository.loadWithFilter(ts.currentPage, target, rawLoader)
            } else {
                rawLoader(ts.currentPage)
            }

            if (reqId != requestSeq.get()) return
            if (!canSelect(item)) return
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

    private suspend fun doLoadJm(reset: Boolean, tab: Int) {
        if (tab !in tabs.indices || !StartupPlatformPolicy.isEnabled(tabs[tab].platformId)) return
        val ts = stateFor(tab)
        val reqId = ++ts.jmRequestId
        if (reset) {
            ts.currentPage = 1
            ts.previousJmPageFirstId = null
            ts.hasMore.value = true
            ts.loading.value = true
            ts.error.value = null
            ts.mangas.value = emptyList()
        } else {
            ts.appending.value = true
        }
        try {
            var filters = JmBrowsePolicy.effectiveFilters(tabs[tab].id, ts.jmFilters.value)
            if (filters.mode == JmBrowsePolicy.MODE_WEEK && filters.weekId.isBlank()) {
                val issues = JmSource.getWeekIssues()
                if (reqId != ts.jmRequestId) return
                _jmWeekIssues.value = issues.map { JmWeekChoice(it.id, it.title) }
                jmWeekIssuesLoaded = true
                val latest = issues.firstOrNull()?.id ?: error("每周必看暂无可用期数")
                filters = filters.copy(weekId = latest, weekType = filters.weekType.ifBlank { "manga" })
                ts.jmFilters.value = filters
            }
            val previousFirstId = ts.previousJmPageFirstId
            val result = JmSource.browse(
                mode = filters.mode,
                page = ts.currentPage,
                query = filters.query,
                order = filters.order,
                category = JmBrowsePolicy.apiCategory(filters.category),
                weekId = filters.weekId,
                weekType = filters.weekType,
                previousFirstId = previousFirstId,
                serializationType = filters.serializationType,
                serializationDate = filters.serializationDate,
                promoteId = filters.promoteId,
            )
            if (reqId != ts.jmRequestId) return
            if (!StartupPlatformPolicy.isEnabled(tabs[tab].platformId)) return
            if (JmBrowsePolicy.shouldStopPaging(result.items, previousFirstId)) {
                ts.loaded = true
                ts.hasMore.value = false
                return
            }
            ts.currentPage += 1
            val merged = JmBrowsePolicy.mergeDistinct(ts.mangas.value, result.items, reset)
            val addedItems = merged.size > ts.mangas.value.size
            ts.mangas.value = merged
            ts.previousJmPageFirstId = result.items.firstOrNull()?.id
            ts.loaded = true
            ts.hasMore.value = result.hasMore && addedItems
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reqId != ts.jmRequestId) return
            if (reset || ts.mangas.value.isEmpty()) ts.error.value = e.message ?: "JM 加载失败"
            ts.loaded = true
            ts.hasMore.value = false
        } finally {
            if (reqId == ts.jmRequestId) {
                ts.loading.value = false
                ts.appending.value = false
            }
        }
    }

    // ==================== Tab 管理（预设持久化） ====================

    /**
     * 最新更新/gay排行均以 builtin=false 的可添加预设存在，3 个内置热门筛选 tab 也可删（靠恢复默认找回），
     * 故当前没有受保护 tab。
     */
    fun isProtected(item: BrowseTabItem): Boolean = false

    /** Persists one complete drag draft and restores selection by stable platform-qualified identity. */
    fun reorderTabs(draft: List<BrowseTabItem>): Boolean {
        val result = BrowseTabPolicy.reorder(tabs.toList(), draft, mainTab.value) ?: return false
        tabs.clear()
        tabs.addAll(result.tabs)
        mainTab.value = result.selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        BrowseTabStore.saveAll(tabs.toList(), mainTab.value)
        return true
    }

    fun isNameTaken(
        name: String,
        ignoreId: String? = null,
        platform: ContentPlatform = currentPlatform.value,
    ): Boolean {
        val n = name.trim()
        val ignoredPlatform = tabs.firstOrNull { it.id == ignoreId }?.platformId ?: platform
        return tabs.any { it.name == n && !(it.id == ignoreId && it.platformId == ignoredPlatform) }
    }

    private fun persist() = BrowseTabStore.saveAll(tabs.toList())

    private fun addNewTab(item: BrowseTabItem): Int {
        val (next, index) = BrowseTabPolicy.insertNewTab(tabs.toList(), item)
        tabs.clear()
        tabs.addAll(next)
        persist()
        goTo(index)
        return index
    }

    private fun addFixedJmTab(item: BrowseTabItem): Boolean {
        val addition = BrowseTabPolicy.addFixedJmTab(tabs.toList(), item) ?: return false
        tabs.clear()
        tabs.addAll(addition.first)
        persist()
        goTo(addition.second)
        return true
    }

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
        val i = tabs.indexOfFirst { it.id == id && it.platformId == ContentPlatform.MANWA }
        if (i < 0 || tabs[i].kind != TabKind.FILTER) return
        tabs[i] = tabs[i].copy(filter = snapshot)
        persist()
        sessionFilters.set("${tabs[i].platformId.id}:$id", snapshot.copy())
        stateFor(i).loaded = false
        if (mainTab.value == i) {
            updateCurrentFilter(snapshot.copy())
            refresh()
        }
    }

    /** Save a JM tab's own category/order state, never converting it to a Manwa FilterSnapshot. */
    internal fun updateTabDefaultJmFilters(id: String, filters: JmBrowsePolicy.Filters) {
        val i = tabs.indexOfFirst { it.id == id && it.platformId == ContentPlatform.JM }
        if (i < 0 || tabs[i].kind != TabKind.FILTER) return
        stateFor(i).jmFilters.value = filters
        saveJmFilters(i, filters)
        stateFor(i).loaded = false
        if (mainTab.value == i) refresh(i)
    }

    fun deleteTab(id: String) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0 || isProtected(tabs[i])) return
        val deletedPlatform = tabs[i].platformId
        tabs.removeAt(i)
        stateById.remove("${deletedPlatform.id}:$id")
        sessionFilters.remove("${deletedPlatform.id}:$id")
        if (deletedPlatform == ContentPlatform.JM) BrowseTabStore.deleteJmFilters(id)
        if (tabs.isEmpty()) tabs.addAll(BrowseTabStore.defaultTabs(ContentPlatform.MANWA))
        persist()
        if (tabs.isEmpty()) {
            mainTab.value = 0
            BrowseTabStore.saveSelectedTab(0)
            return
        }
        val target = when {
            i < mainTab.value -> mainTab.value - 1
            i == mainTab.value -> 0
            else -> mainTab.value
        }.coerceIn(0, tabs.lastIndex)
        bindPlatform(tabs[target].platformId)
        mainTab.value = target
        BrowseTabStore.saveSelectedTab(target)
        bindFilterFor(target)
        if (!stateFor(target).loaded) refresh()
    }

    internal fun addFilterTab(
        name: String,
        snapshot: FilterSnapshot?,
        platform: ContentPlatform = currentPlatform.value,
        jmFilters: JmBrowsePolicy.Filters = JmBrowsePolicy.Filters(mode = JmBrowsePolicy.MODE_CATEGORY, order = "mr"),
    ): Boolean {
        if (!StartupPlatformPolicy.isEnabled(platform)) return false
        val n = name.trim()
        if (n.isEmpty() || isNameTaken(n, platform = platform)) return false
        val item = BrowseTabItem(
            id = "custom_${platform.id}_${System.currentTimeMillis()}",
            name = n,
            kind = TabKind.FILTER,
            builtin = false,
            filter = snapshot.takeIf { platform == ContentPlatform.MANWA },
            platformId = platform,
        )
        if (platform == ContentPlatform.JM) BrowseTabStore.saveJmFilters(item.id, jmFilters)
        addNewTab(item)
        return true
    }

    fun canAddLatest(): Boolean = tabs.none { it.pageType == BrowsePageType.LATEST }

    /** 添加「最新更新」特殊 tab，固定 id，仅可添加一次。 */
    fun addLatestTab(): Boolean {
        if (!canAddLatest()) return false
        tabs.add(
            BrowseTabItem(
                id = BrowseTabStore.ID_LATEST_ADD,
                name = BrowseTabStore.LATEST_NAME,
                kind = TabKind.SPECIAL,
                builtin = false,
                pageType = BrowsePageType.LATEST,
            ),
        )
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

    fun canAddYuzong(): Boolean = tabs.none { it.pageType == BrowsePageType.YUZONG_RANK }

    /** 添加「宇总排行」特殊 tab，固定 id，仅可添加一次。 */
    fun addYuzongTab(): Boolean {
        if (!canAddYuzong()) return false
        tabs.add(
            BrowseTabItem(
                id = BrowseTabStore.ID_YUZONG,
                name = BrowseTabStore.YUZONG_NAME,
                kind = TabKind.SPECIAL,
                builtin = false,
                pageType = BrowsePageType.YUZONG_RANK,
            ),
        )
        persist()
        goTo(tabs.lastIndex)
        return true
    }

    fun canAddJmHome(): Boolean = BrowseTabPolicy.canAddFixedJmTab(tabs, BrowseTabStore.ID_JM_HOME)

    /** Adds the fixed-ID JM homepage tab once; deleting it makes it available to add again. */
    fun addJmHomeTab(): Boolean = addFixedJmTab(BrowseTabStore.jmHomeTab())

    fun canAddJmAllHot(): Boolean = BrowseTabPolicy.canAddFixedJmTab(tabs, JmBrowsePolicy.ALL_HOT_TAB_ID)

    /** Adds the fixed-ID JM all-hot tab once; deleting it makes it available to add again. */
    fun addJmAllHotTab(): Boolean = addFixedJmTab(BrowseTabStore.jmAllHotTab())

    fun resetToDefaults() {
        tabs.clear()
        stateById.clear()
        sessionFilters.clear()
        tabs.addAll(BrowseTabStore.resetAll())
        val defaultPlatform = StartupPlatformPolicy.resolve(null)
        val defaultTab = tabs.indexOfFirst { it.platformId == defaultPlatform }.coerceAtLeast(0)
        bindPlatform(defaultPlatform)
        mainTab.value = defaultTab
        BrowseTabStore.saveSelectedTab(defaultTab)
        bindFilterFor(defaultTab)
        refresh()
    }

    private fun goTo(index: Int) {
        val item = tabs.getOrNull(index) ?: return
        if (!canSelect(item)) return
        bindPlatform(item.platformId, allowIndependentRanking = isIndependentRanking(item))
        mainTab.value = index
        BrowseTabStore.saveSelectedTab(index)
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
        val GENDERS = listOf(-1 to "全部", 2 to "一般向", 1 to "禁漫向", 0 to "BL向", 3 to "TL向", 4 to "GL向")
        val AREAS = listOf(0 to "全部", 2 to "韩国", 3 to "日漫", 4 to "国漫", 5 to "台漫", 6 to "其他", 1 to "未分类")
        val ENDS = listOf(0 to "全部", 2 to "连载", 1 to "完结")
        val STS = listOf(2 to "收藏", 0 to "最新", 1 to "最旧", 3 to "新漫")

        private lateinit var prefs: android.content.SharedPreferences
        fun init(context: android.content.Context) {
            ContentPlatformPrefs.init(context)
            ContentPlatformAccentPrefs.init(context)
            prefs = context.getSharedPreferences("zycomic_display", android.content.Context.MODE_PRIVATE)
            BrowseTabStore.init(context)
        }
    }
}
