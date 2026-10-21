package com.zycomic.app.ui.search

import com.zycomic.app.platform.ContentPlatform

/** Search-specific choices that can be verified without Android or network dependencies. */
enum class SearchProvider(
    val storageValue: String,
    val displayName: String,
    val displayModeKey: String,
    val defaultDisplayMode: Int,
) {
    MANWA("manwa", "mw", "search_display_mode", 3),
    PICACG("picacg", "哔咔", "picacg_search_display_mode", 1),
    JM("jm", "jm", "jm_search_display_mode", 1),
    ;

    companion object {
        fun fromStorage(value: String?): SearchProvider =
            entries.firstOrNull { it.storageValue == value } ?: JM
    }

    val usesJmCoverImageLoader: Boolean
        get() = this == JM

    val usesPicacgCoverImageLoader: Boolean
        get() = this == PICACG
}

/** Resolve the active provider without mutating the user's remembered ordinary-search choice. */
fun resolveSearchProvider(
    preferred: SearchProvider?,
    manwaEnabled: Boolean,
    jmEnabled: Boolean,
    picacgEnabled: Boolean = false,
): SearchProvider {
    val requested = preferred ?: SearchProvider.JM
    val requestedEnabled = when (requested) {
        SearchProvider.MANWA -> manwaEnabled
        SearchProvider.PICACG -> picacgEnabled
        SearchProvider.JM -> jmEnabled
    }
    if (requestedEnabled) return requested
    return when {
        jmEnabled -> SearchProvider.JM
        picacgEnabled -> SearchProvider.PICACG
        manwaEnabled -> SearchProvider.MANWA
        else -> requested
    }
}

/** Explicit detail-origin sources take precedence over the remembered ordinary-search choice. */
fun initialSearchProvider(
    explicitProvider: SearchProvider?,
    rememberedProvider: SearchProvider?,
    manwaEnabled: Boolean,
    jmEnabled: Boolean,
    picacgEnabled: Boolean = false,
): SearchProvider = resolveSearchProvider(
    preferred = explicitProvider ?: rememberedProvider,
    manwaEnabled = manwaEnabled,
    jmEnabled = jmEnabled,
    picacgEnabled = picacgEnabled,
)

/** Values confirmed by JMSource and the repository's JM contract report. */
const val JM_DEFAULT_SEARCH_SORT_CODE = "tf"

fun jmSearchSortCode(saved: String?): String = saved?.takeIf { it.isNotBlank() } ?: JM_DEFAULT_SEARCH_SORT_CODE

val JmSearchSortOptions: List<Pair<String, String>> = listOf(
    "最新" to "mr",
    "总排行" to "mv",
    "月排行" to "mv_m",
    "周排行" to "mv_w",
    "日排行" to "mv_t",
    "最多图片" to "mp",
    "最多喜欢" to "tf",
)

const val PICACG_DEFAULT_SEARCH_SORT_CODE = "ld"

fun picacgSearchSortCode(saved: String?): String =
    saved?.takeIf { code -> PicacgSearchSortOptions.any { it.second == code } } ?: PICACG_DEFAULT_SEARCH_SORT_CODE

val PicacgSearchSortOptions: List<Pair<String, String>> = listOf(
    "最新" to "dd",
    "最旧" to "da",
    "最多喜欢" to "ld",
    "最多浏览" to "vd",
)

fun searchDetailId(provider: SearchProvider, id: String): String =
    when (provider) {
        SearchProvider.MANWA -> id
        SearchProvider.PICACG -> if (id.startsWith("picacg:")) id else "picacg:$id"
        SearchProvider.JM -> if (id.startsWith("jm:")) id else "jm:$id"
    }

fun SearchProvider.toContentPlatform(): ContentPlatform = when (this) {
    SearchProvider.MANWA -> ContentPlatform.MANWA
    SearchProvider.PICACG -> ContentPlatform.PICACG
    SearchProvider.JM -> ContentPlatform.JM
}

/** Immutable parameters for one search operation; never read mutable UI state after launch. */
internal data class SearchRequest(
    val query: String,
    val provider: SearchProvider,
    val order: String,
    val page: Int,
    val generation: Long,
    val picacgCategories: List<String> = emptyList(),
)

/** Monotonic token used to reject results and finally blocks from superseded requests. */
internal class SearchRequestGeneration {
    private var current = 0L

    fun advance(): Long = ++current

    fun isCurrent(generation: Long): Boolean = generation == current
}

fun searchGridColumnsPreferenceKey(provider: SearchProvider, isLandscape: Boolean): String {
    val prefix = when (provider) {
        SearchProvider.MANWA -> "search"
        SearchProvider.PICACG -> "picacg_search"
        SearchProvider.JM -> "jm_search"
    }
    val orientation = if (isLandscape) "landscape" else "portrait"
    return "${prefix}_grid_columns_$orientation"
}

/** API category values are titles, not ids; never translate them into another platform's filters. */
internal fun normalizedPicacgSearchCategories(values: List<String>): List<String> = values.map(String::trim).filter(String::isNotEmpty).distinct()
