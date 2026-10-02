package com.zycomic.app.data.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 兼容数字和字符串的 String 序列化器。
 * 服务端 uid 有时返回数字（814955），有时返回字符串（"814955"）。
 */
object StringOrIntSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrInt", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        return if (decoder is JsonDecoder) {
            when (val element = decoder.decodeJsonElement()) {
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
        } else {
            decoder.decodeString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

/**
 * 兼容字符串和字符串数组的 String 序列化器。
 * 服务端 author 有时返回字符串（"作者名"），有时返回数组（["作者名"]）。
 * 数组时取第一个元素，空数组返回空字符串。
 */
object StringOrListSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        return if (decoder is JsonDecoder) {
            when (val element = decoder.decodeJsonElement()) {
                is JsonPrimitive -> element.content
                is JsonArray -> {
                    if (element.jsonArray.isNotEmpty()) {
                        element.jsonArray[0].jsonPrimitive.content
                    } else {
                        ""
                    }
                }
                else -> ""
            }
        } else {
            decoder.decodeString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

/**
 * 兼容字符串和对象数组的 List<TagItem> 序列化器。
 * 服务端 tags 有时返回字符串（"韩漫|中文"），有时返回数组（[{name:"标签1"}]）。
 * 字符串时按 | 分割。
 */
object TagsSerializer : KSerializer<List<TagItem>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TagsSerializer", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<TagItem> {
        return if (decoder is JsonDecoder) {
            when (val element = decoder.decodeJsonElement()) {
                is JsonPrimitive -> {
                    element.content.split("|").filter { it.isNotEmpty() }
                        .map { TagItem(name = it) }
                }
                is JsonArray -> {
                    element.jsonArray.mapNotNull { item ->
                        when (item) {
                            is JsonPrimitive -> TagItem(name = item.content)
                            is kotlinx.serialization.json.JsonObject -> {
                                val name = item["name"]?.jsonPrimitive?.content ?: ""
                                val id = item["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                                TagItem(name = name, id = id)
                            }
                            else -> null
                        }
                    }
                }
                else -> emptyList()
            }
        } else {
            emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<TagItem>) {
        encoder.encodeString(value.joinToString("|") { it.name })
    }
}

/**
 * 兼容字符串和对象的图片路径列表序列化器。
 * 服务端 piclist 每个元素可能是字符串（"path.jpg"）或对象（{"pic":"path.jpg"} / {"url":"path.jpg"}）。
 * 对象时取 pic 或 url 字段。
 */
object PicListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PicListSerializer", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> {
        return if (decoder is JsonDecoder) {
            when (val element = decoder.decodeJsonElement()) {
                is JsonArray -> {
                    element.jsonArray.mapNotNull { item ->
                        when (item) {
                            is JsonPrimitive -> item.content
                            is kotlinx.serialization.json.JsonObject -> {
                                item["pic"]?.jsonPrimitive?.content
                                    ?: item["url"]?.jsonPrimitive?.content
                                    ?: ""
                            }
                            else -> null
                        }
                    }.filter { it.isNotEmpty() }
                }
                else -> emptyList()
            }
        } else {
            emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeString(value.joinToString(","))
    }
}
