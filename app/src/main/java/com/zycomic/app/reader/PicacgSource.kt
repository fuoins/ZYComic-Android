package com.zycomic.app.reader

import com.zycomic.app.net.PicacgImageFallbackInterceptor
import com.zycomic.app.net.PicacgRouteManager
import com.zycomic.app.picacg.PicacgRepository
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPageLoadStatus
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.zycomic.app.data.dto.Manga as MangaDto

/** Picacg's reader adapter never borrows Manwa/JM clients, source IDs, caches, or route state. */
object PicacgSource : HttpSource() {
    override val name: String = "哔咔"
    override val lang: String = "zh"
    override val supportsLatest: Boolean = true
    override val baseUrl: String get() = PicacgRouteManager.apiBaseUrl
    override val client: OkHttpClient get() = imageClient
    override val headers: Headers = Headers.Builder().add("User-Agent", "okhttp/3.8.1").build()

    private val imageClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .dns(PicacgRouteManager.dns())
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            // A reader page that fails on the fastest mirror automatically retries the other roots.
            .addInterceptor(PicacgImageFallbackInterceptor())
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    private class CategoryFilter : Filter.Text("Picacg 分类（可选）")
    private class SortFilter : Filter.Select<String>("Picacg 排序", SORT_LABELS.toTypedArray(), 0)

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(), SortFilter())

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = PicacgRepository.browse(page, sort = "vd")
        return MangasPage(response.items.map(::toSManga), response.hasMore)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = PicacgRepository.browse(page, sort = "dd")
        return MangasPage(response.items.map(::toSManga), response.hasMore)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.state.orEmpty()
        val sortState = filters.filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0
        val sort = SORT_VALUES.getOrElse(sortState) { "dd" }
        val result = if (query.isNotBlank()) {
            PicacgRepository.search(query, page, sort)
        } else {
            PicacgRepository.browse(page, category.takeIf(String::isNotBlank), sort)
        }
        return MangasPage(result.items.map(::toSManga), result.hasMore)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url.substringAfterLast('/').substringBefore('?')
        val dto = PicacgRepository.detail(mangaId)
        val updatedManga = toSManga(dto).apply { url = "/comic/$mangaId" }
        val updatedChapters = dto.chapterList.mapNotNull { item ->
            val order = item.sort.takeIf { it > 0 } ?: return@mapNotNull null
            if (item.id.isBlank() || item.name.isBlank()) return@mapNotNull null
            SChapter.create().apply {
                url = "/chapter/$mangaId/$order"
                name = item.name
                chapter_number = order.toFloat()
                date_upload = 0L
            }
        }
        if (updatedChapters.isEmpty()) throw IOException("Picacg comic contains no valid episodes")
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.trim('/').split('/')
        require(parts.size == 3 && parts[0] == "chapter") { "Invalid Picacg chapter URL" }
        val comicId = parts[1]
        val order = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: throw IOException("Invalid Picacg chapter order")
        val images = mutableListOf<com.zycomic.app.picacg.PicacgImage>()
        var page = 1
        while (page <= 1000) {
            val response = PicacgRepository.pages(comicId, order, page)
            images += response.items
            reportPicacgPageListProgress(page, images.size, response.hasMore)
            if (!response.hasMore) break
            page++
        }
        return images.mapIndexed { index, image -> Page(index, image.url, image.url) }
    }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl?.takeIf(String::isNotBlank)
        ?: page.url.takeIf(String::isNotBlank) ?: throw IOException("Picacg page has no URL")

    override suspend fun getImage(page: Page): Response {
        val request = Request.Builder().url(PicacgRouteManager.rewriteImageUrl(getImageUrl(page))).headers(headers).get().build()
        val call = client.newCall(request)
        // KMK --> UI observation only: keep this source's client, URL and request policy unchanged.
        val readerPage = page as? ReaderPage
        val generation = readerPage?.generation?.get()
        val attempt = readerPage?.activeAttempt?.get()
        fun isCurrent(): Boolean = readerPage != null && readerPage.generation.get() == generation &&
            readerPage.activeAttempt.get() === attempt && (attempt == null || attempt.isCurrent)
        if (readerPage != null) {
            synchronized(readerPage) {
                if (isCurrent()) {
                    readerPage.progress = 0
                    readerPage.loadStatus = ReaderPageLoadStatus.PicacgRequesting(readerPage.manualRetryCount.get())
                }
            }
        }
        val response = call.awaitSuccess()
        if (readerPage == null) return response
        return response.withPicacgImageProgress(::isCurrent) { progress ->
            synchronized(readerPage) {
                if (isCurrent()) {
                    readerPage.progress = progress.percentage ?: -1
                    readerPage.loadStatus = ReaderPageLoadStatus.PicacgReceiving(
                        progress.bytesRead,
                        progress.contentLength,
                        progress.percentage,
                    )
                }
            }
        }
        // KMK <--
    }

    private fun toSManga(manga: MangaDto) = SManga.create().apply {
        url = "/comic/${manga.id}"
        title = manga.name
        thumbnail_url = manga.picx.takeIf(String::isNotBlank)
        author = manga.author
        description = manga.text
        genre = manga.tags.map { it.name }.distinct().joinToString(", ")
        status = if (manga.state.contains("完") || manga.end.contains("完")) SManga.COMPLETED else SManga.ONGOING
        initialized = true
    }

    private val SORT_VALUES = listOf("dd", "da", "ld", "vd")
    private val SORT_LABELS = listOf("最新", "时间升序", "最多点赞", "最多浏览")
}
