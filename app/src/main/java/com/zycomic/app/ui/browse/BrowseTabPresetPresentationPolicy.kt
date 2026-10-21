package com.zycomic.app.ui.browse

internal data class BrowseTabPresetPresentation(
    val label: String,
    val isAdded: Boolean,
)

internal object BrowseTabPresetPresentationPolicy {
    fun present(label: String, isAdded: Boolean): BrowseTabPresetPresentation =
        BrowseTabPresetPresentation(
            label = label.removeSuffix("（已添加）"),
            isAdded = isAdded,
        )
}
