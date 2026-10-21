package com.zycomic.app.ui.browse

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.ui.picacg.PicacgBrowseFilter
import org.json.JSONArray
import org.json.JSONObject

/** Tab 类型：FILTER=筛选型分类列表；SPECIAL=独立接口特殊页型（最近更新/排行等）。 */
enum class TabKind { FILTER, SPECIAL }

/**
 * 特殊页型枚举，可扩展。
 * - LATEST：最近更新（api/newest）
 * - RANKING：整体排行（api/rank，展示名「gay排行」，默认不内置、可在管理面板添加）
 * 后续如需「宇总排行」，在此新增 pageType 即可，本期不实现。
 */
enum class BrowsePageType { LATEST, RANKING, YUZONG_RANK, PICACG_RANKING, PICACG_CATEGORY }

/** 一个筛选型 tab 的完整筛选快照（对应 getClasses 的全部可调参数）。 */
data class FilterSnapshot(
    val gender: Int = 2,
    val tags: List<String> = emptyList(),
    val area: Int = 0,
    val end: Int = 0,
    val st: Int = 2, // 默认收藏（热门）
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("gender", gender)
        put("tags", JSONArray(tags))
        put("area", area)
        put("end", end)
        put("st", st)
    }

    companion object {
        fun fromJson(o: JSONObject): FilterSnapshot {
            val tagArr = o.optJSONArray("tags")
            val tags = if (tagArr != null) List(tagArr.length()) { tagArr.getString(it) } else emptyList()
            return FilterSnapshot(
                gender = o.optInt("gender", 2),
                tags = tags,
                area = o.optInt("area", 0),
                end = o.optInt("end", 0),
                st = o.optInt("st", 2),
            )
        }
    }
}

/** 分类页可持久化的一个 Tab。 */
data class BrowseTabItem(
    val id: String,
    val name: String,
    val kind: TabKind,
    val builtin: Boolean = false,
    val filter: FilterSnapshot? = null,
    val pageType: BrowsePageType? = null,
    val platformId: ContentPlatform = ContentPlatform.MANWA,
    val picacgFilter: PicacgBrowseFilter? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("builtin", builtin)
        put("platformId", platformId.id)
        if (platformId == ContentPlatform.MANWA) filter?.let { put("filter", it.toJson()) }
        if (platformId == ContentPlatform.PICACG) picacgFilter?.normalized()?.let { put("picacgFilter", it.toJson()) }
        pageType?.let { put("pageType", it.name) }
    }

    companion object {
        fun fromJson(o: JSONObject): BrowseTabItem {
            val kind = runCatching { TabKind.valueOf(o.optString("kind", TabKind.FILTER.name)) }.getOrDefault(TabKind.FILTER)
            val platformId = ContentPlatform.fromId(o.optString("platformId", ContentPlatform.MANWA.id))
            val pageType = o.optString("pageType", "").takeIf { it.isNotEmpty() }?.let {
                runCatching { BrowsePageType.valueOf(it) }.getOrNull()
            }
            val filter = if (kind == TabKind.FILTER && platformId == ContentPlatform.MANWA) {
                o.optJSONObject("filter")?.let { FilterSnapshot.fromJson(it) } ?: FilterSnapshot()
            } else {
                null
            }
            val picacgFilter = if (kind == TabKind.FILTER && platformId == ContentPlatform.PICACG && !o.optBoolean("builtin", false)) {
                o.optJSONObject("picacgFilter")?.let { PicacgBrowseFilter.fromJson(it) } ?: PicacgBrowseFilter()
            } else {
                null
            }
            return BrowseTabItem(
                id = o.optString("id"),
                name = o.optString("name"),
                kind = kind,
                builtin = o.optBoolean("builtin", false),
                filter = filter,
                pageType = pageType,
                platformId = platformId,
                picacgFilter = picacgFilter,
            )
        }
    }
}
