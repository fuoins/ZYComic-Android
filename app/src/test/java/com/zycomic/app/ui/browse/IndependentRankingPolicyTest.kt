package com.zycomic.app.ui.browse

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndependentRankingPolicyTest {
    private val ranking = BrowseTabItem(
        id = BrowseTabStore.ID_YUZONG,
        name = "宇总排行",
        kind = TabKind.SPECIAL,
        pageType = BrowsePageType.YUZONG_RANK,
    )

    @Test
    fun `only the fixed ranking identity bypasses source gating`() {
        assertTrue(BrowseTabPolicy.isIndependentRanking(ranking))
        assertTrue(BrowseTabPolicy.isIndependentRanking(ranking.copy(name = "自定义榜名")))
        assertFalse(BrowseTabPolicy.isIndependentRanking(ranking.copy(id = "other")))
        assertFalse(BrowseTabPolicy.isIndependentRanking(ranking.copy(kind = TabKind.FILTER)))
        assertFalse(BrowseTabPolicy.isIndependentRanking(ranking.copy(pageType = BrowsePageType.RANKING)))
        BrowseTabStore.defaultTabs().filterNot { it.id == ranking.id }.forEach {
            assertFalse(BrowseTabPolicy.isIndependentRanking(it))
        }
    }
}
