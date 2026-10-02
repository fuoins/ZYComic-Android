package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/**
 * 章节内容（/api/chapters/index 响应解密后）。
 * piclist 为图片路径数组，img_domains 为图片域名数组。
 * id / prev / next 均为章节 ID（字符串数字），兼容数字形态。
 */
@Serializable
data class ChapterContent(
    @Serializable(with = StringOrIntSerializer::class)
    val id: String = "",
    val name: String = "",
    @Serializable(with = PicListSerializer::class)
    val piclist: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("img_domains")
    val imgDomains: List<String> = emptyList(),
    @Serializable(with = StringOrIntSerializer::class)
    val prev: String = "",
    @Serializable(with = StringOrIntSerializer::class)
    val next: String = "",
)
