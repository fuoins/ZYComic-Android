package com.zycomic.app.ui.browse

/** Verified, platform-specific help copy; IDs intentionally match existing persisted ContentPlatform IDs. */
internal object PlatformGuidePolicy {
    const val MANWA_ID = "manwa"
    const val PICACG_ID = "picacg"
    const val JM_ID = "jm"

    data class Section(
        val title: String,
        val body: String,
    )

    fun sections(platformId: String): List<Section> = when (platformId) {
        MANWA_ID -> listOf(
            Section(
                title = "浏览分类",
                body = "分类页默认提供一般向热门、禁漫向热门和热血热门标签。点选标签切换分类，再点漫画卡片查看详情。",
            ),
            Section(
                title = "筛选与标签管理",
                body = "当前标签页可重新筛选；标签管理可添加自定义标签，也可删除或改名。重新筛选只影响本次会话，不会改写标签页默认值。",
            ),
            Section(
                title = "可选标签页",
                body = "“最新更新”和“gay排行”是可添加的内置标签页；需要时可从标签管理中添加。",
            ),
        )
        PICACG_ID -> listOf(
            Section(
                title = "浏览漫画",
                body = "分类页提供独立的“哔咔首页”标签。选中该标签浏览哔咔内容，点漫画卡片进入详情。",
            ),
            Section(
                title = "搜索与筛选",
                body = "哔咔页面提供关键词搜索；筛选项包括分类、标签、作者和创作者 ID。应用条件后浏览匹配的哔咔内容。",
            ),
            Section(
                title = "平台范围",
                body = "哔咔有独立的平台数据与页面入口；切换到其他平台不会把哔咔标签或内容混作其他平台结果。",
            ),
        )
        JM_ID -> listOf(
            Section(
                title = "浏览禁漫",
                body = "分类页提供“jm首页”和“jm热门”标签。选中相应标签浏览禁漫内容，点漫画卡片查看详情。",
            ),
            Section(
                title = "筛选与搜索",
                body = "禁漫标签页有独立的筛选控件；分类页搜索入口会使用禁漫搜索。筛选和搜索均针对禁漫内容。",
            ),
            Section(
                title = "独立平台内容",
                body = "禁漫页签和漫画数据与漫蛙、哔咔分开；页面不会用其他平台的结果冒充禁漫内容。",
            ),
        )
        else -> error("Unknown content platform ID: $platformId")
    }
}
