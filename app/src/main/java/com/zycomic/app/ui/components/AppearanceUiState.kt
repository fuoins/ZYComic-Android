package com.zycomic.app.ui.components

/**
 * 外观设置页的临时 UI 状态：
 * 从外观页点「预览」离开、再点悬浮按钮回来时，恢复到离开前所在的标签页与滚动位置，
 * 免得每次都要重新翻到刚才调的参数。
 */
object AppearanceUiState {
    var tab: Int = 0

    private val scrolls = mutableMapOf<Int, Pair<Int, Int>>()

    fun scrollFor(tab: Int): Pair<Int, Int>? = scrolls[tab]

    fun saveScroll(tab: Int, index: Int, offset: Int) {
        scrolls[tab] = index to offset
    }
}
