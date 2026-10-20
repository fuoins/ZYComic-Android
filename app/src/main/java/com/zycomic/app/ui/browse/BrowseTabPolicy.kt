package com.zycomic.app.ui.browse

import com.zycomic.app.platform.ContentPlatform

/** Pure policies for shared browse-tab identity, insertion, ordering and legacy labels. */
internal object BrowseTabPolicy {
    data class Identity(val platformId: ContentPlatform, val id: String)

    data class ReorderResult(
        val tabs: List<BrowseTabItem>,
        val selectedIndex: Int,
    )

    fun identity(tab: BrowseTabItem) = Identity(tab.platformId, tab.id)

    /** Only the canonical cross-platform ranking bypasses a disabled Manwa source. */
    fun isIndependentRanking(tab: BrowseTabItem): Boolean =
        tab.id == BrowseTabStore.ID_YUZONG && tab.kind == TabKind.SPECIAL && tab.pageType == BrowsePageType.YUZONG_RANK

    /** Rejects drafts that add, remove, duplicate or change any platform-qualified tab identity. */
    fun reorder(
        current: List<BrowseTabItem>,
        draft: List<BrowseTabItem>,
        selectedIndex: Int,
    ): ReorderResult? {
        val currentIds = current.map(::identity)
        val draftIds = draft.map(::identity)
        if (currentIds.size != currentIds.toSet().size || draftIds.size != draftIds.toSet().size) return null
        if (currentIds.toSet() != draftIds.toSet()) return null

        val selectedIdentity = current.getOrNull(selectedIndex)?.let(::identity)
        val nextSelectedIndex = selectedIdentity?.let { selected -> draft.indexOfFirst { identity(it) == selected } } ?: 0
        val currentById = current.associateBy(::identity)
        return ReorderResult(draftIds.map { currentById.getValue(it) }, nextSelectedIndex.coerceAtLeast(0))
    }

    /** New platform tabs are inserted at the default Manwa → Picacg → JM boundary without rearranging existing entries. */
    fun insertNewTab(current: List<BrowseTabItem>, item: BrowseTabItem): Pair<List<BrowseTabItem>, Int> {
        val result = current.toMutableList()
        val index = if (item.platformId == ContentPlatform.MANWA) {
            result.indexOfFirst { it.platformId != ContentPlatform.MANWA }.takeIf { it >= 0 } ?: result.size
        } else if (item.platformId == ContentPlatform.PICACG) {
            val lastManwa = result.indexOfLast { it.platformId == ContentPlatform.MANWA }
            if (lastManwa >= 0) lastManwa + 1 else result.indexOfFirst { it.platformId == ContentPlatform.JM }.takeIf { it >= 0 } ?: result.size
        } else {
            result.size
        }
        result.add(index, item)
        return result to index
    }

    fun canAddFixedJmTab(tabs: List<BrowseTabItem>, id: String): Boolean =
        tabs.none { it.platformId == ContentPlatform.JM && it.id == id }

    /** Adds only a missing fixed JM tab and returns the new list and insertion index. */
    fun addFixedJmTab(tabs: List<BrowseTabItem>, item: BrowseTabItem): Pair<List<BrowseTabItem>, Int>? {
        if (item.platformId != ContentPlatform.JM || !canAddFixedJmTab(tabs, item.id)) return null
        return insertNewTab(tabs, item)
    }

    /** Inserts missing defaults before the first Manwa item without reordering existing mixed lists. */
    fun addMissingJmDefaults(
        tabs: List<BrowseTabItem>,
        defaults: List<BrowseTabItem>,
    ): List<BrowseTabItem> = defaults.fold(tabs) { current, item ->
        addFixedJmTab(current, item)?.first ?: current
    }

    /** Adds the single Picacg entry at its platform boundary without reordering existing tabs. */
    fun addMissingPicacgHome(tabs: List<BrowseTabItem>, home: BrowseTabItem): List<BrowseTabItem> {
        if (tabs.any { it.platformId == ContentPlatform.PICACG && it.id == home.id }) return tabs
        return insertNewTab(tabs, home).first
    }

    /** Rename only known historical default labels; user-customized names remain untouched. */
    fun migrateLegacyJmName(item: BrowseTabItem): BrowseTabItem = when {
        item.platformId != ContentPlatform.JM -> item
        item.builtin && item.id == BrowseTabStore.ID_JM_HOME && item.name == BrowseTabStore.LEGACY_JM_HOME_NAME ->
            item.copy(name = BrowseTabStore.JM_HOME_NAME)
        item.builtin && item.id == JmBrowsePolicy.ALL_HOT_TAB_ID && item.name in BrowseTabStore.LEGACY_JM_ALL_HOT_NAMES ->
            item.copy(name = BrowseTabStore.JM_ALL_HOT_NAME)
        else -> item
    }
}
