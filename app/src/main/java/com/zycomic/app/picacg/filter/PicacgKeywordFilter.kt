package com.zycomic.app.picacg.filter

import com.zycomic.app.data.dto.Manga
import java.util.Locale

data class PicacgKeywordPresetGroup(
    val id: String,
    val title: String,
    val keywords: List<String>,
)

data class PicacgKeywordCandidate(
    val title: String,
    val author: String,
    val tags: List<String>,
    val categories: List<String> = emptyList(),
)

/** Pure normalization and substring matching policy for Picacg-only local keyword blocking. */
object PicacgKeywordFilter {
    const val MAX_KEYWORD_LENGTH = 50

    val presetGroups: List<PicacgKeywordPresetGroup> = listOf(
        PicacgKeywordPresetGroup(
            id = "bl_femboy",
            title = "耽美/伪娘",
            keywords = listOf("耽美花園", "偽娘哲學", "扶他樂園", "耽美", "偽娘", "扶他", "BL", "腐向", "男同", "伪娘"),
        ),
        PicacgKeywordPresetGroup(
            id = "ai_art",
            title = "AI作画",
            keywords = listOf(
                "AI作畫", "AI作画", "AI绘画", "AI生成", "AI画作", "Midjourney", "Stable Diffusion", "NovelAI",
                "AI创作", "AI艺术", "(AI)", "（AI）", "AIGC", "AI插画", "AI彩色", "AI上色", "AI漫画", "AI作品",
                "AI繪", "ComfyUI", "LoRA", "Lora", "AI塗色",
            ),
        ),
        PicacgKeywordPresetGroup(
            id = "ntr",
            title = "NTR",
            keywords = listOf("NTR", "牛头人", "绿帽", "寝取", "寝取られ", "Netorare", "出轨", "背叛", "绿化"),
        ),
        PicacgKeywordPresetGroup(
            id = "heavy",
            title = "重口",
            keywords = listOf("SM", "重口地帶", "強暴", "輪姦", "凌辱"),
        ),
    )

    /** Trims, lowercases with Locale.ROOT, and rejects blank or overlong values. */
    fun normalize(keyword: String): String? {
        val normalized = keyword.trim().lowercase(Locale.ROOT)
        return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_KEYWORD_LENGTH }
    }

    /** Returns the normalized value unless it is invalid or already present. */
    fun add(existing: List<String>, keyword: String): List<String>? {
        val normalized = normalize(keyword) ?: return null
        val normalizedExisting = existing.mapNotNull(::normalize)
        if (normalized in normalizedExisting) return null
        return normalizedExisting + normalized
    }

    fun addAll(existing: List<String>, keywords: Iterable<String>): List<String> =
        keywords.fold(existing.mapNotNull(::normalize).distinct()) { result, keyword -> add(result, keyword) ?: result }

    fun remove(existing: List<String>, keyword: String): List<String> {
        val normalized = normalize(keyword) ?: return existing.mapNotNull(::normalize).distinct()
        return existing.mapNotNull(::normalize).distinct().filterNot { it == normalized }
    }

    /** Ordinary substring checks only; Manga.tags already contains the converted categories. */
    fun isBlocked(candidate: PicacgKeywordCandidate, keywords: List<String>): Boolean {
        val normalizedKeywords = keywords.mapNotNull(::normalize).distinct()
        if (normalizedKeywords.isEmpty()) return false
        val fields = buildList {
            add(candidate.title.lowercase(Locale.ROOT))
            add(candidate.author.lowercase(Locale.ROOT))
            addAll(candidate.tags.map { it.lowercase(Locale.ROOT) })
            addAll(candidate.categories.map { it.lowercase(Locale.ROOT) })
        }
        return normalizedKeywords.any { keyword -> fields.any { field -> field.contains(keyword) } }
    }

    fun isBlocked(manga: Manga, keywords: List<String>): Boolean = isBlocked(
        PicacgKeywordCandidate(
            title = manga.name,
            author = manga.author,
            tags = manga.tags.map { it.name },
        ),
        keywords,
    )

    fun <T> filter(items: List<T>, keywords: List<String>, candidate: (T) -> PicacgKeywordCandidate): List<T> =
        if (keywords.isEmpty()) items else items.filterNot { isBlocked(candidate(it), keywords) }

    fun filterManga(items: List<Manga>, keywords: List<String>): List<Manga> =
        if (keywords.isEmpty()) items else items.filterNot { isBlocked(it, keywords) }
}
