package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable

/**
 * 章节内容（/api/chapters/index 响应解密后）。
 *
 * 字段兼容性：
 * - piclist：每个元素可能是字符串或 {pic/url} 对象，由 [PicListSerializer] 兼容。
 * - img_domains：可能是数组或字符串，字段名可能是 img_domains 或 _ALL_IMG_DOMAINS，由 [ImgDomainsSerializer] + @JsonNames 兼容。
 * - id / prev / next：字符串数字，兼容数字形态。
 */
@Serializable
data class ChapterContent(
    @Serializable(with = StringOrIntSerializer::class)
    val id: String = "",
    val name: String = "",
    @Serializable(with = PicListSerializer::class)
    val piclist: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("img_domains")
    @kotlinx.serialization.json.JsonNames("_ALL_IMG_DOMAINS")
    @Serializable(with = ImgDomainsSerializer::class)
    val imgDomains: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("_CURRENT_IMG_DOMAIN")
    val currentImgDomain: String = "",
    @kotlinx.serialization.SerialName("_CURRENT_IMG_INDEX")
    @Serializable(with = StringOrIntSerializer::class)
    val currentImgIndex: String = "",
    @Serializable(with = StringOrIntSerializer::class)
    val prev: String = "",
    @Serializable(with = StringOrIntSerializer::class)
    val next: String = "",
    @kotlinx.serialization.SerialName("book_id")
    @Serializable(with = StringOrIntSerializer::class)
    val bookId: String = "",
)
