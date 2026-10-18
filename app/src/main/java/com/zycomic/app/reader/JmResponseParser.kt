package com.zycomic.app.reader

import com.zycomic.app.data.dto.TagItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Parses confirmed JM fields; album IDs are used as episode IDs only for an explicit empty series. */
internal object JmResponseParser {
    data class WeekIssue(val id: String, val title: String)
    data class ListItem(val id: String, val name: String, val author: String)
    data class ListPage(val items: List<ListItem>, val total: Int)
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
        val authors: List<String>,
        val actors: List<String>,
        val works: List<String>,
        val likes: Int?,
        val liked: Boolean?,
        val views: Int?,
        val updateTime: String,
    )
    data class ForumPage(val total: Int?, val comments: List<com.zycomic.app.data.dto.JmComment>)

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
            authors = album.stringValues("author"),
            actors = album.stringValues("actors"),
            works = album.stringValues("works"),
            likes = album.opt("likes").asIntOrNull(),
            liked = album.opt("liked").asBoolOrNull(),
            views = album.opt("total_views").asIntOrNull(),
            updateTime = album.text("addtime").toLongOrNull()?.let { seconds ->
                java.text.SimpleDateFormat("yyyy-M-d", java.util.Locale.ROOT).format(java.util.Date(seconds * 1000L))
            }.orEmpty(),
        )
    }

    fun forumPage(payload: JSONObject): ForumPage {
        val list = payload.optJSONArray("list") ?: throw IOException("JM /forum 响应缺少 list[]")
        val comments = (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("CID")
            if (id.isBlank()) return@mapNotNull null
            val raw = item.text("content")
            val start = raw.indexOf('>')
            val end = raw.lastIndexOf('<')
            val content = if (start >= 0 && end > start) raw.substring(start + 1, end) else raw
            com.zycomic.app.data.dto.JmComment(
                id = id,
                parentId = item.text("parent_CID"),
                username = item.text("username"),
                avatarUrl = item.text("photo"),
                time = item.text("addtime"),
                content = content,
            )
        }
        return ForumPage(payload.opt("total").asIntOrNull(), comments.distinctBy { it.id })
    }

    fun userAvatarUrl(imageUrl: String, photo: String): String {
        val photoPath = photo.trim().trimStart('/')
        if (photoPath.isBlank()) return ""
        return "${imageUrl.trimEnd('/')}/media/users/$photoPath"
    }

    fun weekIssues(payload: JSONObject): List<WeekIssue> {
        val categories = payload.optJSONArray("categories")
            ?: throw IOException("JM /week 响应缺少有效 categories[]，请重试")
        return (0 until categories.length()).mapNotNull { index ->
            val item = categories.optJSONObject(index) ?: return@mapNotNull null
            val id = item.text("id")
            if (!validId(id)) return@mapNotNull null
            WeekIssue(id, item.text("time").ifBlank { id })
        }
    }

    /** Parse the documented { total, list } response used by /week/filter and /serialization. */
    fun listPage(payload: JSONObject, endpoint: String): ListPage {
        val list = payload.optJSONArray("list")
            ?: throw IOException("JM $endpoint 响应缺少已确认字段 list[]")
        val total = payload.optString("total").toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("JM $endpoint 响应缺少有效非负整数字段 total")
        val items = (0 until list.length()).map { index ->
            val item = list.optJSONObject(index)
                ?: throw IOException("JM $endpoint list[$index] 不是对象")
            val id = item.text("id")
            val name = item.text("name")
            if (!validId(id) || name.isBlank()) {
                throw IOException("JM $endpoint.list[$index] 缺少有效字段 id/name")
            }
            ListItem(id, name, item.text("author"))
        }.distinctBy { it.id }
        return ListPage(items, total)
    }

    fun chapters(album: JSONObject, singleChapterAlbumId: String? = null): Chapters {
        val series = album.optJSONArray("series")
            ?: throw IOException("JM /album 章节数据异常：缺少有效 series[]，请重试")
        if (series.length() == 0) {
            val albumId = singleChapterAlbumId?.let {
                it.takeIf(::validId) ?: throw IOException("JM /album 专辑 ID 无效，无法创建单话")
            }
            return Chapters(
                items = albumId?.let { listOf(Chapter(it, "第1话", 1)) }.orEmpty(),
                warning = null,
            )
        }
        var skipped = 0
        var missingSorts = 0
        val items = (0 until series.length()).mapNotNull { index ->
            val item = series.optJSONObject(index)
            val id = item?.text("id").orEmpty()
            if (!validId(id)) {
                skipped++
                return@mapNotNull null
            }
            val sourceName = item!!.text("name")
            val sort = item.text("sort").toIntOrNull() ?: run {
                missingSorts++
                index + 1
            }
            val name = sourceName.takeIf(String::isNotBlank)?.let { "第${sort}话 $it" } ?: run {
                "第${sort}话"
            }
            Chapter(id, name, sort)
        }
        if (series.length() > 0 && items.isEmpty()) {
            throw IOException("JM /album 章节数据异常：没有有效章节 ID（已跳过 $skipped 项），请重试")
        }
        val warnings = buildList {
            if (skipped > 0) add("已跳过 $skipped 项无效章节")
            if (missingSorts > 0) add("$missingSorts 项缺少有效排序，已使用返回顺序")
        }
        return Chapters(items, warnings.takeIf { it.isNotEmpty() }?.joinToString("；"))
    }

    fun chapterImages(payload: JSONObject): List<String> {
        val images = payload.optJSONArray("images")
            ?: throw IOException("JM /chapter 响应缺少已确认字段 images[]")
        if (images.length() == 0) throw IOException("JM /chapter images[] 为空，没有可读图片")
        return (0 until images.length()).map { index ->
            (images.opt(index) as? String)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IOException("JM /chapter images[$index] 不是有效图片文件名")
        }
    }

    private fun validId(id: String): Boolean = id.matches(Regex("[0-9]+")) && id.any { it != '0' }

    private fun JSONObject.stringValues(key: String): List<String> = when (val value = opt(key)) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it).asText().takeIf(String::isNotBlank) }
        is String -> value.split('|', ',').map(String::trim).filter(String::isNotBlank)
        else -> emptyList()
    }

    private fun Any?.asIntOrNull(): Int? = when (this) {
        is Number -> toInt()
        is String -> trim().toIntOrNull()
        else -> null
    }

    private fun Any?.asBoolOrNull(): Boolean? = when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> when (trim().lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
        else -> null
    }

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
