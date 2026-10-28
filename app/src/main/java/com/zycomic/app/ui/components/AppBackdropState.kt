package com.zycomic.app.ui.components

import com.zycomic.app.platform.ContentPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 背景候选：URL（或本地文件路径）连同所属平台，背景层按平台选择图片加载器。 */
data class BackdropEntry(
    val platform: ContentPlatform?,
    val url: String,
)

/**
 * 全局随机背景的当前状态。
 *
 * 背景来源（由 MainActivity 驱动，见 [BackdropSources]）：
 * 1. 自定义图片（本地文件）→ 永远优先，不走随机源；
 * 2. 随机图 API 源（多选开启）→ 从开启的源里随机取一个；
 * 3. 都没有 → current 为 null，背景层退化为中性渐变兜底。
 */
object AppBackdropState {
    private val _current = MutableStateFlow<BackdropEntry?>(null)
    val current: StateFlow<BackdropEntry?> = _current.asStateFlow()

    /**
     * 最近一张真正画到屏幕上的背景。
     *
     * 搜索页是独立 Dialog 窗口，重新组合时如果从“空白”开始就必然会闪一下；
     * 这里保存上一张已显示的图，新窗口先用它顶上，新图解码完成后再淡入。
     */
    @Volatile
    var lastRendered: BackdropEntry? = null

    /** 按当前来源配置刷新背景；[pageKey] 非空时该页的自定义图优先。 */
    fun refresh(pageKey: String? = null) {
        val page = pageKey?.let { BackdropSources.pagePath(it) }
        val custom = page ?: BackdropSources.customPath.value
        if (!custom.isNullOrBlank()) {
            _current.value = BackdropEntry(platform = null, url = custom)
            return
        }
        val source = BackdropSources.randomSource()
        // 加时间戳参数强制重新请求，随机图源每次都拿新图。
        _current.value = source?.let {
            BackdropEntry(platform = null, url = it.url + "?t=" + System.currentTimeMillis())
        }
    }

    fun clear() {
        _current.value = null
    }
}
