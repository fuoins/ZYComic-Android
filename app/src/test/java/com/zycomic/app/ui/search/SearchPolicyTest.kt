package com.zycomic.app.ui.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SearchPolicyTest {
    @Test
    fun `provider defaults and persisted values are stable`() {
        assertEquals(SearchProvider.JM, SearchProvider.fromStorage(null))
        assertEquals(SearchProvider.JM, SearchProvider.fromStorage("unknown"))
        assertEquals(SearchProvider.MANWA.defaultDisplayMode, 3)
        assertEquals(SearchProvider.JM.defaultDisplayMode, 1)
        assertEquals(SearchProvider.JM, SearchProvider.fromStorage("jm"))
        assertEquals(false, SearchProvider.MANWA.usesJmCoverImageLoader)
        assertEquals(true, SearchProvider.JM.usesJmCoverImageLoader)
        assertEquals("search_grid_columns_portrait", searchGridColumnsPreferenceKey(SearchProvider.MANWA, false))
        assertEquals("jm_search_grid_columns_portrait", searchGridColumnsPreferenceKey(SearchProvider.JM, false))
    }

    @Test
    fun `disabled remembered provider falls back only for this active resolution`() {
        val remembered = SearchProvider.JM
        assertEquals(SearchProvider.MANWA, resolveSearchProvider(remembered, manwaEnabled = true, jmEnabled = false))
        assertEquals(SearchProvider.JM, remembered)
        assertEquals(SearchProvider.JM, resolveSearchProvider(remembered, manwaEnabled = true, jmEnabled = true))
    }

    @Test
    fun `detail query without an explicit provider uses the current saved Search choice`() {
        assertEquals(
            SearchProvider.MANWA,
            initialSearchProvider(
                explicitProvider = null,
                rememberedProvider = SearchProvider.MANWA,
                manwaEnabled = true,
                jmEnabled = true,
            ),
        )
        assertEquals(
            SearchProvider.JM,
            initialSearchProvider(
                explicitProvider = null,
                rememberedProvider = SearchProvider.JM,
                manwaEnabled = true,
                jmEnabled = true,
            ),
        )
    }

    @Test
    fun `JM only defaults missing sort and preserves remembered choices`() {
        assertEquals("tf", jmSearchSortCode(null))
        assertEquals("tf", jmSearchSortCode(""))
        JmSearchSortOptions.forEach { (_, code) -> assertEquals(code, jmSearchSortCode(code)) }
    }

    @Test
    fun `JM sorting uses the seven verified API codes`() {
        assertEquals(
            listOf("mr", "mv", "mv_m", "mv_w", "mv_t", "mp", "tf"),
            JmSearchSortOptions.map { it.second },
        )
        assertEquals(JM_DEFAULT_SEARCH_SORT_CODE, "tf")
        assertEquals("最多喜欢", JmSearchSortOptions.first { it.second == JM_DEFAULT_SEARCH_SORT_CODE }.first)
    }

    @Test
    fun `JM search result IDs route through JM detail while Manwa IDs remain unchanged`() {
        assertEquals("jm:12345", searchDetailId(SearchProvider.JM, "12345"))
        assertEquals("jm:12345", searchDetailId(SearchProvider.JM, "jm:12345"))
        assertEquals("12345", searchDetailId(SearchProvider.MANWA, "12345"))
    }

    @Test
    fun `superseded request generation cannot publish result or clear current loading`() {
        val gate = SearchRequestGeneration()
        val oldGeneration = gate.advance()
        val newGeneration = gate.advance()

        assertEquals(false, gate.isCurrent(oldGeneration))
        assertEquals(true, gate.isCurrent(newGeneration))
    }
}
