package com.zycomic.app.ui.search

/** Search-specific choices that can be verified without Android or network dependencies. */
enum class SearchProvider(
    val storageValue: String,
    val displayName: String,
    val displayModeKey: String,
    val defaultDisplayMode: Int,
) {
    MANWA("manwa", "漫蛙", "search_display_mode", 3),
    JM("jm", "JM", "jm_search_display_mode", 1),
    ;

    companion object {
        fun fromStorage(value: String?): SearchProvider =
            entries.firstOrNull { it.storageValue == value } ?: JM
    }

    val usesJmCoverImageLoader: Boolean
        get() = this == JM
}

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

fun searchDetailId(provider: SearchProvider, id: String): String =
    if (provider == SearchProvider.JM && !id.startsWith("jm:")) "jm:$id" else id

/** Immutable parameters for one search operation; never read mutable UI state after launch. */
internal data class SearchRequest(
    val query: String,
    val provider: SearchProvider,
    val order: String,
    val page: Int,
    val generation: Long,
)

/** Monotonic token used to reject results and finally blocks from superseded requests. */
internal class SearchRequestGeneration {
    private var current = 0L

    fun advance(): Long = ++current

    fun isCurrent(generation: Long): Boolean = generation == current
}

fun searchGridColumnsPreferenceKey(provider: SearchProvider, isLandscape: Boolean): String {
    val prefix = if (provider == SearchProvider.MANWA) "search" else "jm_search"
    val orientation = if (isLandscape) "landscape" else "portrait"
    return "${prefix}_grid_columns_$orientation"
}
