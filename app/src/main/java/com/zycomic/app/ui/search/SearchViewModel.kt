package com.zycomic.app.ui.search

import android.content.Context
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.MangaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val keyword = MutableStateFlow("")

    private val _mangas = MutableStateFlow<List<Manga>>(emptyList())
    val mangas: StateFlow<List<Manga>> = _mangas.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _appending = MutableStateFlow(false)
    val appending: StateFlow<Boolean> = _appending.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ---- 搜索历史（持久化：最新在前、去重、有上限）----
    private val _searchHistory = MutableStateFlow(loadHistoryFromPrefs())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    /** 删除模式：点垃圾桶后每条历史右侧露出 ❌，逐条删；再点垃圾桶退出。 */
    val historyDeleting = MutableStateFlow(false)

    /** 用历史词直接搜索（点历史条目）。 */
    fun searchHistoryItem(k: String) {
        keyword.value = k
        doSearch()
    }

    fun removeHistoryItem(k: String) {
        val next = _searchHistory.value.filter { it != k }
        _searchHistory.value = next
        persistHistory(next)
    }

    fun clearHistory() {
        _searchHistory.value = emptyList()
        persistHistory(emptyList())
    }

    private fun loadHistoryFromPrefs(): List<String> = try {
        val raw = prefs.getString(KEY_HISTORY, null)
        if (raw.isNullOrBlank()) emptyList() else {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun persistHistory(list: List<String>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    // ---- 显示模式（持久化）----
    /** 显示模式：0 紧凑网格 / 1 舒适网格 / 2 仅封面网格 / 3 列表（默认，对齐 manwa 搜索观感） */
    val displayMode = MutableStateFlow(prefs.getInt("search_display_mode", 3))
    val gridColumns = MutableStateFlow(3)

    init { migrateGridColumnsIfNeeded("search") }

    fun setDisplayMode(mode: Int) {
        displayMode.value = mode
        prefs.edit().putInt("search_display_mode", mode).apply()
    }

    fun loadGridColumnsForOrientation(orientation: Int) {
        val k = if (orientation == 2) "search_grid_columns_landscape" else "search_grid_columns_portrait"
        gridColumns.value = prefs.getInt(k, 3)
    }

    fun setGridColumns(cols: Int, orientation: Int) {
        gridColumns.value = cols
        val k = if (orientation == 2) "search_grid_columns_landscape" else "search_grid_columns_portrait"
        prefs.edit().putInt(k, cols).apply()
    }

    private fun migrateGridColumnsIfNeeded(page: String) {
        val old = prefs.getInt("${page}_grid_columns", -1)
        if (old >= 0 && !prefs.contains("${page}_grid_columns_portrait")) {
            prefs.edit().putInt("${page}_grid_columns_portrait", old).remove("${page}_grid_columns").apply()
        }
    }

    private var currentPage = 1
    private var hasSearched = false

    fun updateKeyword(k: String) { keyword.value = k }

    fun doSearch() {
        val k = keyword.value.trim()
        if (k.isEmpty()) return
        recordHistory(k)
        hasSearched = true
        scope.launch { load(reset = true) }
    }

    /** 记一条历史：同一个词重复搜只顶到最前，不产生重复条目；超出上限丢弃最旧的。 */
    private fun recordHistory(k: String) {
        val next = (listOf(k) + _searchHistory.value.filter { it != k }).take(HISTORY_MAX)
        _searchHistory.value = next
        persistHistory(next)
    }

    fun loadMore() {
        if (!hasSearched || _loading.value || _appending.value || !_hasMore.value) return
        scope.launch { load(reset = false) }
    }

    private suspend fun load(reset: Boolean) {
        if (reset) {
            currentPage = 1
            _hasMore.value = true
            _loading.value = true
        } else {
            _appending.value = true
        }
        try {
            val target = if (reset) 30 else 15
            var lastInvoked = currentPage
            val raw: suspend (Int) -> List<Manga> = { p ->
                lastInvoked = p
                MangaRepository.search(keyword.value.trim(), p)
            }
            val result = if (MangaRepository.isAnyFilterEnabled())
                MangaRepository.loadWithFilter(currentPage, target, raw)
            else raw(currentPage)
            currentPage = lastInvoked + 1
            _mangas.value = if (reset) result else _mangas.value + result
            if (result.isEmpty()) _hasMore.value = false
            _error.value = null
        } catch (e: Exception) {
            if (reset) _error.value = e.message ?: "加载失败"
        } finally {
            _loading.value = false
            _appending.value = false
        }
    }

    companion object {
        private const val KEY_HISTORY = "search_history"
        private const val HISTORY_MAX = 20
        private lateinit var prefs: android.content.SharedPreferences
        fun init(context: Context) {
            prefs = context.getSharedPreferences("zycomic_display", Context.MODE_PRIVATE)
        }
    }
}
