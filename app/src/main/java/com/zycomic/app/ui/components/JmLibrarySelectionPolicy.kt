package com.zycomic.app.ui.components

/** Selection applies to loaded/filtered albums; inversion preserves hidden selections like Manwa. */
internal object JmLibrarySelectionPolicy {
    fun toggle(selected: Set<String>, id: String): Set<String> = if (id in selected) selected - id else selected + id

    fun selectAll(visibleIds: Collection<String>): Set<String> = visibleIds.toSet()

    fun invert(selected: Set<String>, visibleIds: Collection<String>): Set<String> = selected.toMutableSet().apply {
        visibleIds.toSet().forEach { id -> if (!add(id)) remove(id) }
    }
}
