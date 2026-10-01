package com.zycomic.app.ui.search

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
}
