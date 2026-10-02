package com.zycomic.app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 漫画对象。
 * 被屏蔽漫画特征：name 以"已根据标签"开头，pic/picx 为 blocked.jpg。
 */
@Serializable
data class Manga(
    val id: Int = 0,
    val name: String = "",
    val nickname: String = "",
    val picx: String = "",
    val pic: String = "",
    val author: String = "",
    val state: Int = 0,          // 0 连载中 / 1 完结
    val score: String = "",
    val hits: Int = 0,
    val shits: Int = 0,
    val text: String = "",
    val nums: Int = 0,
    val addtime: String = "",
    val end: Int = 0,
    val fav: Int = 0,            // 0 未收藏 / 1 已收藏
    val start: Int = 0,          // 继续阅读章节 ID
    @SerialName("book_area") val bookArea: Int = 0,
    @SerialName("category_name") val categoryName: String = "",
    val tags: List<TagItem> = emptyList(),
    @SerialName("chapter_list") val chapterList: List<Chapter> = emptyList(),
    @SerialName("love_list") val loveList: List<Manga> = emptyList(),
    @SerialName("comment_nums") val commentNums: Int = 0,
)

/** 章节信息 */
@Serializable
data class Chapter(
    val id: Int = 0,
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
