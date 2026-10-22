package com.zycomic.app.reader

import com.zycomic.app.picacg.PicacgClient
import com.zycomic.app.picacg.PicacgRepository
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class PicacgIncrementalReaderTest {
    private val chapter = SChapter.create().apply { url = "/chapter/comic/1" }

    @AfterEach
    fun reset() {
        PicacgRepository.installClientForTests(null)
    }

    @Test
    fun `first manifest is delivered before requesting the next batch`() = runBlocking {
        val requests = mutableListOf<Int>()
        val batches = mutableListOf<Pair<List<String>, Boolean>>()
        install { page ->
            requests += page
            if (page == 2) assertEquals(1, batches.size)
            manifest(page, 2)
        }
        val pages = withContext(
            ReaderPageBatchListener { pages, complete ->
                batches += pages.map { it.url } to complete
                if (batches.size == 1) assertEquals(listOf(1), requests)
            },
        ) { PicacgSource.getPageList(chapter) }

        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(false, true), batches.map { it.second })
        assertEquals(listOf(0, 1), pages.map { it.index })
        assertTrue(pages[0].url.endsWith("1.jpg"))
        assertTrue(pages[1].url.endsWith("2.jpg"))
    }

    @Test
    fun `reader ignores a previously cached complete chapter and fetches its current manifest`() = runBlocking {
        var requests = 0
        install { page ->
            requests++
            if (requests == 1) manifest(9, 1) else manifest(page, 2)
        }
        assertFalse(PicacgRepository.pages("comic", 1, 1).hasMore)
        val batches = mutableListOf<Boolean>()
        val pages = withContext(ReaderPageBatchListener { _, complete -> batches += complete }) {
            PicacgSource.getPageList(chapter)
        }
        assertEquals(3, requests)
        assertEquals(listOf(false, true), batches)
        assertEquals(2, pages.size)
        assertTrue(pages.first().url.endsWith("1.jpg"))
        assertTrue(pages.none { it.url.endsWith("9.jpg") })
    }

    @Test
    fun `later directory failure is still a failure and never declares a partial count final`() {
        val complete = mutableListOf<Boolean>()
        install { page ->
            if (page == 2) throw IOException("offline batch failure")
            manifest(1, 2)
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                withContext(ReaderPageBatchListener { _, done -> complete += done }) {
                    PicacgSource.getPageList(chapter)
                }
            }
        }
        assertEquals(listOf(false), complete)
    }

    @Test
    fun `empty nonfinal batch does not prevent a later visible manifest`() = runBlocking {
        install { page -> if (page == 1) """{"code":200,"data":{"pages":{"docs":[],"pages":2}}}""" else manifest(2, 2) }
        val batches = mutableListOf<Pair<Int, Boolean>>()
        val pages = withContext(ReaderPageBatchListener { pages, done -> batches += pages.size to done }) {
            PicacgSource.getPageList(chapter)
        }
        assertEquals(listOf(0 to false, 1 to true), batches)
        assertEquals(0, pages.single().index)
    }

    @Test
    fun `existing batch limit fails an unfinished reader manifest without declaring a final page count`() {
        var requests = 0
        var finalBatches = 0
        install {
            requests++
            """{"code":200,"data":{"pages":{"docs":[],"pages":1001}}}"""
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                withContext(ReaderPageBatchListener { _, complete -> if (complete) finalBatches++ }) {
                    PicacgSource.getPageList(chapter)
                }
            }
        }
        assertEquals(1000, requests)
        assertEquals(0, finalBatches)
    }

    private fun install(body: (Int) -> String) {
        val http = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val page = request.url.queryParameter("page")!!.toInt()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body(page).toResponseBody("application/json".toMediaType()))
                    .build()
            },
        ).build()
        PicacgRepository.installClientForTests(PicacgClient(baseUrl = { "https://api.example" }, token = { "offline-token" }, http = http))
    }

    private fun manifest(image: Int, pages: Int): String =
        """{"code":200,"data":{"pages":{"docs":[{"_id":"$image","media":{"path":"/$image.jpg","fileServer":"https://fresh.example"}}],"pages":$pages}}}"""
}
