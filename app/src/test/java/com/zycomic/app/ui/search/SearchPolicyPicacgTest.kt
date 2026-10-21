package com.zycomic.app.ui.search

import com.zycomic.app.ui.picacg.PicacgThemes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SearchPolicyPicacgTest {
    @Test fun picacgSearchThemesAreStaticAndUnique() {
        assertEquals(40, PicacgThemes.titles.size)
        assertEquals(40, PicacgThemes.titles.distinct().size)
    }

    @Test fun categoryTitlesAreNormalizedAndSnapshotted() {
        assertEquals(listOf("恋爱", "校园"), normalizedPicacgSearchCategories(listOf(" 恋爱 ", "", "校园", "恋爱")))
        val request = SearchRequest("test", SearchProvider.PICACG, "dd", 1, 1, listOf("恋爱"))
        assertEquals(listOf("恋爱"), request.picacgCategories)
        assertEquals(emptyList<String>(), SearchRequest("test", SearchProvider.MANWA, "dd", 1, 1).picacgCategories)
    }

    @Test fun picacgSortIsValidatedAndHasStableDefault() {
        assertEquals("ld", PICACG_DEFAULT_SEARCH_SORT_CODE)
        assertEquals("ld", picacgSearchSortCode(null))
        assertEquals("ld", picacgSearchSortCode("ld"))
        assertEquals("ld", picacgSearchSortCode("unknown"))
        assertEquals(
            listOf("最新" to "dd", "最旧" to "da", "最多喜欢" to "ld", "最多浏览" to "vd"),
            PicacgSearchSortOptions,
        )
    }

    @Test fun detailIdsAreNamespacedByProvider() {
        assertEquals("picacg:42", searchDetailId(SearchProvider.PICACG, "42"))
        assertEquals("picacg:42", searchDetailId(SearchProvider.PICACG, "picacg:42"))
        assertEquals("jm:42", searchDetailId(SearchProvider.JM, "42"))
    }

    @Test fun providerResolutionIncludesPicacgAsIndependentChoice() {
        assertSame(SearchProvider.PICACG, resolveSearchProvider(SearchProvider.PICACG, true, true, true))
        assertSame(SearchProvider.JM, resolveSearchProvider(SearchProvider.PICACG, true, true, false))
    }
}
