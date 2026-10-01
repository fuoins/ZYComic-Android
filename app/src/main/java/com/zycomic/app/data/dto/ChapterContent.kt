package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/**
 * 章节内容（/api/chapters/index 响应解密后）。
 * piclist 为图片路径数组，img_domains 为图片域名数组。
 */
@Serializable
data class ChapterContent(
    val id: Int = 0,
    val name: String = "",
    val piclist: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("img_domains")
    val imgDomains: List<String> = emptyList(),
    val prev: Int = 0,
    val next: Int = 0,
)
