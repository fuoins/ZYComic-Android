package com.zycomic.app.platform

internal enum class PlatformId { BILIBILI, TENCENT, KUAIKAN }

internal data class SubRank(val id: String, val label: String)

internal data class PlatformMeta(
    val id: PlatformId,
    val label: String,
    val subs: List<SubRank>,
    val defaultSub: String,
) {
    fun isValidSub(subId: String): Boolean = subs.any { it.id == subId }
}

internal object PlatformCatalog {
    val platforms: List<PlatformMeta> = listOf(
        PlatformMeta(
            id = PlatformId.BILIBILI,
            label = "哔哩哔哩",
            subs = listOf(
                SubRank("1", "国漫"),
                SubRank("0", "日漫"),
                SubRank("2", "韩漫"),
                SubRank("5", "宝藏"),
                SubRank("7", "新作"),
                SubRank("11", "男生"),
                SubRank("12", "女生"),
                SubRank("13", "完结"),
                SubRank("33", "原创"),
            ),
            defaultSub = "1",
        ),
        PlatformMeta(
            id = PlatformId.TENCENT,
            label = "腾讯",
            subs = listOf(
                SubRank("pay", "畅销"),
                SubRank("new", "新作"),
                SubRank("hot", "真香"),
            ),
            defaultSub = "pay",
        ),
        PlatformMeta(
            id = PlatformId.KUAIKAN,
            label = "快看",
            subs = listOf(
                SubRank("9", "人气"),
                SubRank("2", "新作"),
                SubRank("7", "飙升"),
                SubRank("4", "畅销"),
                SubRank("11", "韩漫"),
                SubRank("10", "日漫"),
                SubRank("6", "恋爱"),
                SubRank("25", "剧情"),
                SubRank("13", "投稿"),
                SubRank("12", "完结"),
                SubRank("28", "免费"),
                SubRank("29", "等免"),
                SubRank("109", "点赞"),
            ),
            defaultSub = "9",
        ),
    )

    val defaultPlatform: PlatformMeta get() = platforms.first()
}
