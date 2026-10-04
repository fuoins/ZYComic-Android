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

    // ---- 显示模式（持久化）----
    val displayMode = MutableStateFlow(prefs.getInt("search_display_mode", 1)) // 默认舒适网格
    val gridColumns = MutableStateFlow(prefs.getInt("search_grid_columns", 3))  // 默认每行3

    fun setDisplayMode(mode: Int) {
        displayMode.value = mode
        prefs.edit().putInt("search_display_mode", mode).apply()
    }

    fun setGridColumns(cols: Int) {
        gridColumns.value = cols
        prefs.edit().putInt("search_grid_columns", cols).apply()
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
