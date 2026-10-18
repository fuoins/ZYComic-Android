package com.zycomic.app.ui.search

import android.content.Context
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.MangaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(initialProvider: SearchProvider? = null) {

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
    private val requestGeneration = SearchRequestGeneration()
    private var activeSearchJob: Job? = null

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
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
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

    // ---- 搜索来源、显示模式与 JM 排序（分别持久化）----
    val provider = MutableStateFlow(initialProvider ?: SearchProvider.fromStorage(searchPrefs.getString(KEY_PROVIDER, null)))
    val displayMode = MutableStateFlow(loadDisplayMode(provider.value))
    val jmSortCode = MutableStateFlow(searchPrefs.getString(KEY_JM_SORT, "mr") ?: "mr")
    val gridColumns = MutableStateFlow(3)

    init {
        migrateGridColumnsIfNeeded("search")
    }

    fun setDisplayMode(mode: Int) {
        displayMode.value = mode
        val selected = provider.value
        if (selected == SearchProvider.MANWA) {
            // Retain the legacy Manwa preference key for existing installations.
            prefs.edit().putInt(selected.displayModeKey, mode).apply()
        } else {
            searchPrefs.edit().putInt(selected.displayModeKey, mode).apply()
        }
    }

    fun selectProvider(selected: SearchProvider) {
        if (provider.value == selected) return
        provider.value = selected
        searchPrefs.edit().putString(KEY_PROVIDER, selected.storageValue).apply()
        displayMode.value = loadDisplayMode(selected)
        if (keyword.value.isNotBlank()) {
            doSearch()
        } else {
            clearSearchResults()
        }
    }

    fun setJmSortCode(code: String) {
        if (JmSearchSortOptions.none { it.second == code } || jmSortCode.value == code) return
        jmSortCode.value = code
        searchPrefs.edit().putString(KEY_JM_SORT, code).apply()
        if (provider.value == SearchProvider.JM && keyword.value.isNotBlank()) doSearch()
    }

    private fun loadDisplayMode(selected: SearchProvider): Int =
        if (selected == SearchProvider.MANWA) {
            prefs.getInt(selected.displayModeKey, selected.defaultDisplayMode)
        } else {
            searchPrefs.getInt(selected.displayModeKey, selected.defaultDisplayMode)
        }

    fun loadGridColumnsForOrientation(orientation: Int) {
        val k = searchGridColumnsPreferenceKey(provider.value, orientation == 2)
        val sourcePrefs = if (provider.value == SearchProvider.MANWA) prefs else searchPrefs
        gridColumns.value = sourcePrefs.getInt(k, 3)
    }

    fun setGridColumns(cols: Int, orientation: Int) {
        gridColumns.value = cols
        val key = searchGridColumnsPreferenceKey(provider.value, orientation == 2)
        val targetPrefs = if (provider.value == SearchProvider.MANWA) prefs else searchPrefs
        targetPrefs.edit().putInt(key, cols).apply()
    }

    private fun migrateGridColumnsIfNeeded(page: String) {
        val old = prefs.getInt("${page}_grid_columns", -1)
        if (old >= 0 && !prefs.contains("${page}_grid_columns_portrait")) {
            prefs.edit().putInt("${page}_grid_columns_portrait", old).remove("${page}_grid_columns").apply()
        }
    }

    private var currentPage = 1
    private var hasSearched = false

    fun updateKeyword(k: String) {
        if (keyword.value != k) _error.value = null
        keyword.value = k
    }

    fun doSearch() {
        val k = keyword.value.trim()
        if (k.isEmpty()) {
            clearSearchResults()
            return
        }
        recordHistory(k)
        hasSearched = true
        startLoad(reset = true, query = k, selectedProvider = provider.value, order = jmSortCode.value, page = 1)
    }

    /** 记一条历史：同一个词重复搜只顶到最前，不产生重复条目；超出上限丢弃最旧的。 */
    private fun recordHistory(k: String) {
        val next = (listOf(k) + _searchHistory.value.filter { it != k }).take(HISTORY_MAX)
        _searchHistory.value = next
        persistHistory(next)
    }

    fun loadMore() {
        if (!hasSearched || _loading.value || _appending.value || !_hasMore.value) return
        startLoad(
            reset = false,
            query = keyword.value.trim(),
            selectedProvider = provider.value,
            order = jmSortCode.value,
            page = currentPage,
        )
    }

    private fun startLoad(reset: Boolean, query: String, selectedProvider: SearchProvider, order: String, page: Int) {
        val generation = requestGeneration.advance()
        activeSearchJob?.cancel()
        activeSearchJob = null
        if (reset) {
            currentPage = 1
            _hasMore.value = true
            _mangas.value = emptyList()
            _error.value = null
            _loading.value = true
            _appending.value = false
        } else {
            _appending.value = true
        }
        val request = SearchRequest(query, selectedProvider, order, page, generation)
        activeSearchJob = scope.launch { load(request, reset) }
    }

    private suspend fun load(request: SearchRequest, reset: Boolean) {
        val coroutineJob = currentCoroutineContext()[Job]
        try {
            if (request.provider == SearchProvider.JM) {
                val pageResult = com.zycomic.app.reader.JmSource.browse(
                    mode = 0,
                    page = request.page,
                    query = request.query,
                    order = request.order,
                )
                if (!requestGeneration.isCurrent(request.generation)) return
                currentPage = request.page + 1
                _mangas.value = if (reset) pageResult.items else _mangas.value + pageResult.items
                _hasMore.value = pageResult.hasMore
            } else {
                val target = if (reset) 30 else 15
                var lastInvoked = request.page
                val raw: suspend (Int) -> List<Manga> = { p ->
                    lastInvoked = p
                    MangaRepository.search(request.query, p)
                }
                val result = if (MangaRepository.isAnyFilterEnabled()) {
                    MangaRepository.loadWithFilter(request.page, target, raw)
                } else {
                    raw(request.page)
                }
                if (!requestGeneration.isCurrent(request.generation)) return
                currentPage = lastInvoked + 1
                _mangas.value = if (reset) result else _mangas.value + result
                if (result.isEmpty()) _hasMore.value = false
            }
            if (requestGeneration.isCurrent(request.generation)) _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestGeneration.isCurrent(request.generation) && reset) _error.value = e.message ?: "加载失败"
        } finally {
            if (requestGeneration.isCurrent(request.generation)) {
                _loading.value = false
                _appending.value = false
                if (activeSearchJob == coroutineJob) activeSearchJob = null
            }
        }
    }

    private fun clearSearchResults() {
        requestGeneration.advance()
        activeSearchJob?.cancel()
        activeSearchJob = null
        currentPage = 1
        hasSearched = false
        _mangas.value = emptyList()
        _hasMore.value = true
        _error.value = null
        _loading.value = false
        _appending.value = false
    }

    /** Called when the screen leaves composition so in-flight work cannot outlive its owner. */
    fun dispose() {
        requestGeneration.advance()
        activeSearchJob?.cancel()
        activeSearchJob = null
        scope.cancel()
    }

    companion object {
        private const val KEY_HISTORY = "search_history"
        private const val KEY_PROVIDER = "search_provider"
        private const val KEY_JM_SORT = "jm_search_sort"
        private const val HISTORY_MAX = 20
        private lateinit var prefs: android.content.SharedPreferences
        private lateinit var searchPrefs: android.content.SharedPreferences
        fun init(context: Context) {
            prefs = context.getSharedPreferences("zycomic_display", Context.MODE_PRIVATE)
            searchPrefs = context.getSharedPreferences("zycomic_search", Context.MODE_PRIVATE)
        }
    }
}
