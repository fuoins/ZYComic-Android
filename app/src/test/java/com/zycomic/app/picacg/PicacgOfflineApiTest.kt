package com.zycomic.app.picacg

import android.content.Context
import com.zycomic.app.net.PicacgRouteManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class PicacgOfflineApiTest {
    private val responses = ArrayDeque<Pair<Int, String>>()
    private val requests = mutableListOf<okhttp3.Request>()
    private var replaceSessionBefore401 = false
    private var clearSessionBeforeResponse = false
    private var failNextCall = false
    private lateinit var offlineHttp: OkHttpClient
    private lateinit var client: PicacgClient

    @BeforeEach
    fun setUp() {
        PicacgAuthStore.initialize(Context())
        PicacgAuthStore.clear()
        PicacgRepository.installClientForTests(null)
        PicacgRouteManager.currentImageHost = "img.example"
        replaceSessionBefore401 = false
        clearSessionBeforeResponse = false
        failNextCall = false
        responses.clear()
        requests.clear()
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            requests += request
            if (failNextCall) {
                failNextCall = false
                throw IOException("simulated offline failure")
            }
            val (status, body) = responses.removeFirst()
            if (status == 401 && replaceSessionBefore401) PicacgAuthStore.clear()
            if (clearSessionBeforeResponse) PicacgAuthStore.clear()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Unauthorized")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        offlineHttp = OkHttpClient.Builder().addInterceptor(interceptor).build()
        client = PicacgClient(
            baseUrl = { "https://api.example/api/v2" },
            token = { "offline-test-token" },
            http = offlineHttp,
            epochSeconds = { 1_700_000_000L },
            nonce = { "0123456789abcdef0123456789abcdef" },
            appUuid = "offline-test-uuid",
        )
    }

    @Test
    fun `configured HTTP and HTTPS API routes are both supported`() = runBlocking {
        for (scheme in listOf("http", "https")) {
            responses += 200 to """{"code":200,"data":{}}"""
            val configured = PicacgClient(baseUrl = { "$scheme://api.example" }, http = offlineHttp)
            configured.request("/categories", authenticated = false)
            assertEquals(scheme, requests.last().url.scheme)
        }
    }

    @Test
    fun `reader popular and latest use distinct explicit sorts`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        repeat(2) { responses += 200 to """{"code":200,"data":{"comics":{"docs":[],"pages":1}}}""" }
        com.zycomic.app.reader.PicacgSource.getPopularManga(1)
        com.zycomic.app.reader.PicacgSource.getLatestUpdates(1)
        assertEquals("vd", requests[0].url.queryParameter("s"))
        assertEquals("dd", requests[1].url.queryParameter("s"))
    }

    @Test
    fun `HMAC covers final encoded URL including API base path and query`() = runBlocking {
        responses += 200 to "{\"code\":200,\"data\":{}}"
        client.request("/comics?q=a%2Fb&page=2", authenticated = false)
        val request = requests.single()
        val url = request.url
        val signedPathAndQuery = url.encodedPath + url.encodedQuery?.let { "?$it" }.orEmpty()
        assertEquals("/api/v2/comics?q=a%2Fb&page=2", signedPathAndQuery)
        assertEquals(PicacgSigner.sign(signedPathAndQuery, "GET", 1_700_000_000L, "0123456789abcdef0123456789abcdef"), request.header("signature"))
    }

    @Test
    fun `injected session token is used on the real mocked request path`() = runBlocking {
        responses += 200 to "{\"code\":200,\"data\":{}}"
        val isolatedClient = PicacgClient(
            baseUrl = { "https://api.example/api/v2" },
            token = { "fallback-token" },
            http = offlineHttp,
            epochSeconds = { 1_700_000_000L },
            nonce = { "0123456789abcdef0123456789abcdef" },
            sessionProvider = { PicacgAuthStore.Session("injected-session-token", 77L) },
            clearSessionIfRevision = { false },
        )
        isolatedClient.request("/private")
        assertEquals("injected-session-token", requests.single().header("authorization"))
    }

    @Test
    fun `response without data is rejected`() = runBlocking {
        responses += 200 to "{\"code\":200,\"message\":\"ok\"}"
        assertThrows(IOException::class.java) { runBlocking { client.request("/categories", authenticated = false) } }
        responses += 200 to "{\"code\":200,\"message\":\"ok\"}"
        assertTrue(client.request("/comments/c1/like", "POST").data.length() == 0)
        Unit
    }

    @Test
    fun `category arrays are parsed and malformed category payload is rejected`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"categories":[{"_id":"a","title":"动作","description":"x"},{"_id":"off","title":"下架","active":false},{"_id":"web","title":"Web","isWeb":true},{"_id":"b","title":"","description":"skip"}]}}"""
        assertEquals(listOf(PicacgCategory("a", "动作", "x")), PicacgRepository.categories())
        responses += 200 to """{"code":200,"data":{}}"""
        assertThrows(IOException::class.java) { runBlocking { PicacgRepository.categories() } }
        Unit
    }

    @Test
    fun `page cache retains hasMore and is isolated by session revision`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"pages":{"docs":[{"_id":"i1","media":{"path":"/p1.jpg","originalName":"p1.jpg"}}],"pages":2}}}"""
        val first = PicacgRepository.pages("comic", 1, 2)
        assertFalse(first.hasMore)
        assertFalse(PicacgRepository.pages("comic", 1, 2).hasMore)
        assertEquals(1, requests.size)
        PicacgAuthStore.clear()
        responses += 200 to """{"code":200,"data":{"pages":{"docs":[],"pages":3}}}"""
        assertTrue(PicacgRepository.pages("comic", 1, 2).hasMore)
        assertEquals(2, requests.size)
    }

    @Test
    fun `favorites request uses requested one based page and sort`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"comics":{"docs":[],"pages":4}}}"""
        val result = PicacgRepository.favourites(3, "da")
        assertEquals(3, result.page)
        assertTrue(result.hasMore)
        assertEquals("/api/v2/users/favourite?page=3&s=da", requests.single().url.encodedPath + "?" + requests.single().url.encodedQuery)
    }

    @Test
    fun `favorites maps records that identify comics with either id or underscore id`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"comics":{"docs":[{"_id":"wire-id","title":"Underscore"},{"id":"plain-id","title":"Plain"}],"pages":1}}}"""

        val result = PicacgRepository.favourites(1)

        assertEquals(listOf("wire-id", "plain-id"), result.items.map { it.id })
    }

    @Test
    fun `profile maps Me fields and today's check-in status`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"user":{"id":"user-id","name":"Reader","email":"reader@example.invalid","avatar":{"path":"avatar/a.jpg"},"slogan":"Hello","title":"Member","gender":"m","birthday":"2000-01-01","level":4,"exp":321,"characters":["cat","fox"],"createdAt":"2024-01-02","isPunched":true,"verified":true}}}"""

        val profile = PicacgRepository.profile()

        assertEquals("user-id", profile.id)
        assertEquals("Reader", profile.name)
        assertEquals("reader@example.invalid", profile.email)
        assertEquals("https://img.example/static/avatar/a.jpg", profile.avatarUrl)
        assertEquals("Hello", profile.slogan)
        assertEquals("Member", profile.title)
        assertEquals("m", profile.gender)
        assertEquals("2000-01-01", profile.birthday)
        assertEquals(4, profile.level)
        assertEquals(321, profile.exp)
        assertEquals(listOf("cat", "fox"), profile.characters)
        assertEquals("2024-01-02", profile.createdAt)
        assertEquals(true, profile.isPunched)
        assertTrue(profile.verified)
    }

    @Test
    fun `profile without a valid check-in flag reports an unknown state`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"user":{"_id":"user-id","name":"Reader","isPunched":"unknown"}}}"""

        val profile = PicacgRepository.profile()

        assertEquals(null, profile.isPunched)
    }

    @Test
    fun `registration sends all eleven fields as an unauthenticated request`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"message":"success","data":null}"""

        PicacgRepository.register(testRegistrationDraft())

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/auth/register", request.url.encodedPath)
        assertEquals(null, request.header("authorization"))
        val payload = org.json.JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(11, payload.length())
        assertEquals("testaccount123", payload.getString("email"))
        assertEquals("f", payload.getString("gender"))
        assertFalse(payload.has("username"))
    }

    @Test
    fun `registration errors are mapped without exposing response text`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 400 to """{"code":400,"error":"1025","message":"sensitive server echo"}"""

        val failure = assertThrows(PicacgRegistrationException::class.java) {
            runBlocking { PicacgRepository.register(testRegistrationDraft()) }
        }

        assertTrue(failure.message.orEmpty().contains("账号或昵称"))
        assertFalse(failure.message.orEmpty().contains("sensitive server echo"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `punch in posts an empty object then reads back authoritative profile status`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{}}"""
        responses += 200 to """{"code":200,"data":{"user":{"_id":"user-id","name":"Reader","isPunched":true}}}"""

        val profile = PicacgRepository.punchIn()

        assertEquals(true, profile.isPunched)
        assertEquals("/api/v2/users/punch-in", requests[0].url.encodedPath)
        assertEquals("{}", Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8())
        assertEquals("/api/v2/users/profile", requests[1].url.encodedPath)
    }

    private fun testRegistrationDraft() = PicacgRegistrationDraft(
        name = "Test Reader",
        username = "testaccount123",
        password = "offline-password",
        question1 = "Question one",
        answer1 = "Answer one",
        question2 = "Question two",
        answer2 = "Answer two",
        question3 = "Question three",
        answer3 = "Answer three",
        birthday = "2000-01-01",
    )

    @Test
    fun `browse serializes all documented filter keys`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"comics":{"docs":[],"pages":1}}}"""
        PicacgRepository.browse(2, category = "romance & school", sort = "ld", tag = "tag", author = "writer", creatorId = "creator", chineseTeam = "team")
        assertEquals("/api/v2/comics?page=2&c=romance+%26+school&t=tag&a=writer&ca=creator&ct=team&s=ld", requests.single().url.encodedPath + "?" + requests.single().url.encodedQuery)
    }

    @Test
    fun `advanced search includes multiple categories and does not retry POST`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"comics":{"docs":[],"pages":1}}}"""
        PicacgRepository.search("keyword", 4, "vd", listOf("romance", "school"))
        val request = requests.single()
        assertEquals("/api/v2/comics/advanced-search?page=4", request.url.encodedPath + "?" + request.url.encodedQuery)
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val bodyJson = org.json.JSONObject(body)
        assertEquals("keyword", bodyJson.getString("keyword"))
        assertEquals("vd", bodyJson.getString("sort"))
        assertEquals("[\"romance\",\"school\"]", bodyJson.getJSONArray("categories").toString())

        responses += 401 to """{"code":401,"message":"expired","data":{}}"""
        assertThrows(IOException::class.java) { runBlocking { PicacgRepository.search("again", 1) } }
        assertEquals(2, requests.size)
    }

    @Test
    fun `search defaults to most likes without changing browse latest default`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        repeat(2) { responses += 200 to """{"code":200,"data":{"comics":{"docs":[],"pages":1}}}""" }

        PicacgRepository.search("title or author", 1)
        PicacgRepository.browse(1)

        val payload = org.json.JSONObject(Buffer().also { requests[0].body!!.writeTo(it) }.readUtf8())
        assertEquals("ld", payload.getString("sort"))
        assertEquals("title or author", payload.getString("keyword"))
        assertEquals(0, payload.getJSONArray("categories").length())
        assertEquals("dd", requests[1].url.queryParameter("s"))
    }

    @Test
    fun `all four favourite sorts are forwarded without altering pagination`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        for (sort in listOf("dd", "da", "ld", "vd")) {
            responses += 200 to """{"code":200,"data":{"comics":{"docs":[{"_id":"$sort","title":"Saved"}],"pages":3}}}"""
            val page = PicacgRepository.favourites(2, sort)
            assertEquals(2, page.page)
            assertTrue(page.hasMore)
            assertEquals(sort, page.items.single().id)
            assertEquals("2", requests.last().url.queryParameter("page"))
            assertEquals(sort, requests.last().url.queryParameter("s"))
        }
        assertEquals(4, requests.size)
    }

    @Test
    fun `ranking tabs use H24 D7 D30 and random while latest remains paginated`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        for (timeframe in listOf("H24", "D7", "D30")) {
            responses += 200 to """{"code":200,"data":{"comics":[{"_id":"$timeframe","title":"Ranked"}]}}"""
            assertEquals(timeframe, PicacgRepository.leaderboard(timeframe).single().id)
            assertEquals("/api/v2/comics/leaderboard", requests.last().url.encodedPath)
            assertEquals(timeframe, requests.last().url.queryParameter("tt"))
            assertEquals("VC", requests.last().url.queryParameter("ct"))
        }
        responses += 200 to """{"code":200,"data":{"comics":[]}}"""
        assertTrue(PicacgRepository.random().isEmpty())
        assertEquals("/api/v2/comics/random", requests.last().url.encodedPath)
        responses += 200 to """{"code":200,"data":{"comics":{"docs":[],"pages":3}}}"""
        assertTrue(PicacgRepository.browse(2, sort = "dd").hasMore)
        assertEquals("/api/v2/comics", requests.last().url.encodedPath)
        assertEquals("dd", requests.last().url.queryParameter("s"))
        assertEquals("2", requests.last().url.queryParameter("page"))
    }

    @Test
    fun `favourite and comic like return state from a same-session detail read`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        val favoritesBefore = PicacgRepository.favouritesRevision.value
        responses += 200 to """{"code":200,"data":{}}"""
        responses += 200 to """{"code":200,"data":{"comic":{"isFavourite":true}}}"""
        assertTrue(PicacgRepository.toggleFavourite("comic-1"))
        assertEquals(favoritesBefore + 1, PicacgRepository.favouritesRevision.value)
        assertEquals("/api/v2/comics/comic-1/favourite", requests[0].url.encodedPath)
        responses += 200 to """{"code":200,"data":{}}"""
        responses += 200 to """{"code":200,"data":{"comic":{"isLiked":false}}}"""
        assertFalse(PicacgRepository.toggleLike("comic-1"))
        assertEquals("/api/v2/comics/comic-1/like", requests[2].url.encodedPath)
    }

    @Test
    fun `toggle readback failure explains state is uncertain and does not repeat POST`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{}}"""
        responses += 200 to """{"code":200,"data":{"comic":{}}}"""
        val failure = assertThrows(IOException::class.java) { runBlocking { PicacgRepository.toggleFavourite("comic-1") } }
        assertTrue(failure.message.orEmpty().contains("may have taken effect"))
        assertEquals(2, requests.size)
    }

    @Test
    fun `write response is rejected if the session changes while request is in flight`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{}}"""
        clearSessionBeforeResponse = true
        assertThrows(IOException::class.java) { runBlocking { PicacgRepository.toggleFavourite("comic-1") } }
        assertEquals(1, requests.size)
        assertEquals("/api/v2/comics/comic-1/favourite", requests.single().url.encodedPath)
    }

    @Test
    fun `failed POST connection is not automatically retried`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        failNextCall = true
        assertThrows(IOException::class.java) { runBlocking { PicacgRepository.toggleCommentLike("c1") } }
        assertEquals(1, requests.size)
    }

    @Test
    fun `comments support top-level pagination replies and sending without POST replay`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"comments":{"docs":[{"_id":"c1","content":"hi","isLiked":false,"likesCount":2,"commentsCount":1,"created_at":"now","_user":{"name":"reader"}}],"pages":2}}}"""
        val first = PicacgRepository.comments("comic-1", 1)
        assertTrue(first.hasMore)
        assertEquals(PicacgComment("c1", "reader", "", "hi", false, 2, 1, "now"), first.items.single())
        responses += 200 to """{"code":200,"data":{"comments":{"docs":[],"pages":1}}}"""
        assertFalse(PicacgRepository.comments("comic-1", 1, replyTo = "c1").hasMore)
        responses += 200 to """{"code":200,"data":{}}"""
        PicacgRepository.sendComment("comic-1", "reply", replyTo = "c1")
        assertEquals("/api/v2/comments/c1", requests.last().url.encodedPath)
        assertEquals(3, requests.size)
    }

    @Test
    fun `random leaderboard recommendations and comment like use documented paths`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"comics":[{"_id":"r1","title":"Random"}]}}"""
        assertEquals("r1", PicacgRepository.random().single().id)
        responses += 200 to """{"code":200,"data":{"comics":[{"_id":"b1","title":"Ranked"}]}}"""
        assertEquals("b1", PicacgRepository.leaderboard("D7").single().id)
        assertEquals("/api/v2/comics/leaderboard?tt=D7&ct=VC", requests.last().url.encodedPath + "?" + requests.last().url.encodedQuery)
        responses += 200 to """{"code":200,"data":{"comics":[]}}"""
        assertTrue(PicacgRepository.recommendations("comic-1").isEmpty())
        responses += 200 to """{"code":200,"data":{}}"""
        PicacgRepository.toggleCommentLike("c1")
        assertEquals("/api/v2/comments/c1/like", requests.last().url.encodedPath)
    }

    @Test
    fun `401 clears the captured session but not a replacement session`() = runBlocking {
        responses += 401 to """{"code":401,"message":"expired","data":{}}"""
        val before = PicacgAuthStore.sessionRevision.value
        assertThrows(IOException::class.java) { runBlocking { client.request("/private") } }
        assertEquals(before + 1, PicacgAuthStore.sessionRevision.value)

        replaceSessionBefore401 = true
        responses += 401 to """{"code":401,"message":"expired","data":{}}"""
        val prior = PicacgAuthStore.sessionRevision.value
        assertThrows(IOException::class.java) { runBlocking { client.request("/private") } }
        assertEquals(prior + 1, PicacgAuthStore.sessionRevision.value)
    }

    @Test
    fun `malformed pagination is rejected but documented empty pages zero is accepted`() = runBlocking {
        PicacgRepository.installClientForTests(client)
        responses += 200 to """{"code":200,"data":{"pages":{"docs":[]}}}"""
        assertThrows(IOException::class.java) { runBlocking { PicacgRepository.pages("comic", 1) } }
        responses += 200 to """{"code":200,"data":{"comics":{"docs":[],"pages":0}}}"""
        val empty = PicacgRepository.browse(1)
        assertFalse(empty.hasMore)
        assertTrue(empty.items.isEmpty())
        responses += 200 to """{"code":200,"data":{"comics":{"docs":[{"_id":"x"}],"pages":0}}}"""
        assertThrows(IOException::class.java) { runBlocking { PicacgRepository.browse(1) } }
        Unit
    }
}
