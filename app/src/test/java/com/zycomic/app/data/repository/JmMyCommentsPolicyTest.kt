package com.zycomic.app.data.repository

import com.zycomic.app.reader.JmResponseParser
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmMyCommentsPolicyTest {
    @Test
    fun `profile comments request is read only and uses the authenticated numeric uid`() {
        val request = JmMyCommentsPolicy.request(uid = "814955", page = 1)
        assertEquals("GET", request.method)
        assertEquals("/forum", request.path)
        assertEquals(mapOf("uid" to "814955", "page" to "1"), request.queryParameters)
        assertEquals(mapOf("uid" to "814955", "page" to "3"), JmMyCommentsPolicy.request("814955", 3).queryParameters)
        assertThrows(IllegalArgumentException::class.java) { JmMyCommentsPolicy.request("", 1) }
        assertThrows(IllegalArgumentException::class.java) { JmMyCommentsPolicy.request("username", 1) }
        assertThrows(IllegalArgumentException::class.java) { JmMyCommentsPolicy.request("000", 1) }
        assertThrows(IllegalArgumentException::class.java) { JmMyCommentsPolicy.request("814955", 0) }
    }

    @Test
    fun `page size and total bound forward pagination`() {
        assertEquals(10, JmMyCommentsPolicy.PAGE_SIZE)
        assertTrue(JmMyCommentsPolicy.hasMore(total = 11, itemCount = 10, page = 1))
        assertFalse(JmMyCommentsPolicy.hasMore(total = 11, itemCount = 1, page = 2))
        assertFalse(JmMyCommentsPolicy.hasMore(total = 0, itemCount = 0, page = 1))
        assertTrue(JmMyCommentsPolicy.hasMore(total = null, itemCount = 10, page = 1))
        assertFalse(JmMyCommentsPolicy.hasMore(total = null, itemCount = 9, page = 1))
    }

    @Test
    fun `session revision mismatch blocks stale account page delivery`() {
        assertTrue(JmMyCommentsPolicy.isCurrentSession(requestRevision = 7, currentRevision = 7))
        assertFalse(JmMyCommentsPolicy.isCurrentSession(requestRevision = 7, currentRevision = 8))
    }

    @Test
    fun `HTTP and API failures remain errors including expired login`() {
        assertEquals(null, JmMyCommentsPolicy.responseError(true, 200, null))
        assertEquals("JM 登录已失效，请重新登录", JmMyCommentsPolicy.responseError(false, 401, null))
        assertEquals("JM 留言记录请求失败（HTTP 503）", JmMyCommentsPolicy.responseError(false, 503, null))
        assertEquals("JM 留言记录请求失败：session expired", JmMyCommentsPolicy.responseError(true, 200, "session expired"))
    }

    @Test
    fun `existing forum comment parser retains text and exposes linked manga fields`() {
        val page = JmResponseParser.forumPage(
            JSONObject(
                """{"total":"11","list":[{"CID":"99","parent_CID":"0","aid":"1480008","name":"作品名","addtime":"2026-01-02","content":"<p>留言正文</p>"}]}""",
            ),
        )
        val comment = page.comments.single()
        assertEquals(11, page.total)
        assertEquals("1480008", comment.mangaId)
        assertEquals("作品名", comment.mangaName)
        assertEquals("留言正文", comment.content)
        assertEquals("2026-01-02", comment.time)
    }
}
