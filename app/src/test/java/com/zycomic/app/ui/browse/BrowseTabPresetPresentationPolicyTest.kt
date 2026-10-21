package com.zycomic.app.ui.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrowseTabPresetPresentationPolicyTest {
    @Test
    fun `available presets keep their label and show add state`() {
        assertEquals(
            BrowseTabPresetPresentation(label = "首页", isAdded = false),
            BrowseTabPresetPresentationPolicy.present("首页", isAdded = false),
        )
    }

    @Test
    fun `unavailable preset is not presented as added unless it is a duplicate`() {
        assertEquals(
            BrowseTabPresetPresentation(label = "mw一般向热门", isAdded = false),
            BrowseTabPresetPresentationPolicy.present("mw一般向热门", isAdded = false),
        )
    }

    @Test
    fun `added presets keep the base label and show added state`() {
        assertEquals(
            BrowseTabPresetPresentation(label = "首页", isAdded = true),
            BrowseTabPresetPresentationPolicy.present("首页", isAdded = true),
        )
    }
}
