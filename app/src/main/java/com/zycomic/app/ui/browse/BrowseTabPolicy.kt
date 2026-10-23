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

    /** New tabs are appended globally without rearranging existing entries. */
    fun insertNewTab(current: List<BrowseTabItem>, item: BrowseTabItem): Pair<List<BrowseTabItem>, Int> {
        val result = current.toMutableList()
        val index = result.size
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

    /** Adds a fixed Picacg page immediately after its predecessor without disturbing custom order. */
    fun addFixedPicacgTab(tabs: List<BrowseTabItem>, item: BrowseTabItem, afterId: String): List<BrowseTabItem>? {
        if (item.platformId != ContentPlatform.PICACG || tabs.any { identity(it) == identity(item) }) return null
        val result = tabs.toMutableList()
        val predecessorPlatform = if (afterId == JmBrowsePolicy.ALL_HOT_TAB_ID) ContentPlatform.JM else ContentPlatform.PICACG
        val predecessor = result.indexOfFirst { it.platformId == predecessorPlatform && it.id == afterId }
        val home = result.indexOfFirst { it.platformId == ContentPlatform.PICACG && it.id == BrowseTabStore.ID_PICACG_HOME }
        val index = when {
            predecessor >= 0 -> predecessor + 1
            home >= 0 -> home + 1
            else -> result.indexOfFirst { it.platformId != ContentPlatform.PICACG }.takeIf { it >= 0 } ?: result.size
        }
        result.add(index, item)
        return result
    }

    /**
     * Migrate only a complete, canonical previous default sequence. Names, custom ordering, filters and
     * any extra/missing tabs make the list user-owned and therefore ineligible for this migration.
     */
    fun migrateCompleteDefaultPreset(
        tabs: List<BrowseTabItem>,
        previousDefaults: List<BrowseTabItem>,
        defaults: List<BrowseTabItem>,
    ): List<BrowseTabItem>? {
        if (tabs.size != previousDefaults.size || defaults.size != previousDefaults.size) return null
        val identities = tabs.map(::identity)
        if (identities.toSet().size != identities.size) return null
        if (identities != previousDefaults.map(::identity)) return null
        if (identities.toSet() != defaults.map(::identity).toSet()) return null

        val defaultByIdentity = defaults.associateBy(::identity)
        val isKnownDefaultName = tabs.zip(previousDefaults).all { (item, expected) ->
            val currentDefaultName = defaultByIdentity.getValue(identity(expected)).name
            val acceptedNames = when {
                item.platformId == ContentPlatform.JM && item.id == BrowseTabStore.ID_JM_HOME ->
                    setOf(expected.name, currentDefaultName, BrowseTabStore.LEGACY_JM_HOME_NAME)
                item.platformId == ContentPlatform.JM && item.id == JmBrowsePolicy.ALL_HOT_TAB_ID ->
                    setOf(expected.name, currentDefaultName) + BrowseTabStore.LEGACY_JM_ALL_HOT_NAMES
                item.platformId == ContentPlatform.MANWA && item.id == BrowseTabStore.ID_JINMAN ->
                    setOf(expected.name, currentDefaultName, "禁漫热门")
                else -> setOf(expected.name, currentDefaultName)
            }
            item.name in acceptedNames && item.copy(name = expected.name) == expected
        }
        if (!isKnownDefaultName) return null

        val currentByIdentity = tabs.associateBy(::identity)
        return defaults.map { default -> currentByIdentity.getValue(identity(default)).copy(name = default.name) }
    }
}
