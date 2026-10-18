package com.zycomic.app.reader

import com.zycomic.app.data.dto.TagItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Parses confirmed fields only; never substitutes an album ID for a chapter ID. */
internal object JmResponseParser {
    data class WeekIssue(val id: String, val title: String)
    data class Chapter(val id: String, val name: String, val sort: Int)
    data class Chapters(val items: List<Chapter>, val warning: String?)
    data class RelatedManga(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
    )
    data class AlbumDetails(
        val author: String,
        val description: String,
        val tags: List<TagItem>,
        val addtime: String,
        val hits: Int,
        val favorite: Int,
        val related: List<RelatedManga>,
    )

    /** Extract album fields whose equivalents are present in the existing Manga DTO. */
    fun albumDetails(album: JSONObject): AlbumDetails {
        val related = album.optJSONArray("related_list")?.let { list ->
            (0 until list.length()).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val id = item.text("id")
                val name = item.text("name")
                if (!validId(id) || name.isBlank()) return@mapNotNull null
                RelatedManga(
                    id = id,
                    name = name,
                    author = item.text("author"),
                    description = item.text("description"),
                )
            }
        }.orEmpty()
        return AlbumDetails(
            author = album.firstText("author"),
            description = album.text("description"),
            tags = album.tags("tags"),
            addtime = album.text("addtime"),
            hits = album.text("total_views").toIntOrNull() ?: 0,
            favorite = album.opt("is_favorite").asFlag(),
            related = related,
        )
    }

    fun weekIssues(payload: JSONObject): List<WeekIssue> {
        val categories = payload.optJSONArray("categories")
            ?: throw IOException("JM /week 响应缺少有效 categories[]，请重试")
        return (0 until categories.length()).mapNotNull { index ->
            val item = categories.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("id")
            if (!validId(id)) return@mapNotNull null
            WeekIssue(id, item.text("title").ifBlank { id })
        }
    }

    fun chapters(album: JSONObject): Chapters {
        val series = album.optJSONArray("series")
            ?: throw IOException("JM /album 章节数据异常：缺少有效 series[]，请重试")
        var skipped = 0
        var missingNames = 0
        var missingSorts = 0
        val items = (0 until series.length()).mapNotNull { index ->
            val item = series.optJSONObject(index)
            val id = item?.text("id").orEmpty()
            if (!validId(id)) {
                skipped++
                return@mapNotNull null
            }
            val name = item!!.text("name").ifBlank {
                missingNames++
                "章节 $id"
            }
            val sort = item.text("sort").toIntOrNull() ?: run {
                missingSorts++
                index + 1
            }
            Chapter(id, name, sort)
        }
        if (series.length() > 0 && items.isEmpty()) {
            throw IOException("JM /album 章节数据异常：没有有效章节 ID（已跳过 $skipped 项），请重试")
        }
        val warnings = buildList {
            if (skipped > 0) add("已跳过 $skipped 项无效章节")
            if (missingNames > 0) add("$missingNames 项缺少名称，已使用章节 ID")
            if (missingSorts > 0) add("$missingSorts 项缺少有效排序，已使用返回顺序")
        }
        return Chapters(items, warnings.takeIf { it.isNotEmpty() }?.joinToString("；"))
    }

    private fun validId(id: String): Boolean = id.matches(Regex("[0-9]+")) && id.any { it != '0' }

    private fun JSONObject.firstText(key: String): String = when (val value = opt(key)) {
        is JSONArray -> value.opt(0).asText()
        else -> value.asText()
    }

    private fun JSONObject.tags(key: String): List<TagItem> = when (val value = opt(key)) {
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val item = value.opt(index)) {
                is JSONObject -> item.text("name").takeIf(String::isNotBlank)?.let { name ->
                    TagItem(name, item.text("id").toIntOrNull() ?: 0)
                }
                is String, is Number -> item.asText().takeIf(String::isNotBlank)?.let { TagItem(it) }
                else -> null
            }
        }
        is String -> value.split('|').map(String::trim).filter(String::isNotBlank).map { TagItem(it) }
        else -> emptyList()
    }

    private fun Any?.asText(): String = when (this) {
        is String -> trim()
        is Number -> toString()
        else -> ""
    }

    private fun Any?.asFlag(): Int = when (this) {
        is Boolean -> if (this) 1 else 0
        is Number -> if (toInt() != 0) 1 else 0
        is String -> if (trim() == "1" || trim().equals("true", ignoreCase = true)) 1 else 0
        else -> 0
    }

    private fun JSONObject.text(key: String): String = when (val value = opt(key)) {
        is String -> value.trim()
        is Number -> value.toString()
        else -> ""
    }
}
