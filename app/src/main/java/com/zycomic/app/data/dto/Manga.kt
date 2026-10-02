package com.zycomic.app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * 漫画对象。
 * 被屏蔽漫画特征：name 以"已根据标签"开头，pic/picx 为 blocked.jpg。
 *
 * 字段类型（与服务端实际返回对齐）：
 * - id / chapterList[].id 为字符串数字（"539752"），用 [StringOrIntSerializer] 兼容数字/字符串两种形态。
 * - state 为连载状态文本（"连载"/"完结"），**不是** 0/1 数字。
 * - start 为上次阅读章节 ID（"0" 表示从第一章开始），字符串。
 * - end 在收藏列表/详情中均可能为"连载"文本，字符串。
 *
 * 别名：@JsonNames 指定兼容字段名（需 Json { useAlternativeNames = true }）。
 */
@Serializable
data class Manga(
    @JsonNames("book_id")
    @Serializable(with = StringOrIntSerializer::class)
    val id: String = "",
    @JsonNames("title")
    val name: String = "",
    val nickname: String = "",
    val picx: String = "",
    val pic: String = "",
    @Serializable(with = StringOrListSerializer::class)
    val author: String = "",
    @JsonNames("serialize", "status")
    val state: String = "",          // "连载" / "完结"
    val score: String = "",
    val hits: Int = 0,
    val shits: Int = 0,
    @JsonNames("desc", "content")
    val text: String = "",
    val nums: Int = 0,
    val addtime: String = "",
    @Serializable(with = StringOrIntSerializer::class)
    val end: String = "",
    val fav: Int = 0,                // 0 未收藏 / 1 已收藏
    @Serializable(with = StringOrIntSerializer::class)
    val start: String = "",          // 上次阅读章节 ID；"0" / 空 = 从第一章开始
    @SerialName("book_area") val bookArea: String = "",
    @SerialName("category_name") val categoryName: String = "",
    @Serializable(with = TagsSerializer::class)
    val tags: List<TagItem> = emptyList(),
    @SerialName("chapter_list")
    @JsonNames("chapters", "list")
    val chapterList: List<Chapter> = emptyList(),
    @SerialName("love_list") val loveList: List<Manga> = emptyList(),
    @SerialName("last_chapter") val lastChapter: String = "",
    @SerialName("comment_nums") val commentNums: Int = 0,
)

/** 章节信息。id 为字符串数字（"123"），兼容数字形态。 */
@Serializable
data class Chapter(
    @Serializable(with = StringOrIntSerializer::class)
    val id: String = "",
    val name: String = "",
    val vip: Int = 0,
    val addtime: String = "",
    val sort: Int = 0,
    val readed: Int = 0,
    @SerialName("is_vip") val isVip: Int = 0,
    val lv: Int = 0,
)

/** 标签项 */
@Serializable
data class TagItem(
    val name: String = "",
    val id: Int = 0,
)
