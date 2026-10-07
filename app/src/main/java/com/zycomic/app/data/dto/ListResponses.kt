package com.zycomic.app.data.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ListData 自定义序列化器。
 *
 * 兼容两种服务端返回格式：
 * 1. data 是对象：{"list": [...]}
 * 2. data 直接是数组：[...]
 *
 * 反序列化时判断 JsonElement 类型：JsonObject 则取 "list" 字段，JsonArray 则直接用。
 * 序列化时统一写为 {"list": [...]}。
 */
class ListDataSerializer<T>(
    private val dataSerializer: KSerializer<T>,
) : KSerializer<ListData<T>> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ListData") {
        element("list", ListSerializer(dataSerializer).descriptor, isOptional = true)
    }

    override fun deserialize(decoder: Decoder): ListData<T> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw IllegalStateException("ListDataSerializer 仅支持 JSON")
        val element = jsonDecoder.decodeJsonElement()
        val listSerializer = ListSerializer(dataSerializer)
        return when (element) {
            is JsonObject -> {
                val listElement = element["list"] ?: JsonArray(emptyList())
                ListData(jsonDecoder.json.decodeFromJsonElement(listSerializer, listElement))
            }
            is JsonArray -> {
                ListData(jsonDecoder.json.decodeFromJsonElement(listSerializer, element))
            }
            else -> ListData(emptyList())
        }
    }

    override fun serialize(encoder: Encoder, value: ListData<T>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw IllegalStateException("ListDataSerializer 仅支持 JSON")
        val listJson = jsonEncoder.json.encodeToJsonElement(ListSerializer(dataSerializer), value.list)
        val obj = buildJsonObject { put("list", listJson) }
        jsonEncoder.encodeJsonElement(obj)
    }
}

/**
 * 列表数据包装。
 * 服务端可能返回 {"list":[...]} 或直接 [...]，由 [ListDataSerializer] 兼容。
 */
@Serializable(with = ListDataSerializer::class)
data class ListData<T>(
    val list: List<T> = emptyList(),
    val favorite_limit_info: FavoriteLimitInfo? = null,
)

@Serializable
data class FavoriteLimitInfo(
    val current: Int = 0,
    val max: Int = 0,
)

/** 排行榜响应：data={list:[...]} */
typealias RankResponse = ApiResponse<ListData<Manga>>

/**
 * 最近更新响应。
 * nums 在响应顶层（不在 data 内），由 [ApiResponse.nums] 承载。
 */
typealias NewestResponse = ApiResponse<ListData<Manga>>

/** 搜索响应：参数 k，data={list:[...]} */
typealias SearchResponse = ApiResponse<ListData<Manga>>

/** 分类响应：data={list:[...]}，tag 为标签名字符串 */
typealias ClassResponse = ApiResponse<ListData<Manga>>
