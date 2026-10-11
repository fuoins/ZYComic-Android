package com.zycomic.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

internal object PlatformRankRepo {

    suspend fun fetch(platform: PlatformMeta, subId: String): List<PlatformRankItem> =
        withContext(Dispatchers.IO) {
            val sub = if (platform.isValidSub(subId)) subId else platform.defaultSub
            when (platform.id) {
                PlatformId.BILIBILI -> fetchBilibili(sub)
                PlatformId.TENCENT -> fetchTencent(sub)
                PlatformId.KUAIKAN -> fetchKuaikan(sub)
            }
        }

    private fun httpGet(url: String, block: okhttp3.Request.Builder.() -> Unit = {}): String {
        val req = Request.Builder().url(url).apply(block).build()
        PlatformNet.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun fetchBilibili(subId: String): List<PlatformRankItem> {
        val html = httpGet("https://manga.bilibili.com/ranking/$subId?from=manga_home")
        val doc = Jsoup.parse(html)
        val items = doc.select("*[class*=_rank-item_]")
        val out = ArrayList<PlatformRankItem>(items.size.coerceAtMost(50))
        items.take(50).forEachIndexed { idx, el ->
            val title = el.selectFirst("a[title]")?.attr("title")
                ?: el.selectFirst("img")?.attr("alt").orEmpty()
            val cover = el.selectFirst("img[src*=@204w]")?.attr("src").orEmpty()
                .let { fixBiliCover(it) }
            out.add(
                PlatformRankItem(
                    rank = idx + 1,
                    title = title.trim(),
                    coverUrl = cover,
                    externalId = "",
                )
            )
        }
        return out
    }

    private fun fixBiliCover(src: String): String {
        if (src.isEmpty()) return src
        if (!src.contains("@")) return src
        val lower = src.substringBefore("@").lowercase()
        if (lower.endsWith(".avif") || lower.endsWith(".webp")) {
            return lower + "@204w.png"
        }
        return src
    }

    private fun fetchTencent(subId: String): List<PlatformRankItem> {
        val type = if (subId in setOf("new", "hot")) subId else "pay"
        val maxPage = if (type == "pay") 10 else 3
        val referer = when (type) {
            "new" -> "https://m.ac.qq.com/rank/xinzuo"
            "hot" -> "https://m.ac.qq.com/rank/zhenxiang"
            else -> "https://m.ac.qq.com/rank/changxiao"
        }
        val out = ArrayList<PlatformRankItem>(maxPage * 10)
        for (page in 1..maxPage) {
            val url = "https://m.ac.qq.com/rank/index?_t=${System.currentTimeMillis()}" +
                "&type=$type&page=$page&pageSize=10&style=items"
            val html = httpGet(url) {
                header("X-Requested-With", "XMLHttpRequest")
                header("Referer", referer)
            }
            val doc = Jsoup.parse(html)
            val links = doc.select("a.comic-link")
            if (links.isEmpty()) break
            links.forEach { a ->
                val href = a.attr("href")
                val id = Regex("/comic/index/id/(\\d+)").find(href)?.groupValues?.getOrNull(1).orEmpty()
                val cover = a.selectFirst("img.comic-cover, img.cover-image")?.absUrl("src").orEmpty()
                val title = a.selectFirst("strong.comic-title")?.text().orEmpty()
                out.add(
                    PlatformRankItem(
                        rank = out.size + 1,
                        title = title.trim(),
                        coverUrl = cover,
                        externalId = id,
                    )
                )
            }
        }
        return out
    }

    private fun fetchKuaikan(subId: String): List<PlatformRankItem> {
        val validIds = PlatformCatalog.platforms
            .first { it.id == PlatformId.KUAIKAN }
            .subs.map { it.id }.toSet()
        val id = if (subId in validIds) subId else "9"
        val body = httpGet(
            "https://m.kuaikanmanhua.com/mini/v1/comic/mkuaikan/rank_list?since=0&limit=50&need_ranks=true&rank_id=$id"
        )
        val root = JSONObject(body)
        val topics = root.optJSONObject("data")?.optJSONArray("topics") ?: return emptyList()
        val out = ArrayList<PlatformRankItem>(topics.length())
        for (i in 0 until topics.length()) {
            val t = topics.optJSONObject(i) ?: continue
            out.add(
                PlatformRankItem(
                    rank = i + 1,
                    title = t.optString("title").trim(),
                    coverUrl = t.optString("image_url"),
                    externalId = t.opt("id")?.toString().orEmpty(),
                )
            )
        }
        return out
    }
}
