package com.zycomic.app.ui.browse

import com.zycomic.app.data.AllTags
import com.zycomic.app.data.dto.Manga
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

/** 分类页 ViewModel（普通类，由 remember 持有）。收集 userFlow 同步登录状态。 */
class BrowseViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- 主 Tab：0 分类 / 1 最近更新 / 2 排行 ----
    val mainTab = MutableStateFlow(0)

    // ---- 列表状态 ----
    private val _mangas = MutableStateFlow<List<Manga>>(emptyList())
    val mangas: StateFlow<List<Manga>> = _mangas.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _appending = MutableStateFlow(false)
    val appending: StateFlow<Boolean> = _appending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

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

    // ---- 登录状态 ----
    val user = UserRepository.userFlow

    private var currentPage = 1

    /** 请求序号：每次自增，用于丢弃过期请求的结果（竞态防护）。 */
    private val requestSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 当前加载任务：启动新请求前取消旧的，避免旧页面残留。 */
    private var currentJob: Job? = null

    /** 日期选项：7天 + 今天 + 前6天。 */
    val dateOptions: List<Pair<String, String>> = buildDateOptions()

    init { refresh() }

    fun selectMainTab(tab: Int) {
        // 移除 if 判断：点击当前 tab 也强制刷新
        mainTab.value = tab
        // 直接调 refresh，让 doLoad 自己设置加载状态/清空列表
        refresh()
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
        val cur = selectedTags.value.toMutableSet()
        if (!cur.add(tag)) cur.remove(tag)
        selectedTags.value = cur
        refresh()
    }

    fun clearTags() { selectedTags.value = emptySet(); refresh() }

    /** 从"更多"弹窗直接设置完整选择集合。 */
    fun setSelectedTags(tags: Set<String>) {
        selectedTags.value = tags
        refresh()
    }

    fun selectDate(date: String) { newestDate.value = date; refresh() }

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

    fun loadMore() {
        // 如果正在加载则直接 return，不 cancel 当前 job
        if (_loading.value || _appending.value || !_hasMore.value) return
        currentJob = scope.launch { doLoad(reset = false) }
    }

    private suspend fun doLoad(reset: Boolean) {
        // 自增请求序号；用于判断本次结果是否已被更新的请求取代
        val reqId = requestSeq.incrementAndGet()
        android.util.Log.d("BrowseVM", "doLoad start: tab=${mainTab.value}, page=$currentPage, reset=$reset, reqId=$reqId")
        if (reset) {
            currentPage = 1
            _hasMore.value = true
            _loading.value = true
            _error.value = null
            // 重置时先清空列表，避免旧数据残留
            _mangas.value = emptyList()
        } else {
            _appending.value = true
        }
        try {
            val target = if (reset) 30 else 15
            var lastInvoked = currentPage

            val rawLoader: suspend (Int) -> List<Manga> = { p ->
                lastInvoked = p
                when (mainTab.value) {
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
                MangaRepository.loadWithFilter(currentPage, target, rawLoader)
            } else {
                rawLoader(currentPage)
            }

            // 竞态防护：期间若有更新的请求，丢弃本次结果
            if (reqId != requestSeq.get()) return
            currentPage = lastInvoked + 1

            if (mainTab.value == 1 && reset) {
                newestNums.value = MangaRepository.getNewest(1, newestDate.value, 30).second
            }

            _mangas.value = if (reset) result else _mangas.value + result
            android.util.Log.d("BrowseVM", "doLoad done: reqId=$reqId, got ${result.size} items")
            if (result.isEmpty()) _hasMore.value = false
        } catch (e: Exception) {
            android.util.Log.w("BrowseVM", "doLoad error: ${e.message}")
            // 过期请求的异常不更新 UI
            if (reqId != requestSeq.get()) return
            if (reset) _error.value = e.message ?: "加载失败"
        } finally {
            // 仅当仍是最新请求时才复位加载态，避免旧任务覆盖新任务的状态
            if (reqId == requestSeq.get()) {
                _loading.value = false
                _appending.value = false
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
    }
}
