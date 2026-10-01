package com.zycomic.app.ui.browse

import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.MangaRepository
import com.zycomic.app.data.repository.TagRepository
import com.zycomic.app.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    // 全部标签（“更多”弹窗）
    val allTags = MutableStateFlow<List<String>>(emptyList())
    val tagsLoading = MutableStateFlow(false)

    // ---- 最近更新 ----
    val newestDate = MutableStateFlow("")       // ""=7天
    val newestNums = MutableStateFlow(0)

    // ---- 排行子 Tab：0 人气 / 1 新番 / 2 完结 ----
    val rankType = MutableStateFlow(0)

    // ---- 登录状态 ----
    val user = UserRepository.userFlow

    private var currentPage = 1

    /** 日期选项：7天 + 今天 + 前6天。 */
    val dateOptions: List<Pair<String, String>> = buildDateOptions()

    init { refresh() }

    fun selectMainTab(tab: Int) {
        if (mainTab.value == tab) return
        mainTab.value = tab
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

    fun selectDate(date: String) { newestDate.value = date; refresh() }

    /** 从详情页标签点击返回：选中该标签并切到分类 Tab。 */
    fun applyPendingTag(tag: String) {
        mainTab.value = 0
        selectedTags.value = setOf(tag)
        refresh()
    }

    fun loadTagsIfNeeded() {
        if (allTags.value.isNotEmpty() || tagsLoading.value) return
        scope.launch {
            tagsLoading.value = true
            try {
                val groups = MangaRepository.getTags()
                allTags.value = groups.flatten().flatMap { grp -> grp.list.map { it.name } }.distinct()
            } catch (_: Exception) {
            } finally {
                tagsLoading.value = false
            }
        }
    }

    fun refresh() {
        scope.launch { doLoad(reset = true) }
    }

    fun loadMore() {
        if (_loading.value || _appending.value || !_hasMore.value) return
        scope.launch { doLoad(reset = false) }
    }

    private suspend fun doLoad(reset: Boolean) {
        if (reset) {
            currentPage = 1
            _hasMore.value = true
            _loading.value = true
            _error.value = null
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
            currentPage = lastInvoked + 1

            if (mainTab.value == 1 && reset) {
                newestNums.value = MangaRepository.getNewest(1, newestDate.value, 30).second
            }

            _mangas.value = if (reset) result else _mangas.value + result
            if (result.isEmpty()) _hasMore.value = false
        } catch (e: Exception) {
            if (reset) _error.value = e.message ?: "加载失败"
        } finally {
            _loading.value = false
            _appending.value = false
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
        val QUICK_TAGS = listOf("中文", "巨乳", "中出", "口交")
    }
}
