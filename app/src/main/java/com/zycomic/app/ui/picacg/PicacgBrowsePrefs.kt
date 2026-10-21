package com.zycomic.app.ui.picacg

import android.content.Context
import com.zycomic.app.picacg.PicacgCategory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class PicacgBrowsePageMode { HOME, RANKING, CATEGORY }

internal object PicacgBrowsePolicy {
    data class LeaderboardPreset(val name: String, val timeframe: String)

    val leaderboards = listOf(
        LeaderboardPreset("24小时", "H24"),
        LeaderboardPreset("7天", "D7"),
        LeaderboardPreset("30天", "D30"),
    )

    fun visibleCategories(categories: List<PicacgCategory>): List<PicacgCategory> =
        categories.filter { it.active && !it.isWeb }.distinctBy { it.title }
}

/** Immutable browse parameters, kept separate from the Manwa/JM tab filter schemas. */
data class PicacgBrowseFilter(
    val category: String = "",
    val tag: String = "",
    val author: String = "",
    val creatorId: String = "",
    val chineseTeam: String = "",
    val sort: String = "dd",
) {
    fun normalized(): PicacgBrowseFilter = copy(
        category = category.trim(),
        tag = tag.trim(),
        author = author.trim(),
        creatorId = creatorId.trim(),
        chineseTeam = chineseTeam.trim(),
        sort = sort.takeIf { it in SORT_CODES } ?: DEFAULT_SORT,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("category", category)
        .put("tag", tag)
        .put("author", author)
        .put("creatorId", creatorId)
        .put("chineseTeam", chineseTeam)
        .put("sort", sort)

    companion object {
        const val DEFAULT_SORT = "dd"
        val SORT_CODES = setOf("dd", "da", "ld", "vd")

        fun fromJson(json: JSONObject): PicacgBrowseFilter = PicacgBrowseFilter(
            category = json.optString("category"),
            tag = json.optString("tag"),
            author = json.optString("author"),
            creatorId = json.optString("creatorId"),
            chineseTeam = json.optString("chineseTeam"),
            sort = json.optString("sort", DEFAULT_SORT),
        ).normalized()
    }
}

data class PicacgBrowsePreset(
    val id: String,
    val name: String,
    val filter: PicacgBrowseFilter,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("filter", filter.normalized().toJson())

    companion object {
        fun fromJson(json: JSONObject): PicacgBrowsePreset? {
            val id = json.optString("id").trim()
            val name = json.optString("name").trim()
            val filter = json.optJSONObject("filter") ?: return null
            if (id.isBlank() || name.isBlank()) return null
            return PicacgBrowsePreset(id, name, PicacgBrowseFilter.fromJson(filter))
        }
    }
}

/** Pure list operations used by local persistence and unit tests. */
internal object PicacgBrowsePrefsPolicy {
    fun sanitize(presets: List<PicacgBrowsePreset>): List<PicacgBrowsePreset> {
        val seenIds = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        return presets.mapNotNull { preset ->
            val id = preset.id.trim()
            val name = preset.name.trim()
            if (id.isBlank() || name.isBlank() || !seenIds.add(id) || !seenNames.add(name.lowercase())) {
                null
            } else {
                preset.copy(id = id, name = name, filter = preset.filter.normalized())
            }
        }
    }

    fun save(current: List<PicacgBrowsePreset>, name: String, filter: PicacgBrowseFilter): List<PicacgBrowsePreset>? {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return null
        val existing = sanitize(current)
        if (existing.any { it.name.equals(cleanName, ignoreCase = true) }) return null
        return existing + PicacgBrowsePreset(UUID.randomUUID().toString(), cleanName, filter.normalized())
    }

    fun delete(current: List<PicacgBrowsePreset>, id: String): List<PicacgBrowsePreset> =
        sanitize(current).filterNot { it.id == id }
}

/** Picacg-only SharedPreferences namespace; these presets never enter shared BrowseTabStore. */
object PicacgBrowsePrefs {
    private const val PREFS_NAME = "picacg_browse_preferences"
    private const val KEY_PRESETS = "custom_filter_presets_v1"

    fun load(context: Context): List<PicacgBrowsePreset> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PRESETS, null)
            ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> PicacgBrowsePreset.fromJson(array.optJSONObject(index) ?: JSONObject()) }
                .filterNotNull()
        }.getOrDefault(emptyList())
        return PicacgBrowsePrefsPolicy.sanitize(parsed)
    }

    fun save(context: Context, presets: List<PicacgBrowsePreset>) {
        val array = JSONArray()
        PicacgBrowsePrefsPolicy.sanitize(presets).forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, array.toString())
            .apply()
    }
}
