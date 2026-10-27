package com.zycomic.app.ui.browse

import com.zycomic.app.platform.ContentPlatform
import com.zycomic.app.ui.picacg.PicacgBrowseFilter
import com.zycomic.app.ui.picacg.PicacgRankingMode
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
    val picacgRankingMode: PicacgRankingMode = PicacgRankingMode.H24,
    /**
     * 背景封面来源（平台 id 列表）。为空表示不使用随机封面背景（与旧版一致）。
     * 新增 tab 时自动加入自身平台；删除 tab 时随 tab 一起删除。
     */
    val backgroundSources: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("builtin", builtin)
        put("backgroundSources", JSONArray(backgroundSources))
        put("platformId", platformId.id)
        if (platformId == ContentPlatform.MANWA) filter?.let { put("filter", it.toJson()) }
        if (platformId == ContentPlatform.PICACG) {
            picacgFilter?.normalized()?.let { put("picacgFilter", it.toJson()) }
            put("picacgRankingMode", picacgRankingMode.name)
        }
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
            val id = o.optString("id")
            val picacgFilter = if (
                platformId == ContentPlatform.PICACG &&
                ((kind == TabKind.FILTER && !o.optBoolean("builtin", false)) || id == "picacg_category" || pageType == BrowsePageType.PICACG_CATEGORY)
            ) {
                o.optJSONObject("picacgFilter")?.let { PicacgBrowseFilter.fromJson(it) } ?: PicacgBrowseFilter()
            } else {
                null
            }
            val rankingMode = runCatching {
                PicacgRankingMode.valueOf(o.optString("picacgRankingMode", PicacgRankingMode.H24.name))
            }.getOrDefault(PicacgRankingMode.H24)
            // 背景源缺省时自动加入自身平台（“添加 tab 时自动加入背景源”的迁移语义）。
            val backgroundArr = o.optJSONArray("backgroundSources")
            val backgroundSources = if (backgroundArr != null) {
                List(backgroundArr.length()) { backgroundArr.getString(it) }
            } else {
                listOf(platformId.id)
            }
            return BrowseTabItem(
                id = id,
                name = o.optString("name"),
                kind = kind,
                builtin = o.optBoolean("builtin", false),
                filter = filter,
                pageType = pageType,
                platformId = platformId,
                picacgFilter = picacgFilter,
                picacgRankingMode = rankingMode,
                backgroundSources = backgroundSources,
            )
        }
    }
}

/** Internal IDs, not editable tab titles, determine Picacg tab behavior. */
fun BrowseTabItem.isPicacgRanking(): Boolean =
    platformId == ContentPlatform.PICACG &&
        (id == "picacg_home" || id == "picacg_ranking" || pageType == BrowsePageType.PICACG_RANKING)

fun BrowseTabItem.isPicacgCategory(): Boolean =
    platformId == ContentPlatform.PICACG &&
        (id == "picacg_category" || pageType == BrowsePageType.PICACG_CATEGORY)

/** 新构造的 tab（无论内置还是新增）自动把自己的平台作为背景源；用户可在管理面板清空。 */
fun BrowseTabItem.withDefaultBackgrounds(): BrowseTabItem =
    if (backgroundSources.isNotEmpty()) this else copy(backgroundSources = listOf(platformId.id))
