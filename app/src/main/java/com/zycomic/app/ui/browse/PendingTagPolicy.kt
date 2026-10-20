package com.zycomic.app.ui.browse

import com.zycomic.app.platform.ContentPlatform
import java.util.UUID

/** Detail-tag navigation must not rename, reorder or rewrite the user's tab presets. */
internal object PendingTagPolicy {
    data class Target(val tabs: List<BrowseTabItem>, val index: Int)

    fun resolve(tabs: List<BrowseTabItem>, manwaEnabled: Boolean): Target? {
        if (!manwaEnabled) return null
        val existing = tabs.indexOfFirst { it.platformId == ContentPlatform.MANWA && it.kind == TabKind.FILTER }
        if (existing >= 0) return Target(tabs, existing)

        val names = tabs.map { it.name }.toSet()
        var name = "mw"
        var suffix = 2
        while (name in names) name = "mw (${suffix++})"
        val ids = tabs.map { it.id }.toSet()
        var id: String
        do {
            id = "custom_${UUID.randomUUID()}"
        } while (id in ids)
        val item = BrowseTabItem(
            id = id,
            name = name,
            kind = TabKind.FILTER,
            filter = FilterSnapshot(),
            platformId = ContentPlatform.MANWA,
        )
        return Target(tabs + item, tabs.size)
    }

    /** Preserve the existing detail-tag semantics: use preset fields, replacing only its tags. */
    fun temporaryFilter(item: BrowseTabItem, tag: String): FilterSnapshot =
        (item.filter ?: FilterSnapshot()).copy(tags = listOf(tag))

    fun applyAndConsume(tag: String, apply: (String) -> Boolean, onConsumed: () -> Unit) {
        if (apply(tag)) onConsumed()
    }
}
