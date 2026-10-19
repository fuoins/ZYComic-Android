package com.zycomic.app.ui.components

/** Shared JM library inversion preserves selections outside the current visible filter. */
internal object JmLibrarySelectionPolicy {
    fun toggle(selected: Set<String>, id: String): Set<String> = if (id in selected) selected - id else selected + id

    fun selectAll(visibleIds: Collection<String>): Set<String> = visibleIds.toSet()

    fun invert(selected: Set<String>, visibleIds: Collection<String>): Set<String> {
        val visible = visibleIds.toSet()
        return (selected - visible) + (visible - selected)
    }

    /** Browse selection, like Manwa, is restricted to items currently loaded in the tab. */
    fun invertLoaded(selected: Set<String>, loadedIds: Collection<String>): Set<String> = loadedIds.toSet() - selected
}
