package com.zycomic.app.picacg

import android.content.Context
import com.zycomic.app.net.PicacgRouteManager
import com.zycomic.app.picacg.filter.PicacgBlockKeywordStore
import com.zycomic.app.picacg.filter.PicacgKeywordAddResult
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.ArrayDeque

class PicacgKeywordRepositoryTest {
    private val responses = ArrayDeque<String>()

    @BeforeEach
    fun setUp() {
        PicacgAuthStore.initialize(Context())
        PicacgBlockKeywordStore.initialize(Context())
        PicacgBlockKeywordStore.removeKeyword("block")
        assertEquals(PicacgKeywordAddResult.ADDED, PicacgBlockKeywordStore.addKeyword(" BLOCK "))
        PicacgRepository.installClientForTests(null)
        PicacgRouteManager.currentImageHost = "img.example"
        responses.clear()
        val http = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responses.removeFirst().toResponseBody("application/json".toMediaType()))
                    .build()
            },
        ).build()
        PicacgRepository.installClientForTests(
            PicacgClient(
                baseUrl = { "https://api.example/api/v2" },
                token = { "offline-test-token" },
                http = http,
                epochSeconds = { 1_700_000_000L },
                nonce = { "0123456789abcdef0123456789abcdef" },
                appUuid = "offline-test-uuid",
            ),
        )
    }

    @Test
    fun `browse leaderboard random and recommendations filter but favourites do not and hasMore survives`() = runBlocking {
        val docs = """[{"_id":"hit","title":"A BLOCKed comic"},{"_id":"kept","title":"clean"}]"""
        val paged = """{"code":200,"data":{"comics":{"docs":$docs,"pages":4}}}"""
        responses += paged
        val browse = PicacgRepository.browse(page = 2)
        assertEquals(listOf("kept"), browse.items.map { it.id })
        assertTrue(browse.hasMore)
        assertEquals(2, browse.page)

        responses += paged
        val favourites = PicacgRepository.favourites(page = 2)
        assertEquals(listOf("hit", "kept"), favourites.items.map { it.id })
        assertTrue(favourites.hasMore)

        val collection = """{"code":200,"data":{"comics":[{"_id":"hit","title":"blocked"},{"_id":"kept","title":"clean"}]}}"""
        responses += collection
        assertEquals(listOf("kept"), PicacgRepository.leaderboard().map { it.id })
        responses += collection
        assertEquals(listOf("kept"), PicacgRepository.random().map { it.id })
        responses += collection
        assertEquals(listOf("kept"), PicacgRepository.recommendations("parent").map { it.id })
    }

    @Test
    fun `repository filtering uses author and manga tags which include converted categories`() = runBlocking {
        val data = """{"code":200,"data":{"comics":{"docs":[{"_id":"author-hit","title":"clean","author":"BLOCK author"},{"_id":"category-hit","title":"clean","categories":["block category"]},{"_id":"keep","title":"clean"}],"pages":1}}}"""
        responses += data
        val result = PicacgRepository.browse(page = 1)
        assertEquals(listOf("keep"), result.items.map { it.id })
        assertFalse(result.hasMore)
    }
}
