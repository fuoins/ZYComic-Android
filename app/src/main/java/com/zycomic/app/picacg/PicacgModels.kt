package com.zycomic.app.picacg

import com.zycomic.app.data.dto.Chapter
import com.zycomic.app.data.dto.Manga
import com.zycomic.app.data.dto.TagItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Picacg-only wire and UI-facing models. */
data class PicacgCategory(
    val id: String,
    val title: String,
    val description: String = "",
    val active: Boolean = true,
    val isWeb: Boolean = false,
)
data class PicacgPage<T>(val items: List<T>, val hasMore: Boolean, val page: Int)
data class PicacgEpisode(val id: String, val title: String, val order: Int, val updatedAt: String = "")
data class PicacgImage(val id: String, val url: String, val originalName: String)
data class PicacgProfile(
    val id: String,
    val name: String,
    val email: String = "",
    val avatarUrl: String = "",
    val slogan: String = "",
    val title: String = "",
    val gender: String = "",
    val birthday: String = "",
    val level: Int = 0,
    val exp: Int = 0,
    val characters: List<String> = emptyList(),
    val createdAt: String = "",
    val isPunched: Boolean? = null,
    val verified: Boolean = false,
)

/** Interaction fields supplied by the comic detail endpoint, kept separate from the legacy Manga DTO. */
data class PicacgComicDetail(
    val manga: Manga,
    val isFavourite: Boolean,
    val isLiked: Boolean,
    val likesCount: Int,
    val commentsCount: Int,
    val allowComment: Boolean,
    val author: String,
    val chineseTeam: String,
    val creatorId: String,
    val categories: List<String>,
    val tags: List<String>,
    val uploader: String,
    val updatedAt: String,
    val recommendations: List<Manga>,
)

data class PicacgComicState(
    val isFavourite: Boolean,
    val isLiked: Boolean,
    val likesCount: Int,
    val commentsCount: Int,
    val allowComment: Boolean,
    val author: String,
    val chineseTeam: String,
    val creatorId: String,
    val categories: List<String>,
    val tags: List<String>,
)

/** Parsed from the documented comments and child-comments responses. */
data class PicacgComment(
    val id: String,
    val userName: String,
    val avatarUrl: String,
    val content: String,
    val isLiked: Boolean,
    val likesCount: Int,
    val replyCount: Int,
    val createdAt: String,
)

/** Bounded, platform-private page cache that retains terminal-page metadata. */
internal class PicacgPageCache<T>(private val maxSize: Int = 32) {
    private val entries = object : LinkedHashMap<String, PicacgPage<T>>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PicacgPage<T>>?) = size > maxSize
    }

    @Synchronized fun get(key: String): PicacgPage<T>? = entries[key]

    @Synchronized fun put(key: String, value: PicacgPage<T>) {
        entries[key] = value
    }

    @Synchronized fun clear() = entries.clear()
}

/** Signing omits the leading slash only; query ordering/encoding and the request itself stay intact. */
object PicacgSigner {
    const val API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
    private const val HMAC_KEY = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"

    fun sign(pathAndQuery: String, method: String, timeSeconds: Long, nonce: String): String {
        require(pathAndQuery.startsWith('/')) { "Picacg path must begin with /" }
        require(nonce.matches(Regex("[a-z0-9]{32}"))) { "Picacg nonce must be 32 lowercase alphanumeric characters" }
        val message = (pathAndQuery.removePrefix("/") + timeSeconds + nonce + method.lowercase(java.util.Locale.ROOT) + API_KEY)
            .lowercase(java.util.Locale.ROOT)
            .toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message).joinToString("") { "%02x".format(it) }
    }
}

/** Keeps an API-provided local content URI intact; network image hosts are supplied by PicacgRouteManager. */
internal object PicacgImageUrl {
    fun resolve(path: String, selectedImageHost: String): String {
        val value = path.trim()
        if (value.startsWith("file://") || value.startsWith("content://")) return value
        require(selectedImageHost.isNotBlank()) { "Picacg image host is not configured" }
        val host = selectedImageHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        require(host.isNotBlank() && !host.contains('/') && !host.contains('@')) { "Invalid configured Picacg image host" }
        var relative = value.trimStart('/')
        while (relative.startsWith("static/", ignoreCase = true)) relative = relative.substringAfter('/')
        return "https://$host/static/$relative"
    }

    /**
     * Prefer the API-provided [fileServer] (the real bucket, e.g. storage1.picacomic.com) so the URL
     * carries the true origin; [PicacgRouteManager.rewriteImageUrl] then mirrors that bucket. Falls
     * back to the currently selected image host when the API omits a usable fileServer.
     */
    fun resolveWithFileServer(path: String, fileServer: String?, selectedImageHost: String): String {
        val value = path.trim()
        if (value.startsWith("file://") || value.startsWith("content://")) return value
        val server = fileServer?.trim()?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
            ?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('@') }
        return resolve(value, server ?: selectedImageHost)
    }
}

/** Convert only documented Picacg comic fields into the app's existing Manga DTO. */
internal fun Map<String, Any?>.toPicacgManga(): Manga {
    fun str(key: String) = this[key]?.toString().orEmpty()
    fun count(key: String) = str(key).toIntOrNull() ?: 0
    fun strings(key: String): List<String> = (this[key] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
    val thumb = this["thumb"] as? Map<*, *>
    val coverPath = thumb?.get("path")?.toString().orEmpty()
    val categories = strings("categories")
    val tags = strings("tags") + categories
    val chapters = (this["chapterList"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { episode ->
        Chapter(id = episode["_id"]?.toString().orEmpty(), name = episode["title"]?.toString().orEmpty(), sort = (episode["order"]?.toString()?.toIntOrNull() ?: 0))
    }.orEmpty()
    return Manga(
        id = str("_id").ifBlank { str("id") },
        name = str("title"),
        author = str("author"),
        picx = coverPath,
        text = str("description"),
        state = if (this["finished"] == true) "完结" else "连载",
        end = if (this["finished"] == true) "完结" else "连载",
        hits = count("totalViews"),
        nums = count("pagesCount"),
        commentNums = count("totalComments"),
        fav = if (this["isFavourite"] == true) 1 else 0,
        tags = tags.distinct().map { TagItem(name = it) },
        chapterList = chapters,
    )
}

internal fun JSONObject.toPicacgMap(): MutableMap<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = opt(key)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toPicacgMap()
        is JSONArray -> value.toPicacgList()
        else -> value
    }
}.toMutableMap()

internal fun JSONArray.toPicacgList(): List<Any?> = (0 until length()).map { index ->
    when (val value = opt(index)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toPicacgMap()
        is JSONArray -> value.toPicacgList()
        else -> value
    }
}

internal fun normalizedPicacgUri(raw: String): String = runCatching { URI(raw).normalize().toString() }.getOrDefault(raw)
