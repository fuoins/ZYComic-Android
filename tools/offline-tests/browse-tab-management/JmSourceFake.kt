package com.zycomic.app.reader

import com.zycomic.app.data.dto.Manga

/** API model surface only; all browse behavior remains the production policy. */
object JmSource {
    data class PromoteSection(
        val id: String,
        val title: String,
        val type: String,
        val slug: String,
        val filterValue: String,
        val items: List<Manga>,
    )

    val orderOptions: List<Pair<String, String>> = listOf(
        "最新" to "mr",
        "总排行" to "mv",
        "月排行" to "mv_m",
        "周排行" to "mv_w",
        "日排行" to "mv_t",
        "最多图片" to "mp",
        "最多喜欢" to "tf",
    )
}
