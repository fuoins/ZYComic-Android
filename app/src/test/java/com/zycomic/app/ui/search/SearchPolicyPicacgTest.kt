package com.zycomic.app.ui.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SearchPolicyPicacgTest {
    @Test fun categoryTitlesAreNormalizedAndSnapshotted() {
        assertEquals(listOf("恋爱", "校园"), normalizedPicacgSearchCategories(listOf(" 恋爱 ", "", "校园", "恋爱")))
        val request = SearchRequest("test", SearchProvider.PICACG, "dd", 1, 1, listOf("恋爱"))
        assertEquals(listOf("恋爱"), request.picacgCategories)
        assertEquals(emptyList<String>(), SearchRequest("test", SearchProvider.MANWA, "dd", 1, 1).picacgCategories)
    }

    @Test fun picacgSortIsValidatedAndHasStableDefault() {
        assertEquals("dd", picacgSearchSortCode(null))
        assertEquals("ld", picacgSearchSortCode("ld"))
        assertEquals("dd", picacgSearchSortCode("unknown"))
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
