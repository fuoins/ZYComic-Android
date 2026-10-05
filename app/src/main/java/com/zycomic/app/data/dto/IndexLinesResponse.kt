package com.zycomic.app.data.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** 首页接口中服务端动态下发的线路列表（顶层字段，兼容多种命名）。 */
@Serializable
data class IndexLinesResponse(
    @JsonNames("lines", "domains", "api_domains", "api_urls")
    val serverLines: List<String>? = null,
)
