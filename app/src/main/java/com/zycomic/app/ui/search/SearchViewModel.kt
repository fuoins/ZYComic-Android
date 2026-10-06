package com.zycomic.app.ui.search

import android.content.Context
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.repository.MangaRepository
import com.zycomic.app.data.repository.TagRepository
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

    // ---- 显示模式（持久化）----
    val displayMode = MutableStateFlow(prefs.getInt("search_display_mode", 1)) // 默认舒适网格
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
        hasSearched = true
        scope.launch { load(reset = true) }
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
            val result = if (TagRepository.isFilterEnabled())
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
        private lateinit var prefs: android.content.SharedPreferences
        fun init(context: Context) {
            prefs = context.getSharedPreferences("zycomic_display", Context.MODE_PRIVATE)
        }
    }
}
