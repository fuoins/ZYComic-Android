package com.zycomic.app.reader

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class JmResponseParserTest {
    @Test
    fun `week skips invalid items without losing valid issues`() {
        val result = JmResponseParser.weekIssues(
            JSONObject("""{"categories":[{"id":"12","title":"第十二期"},{"title":"缺 ID"},null,3,{"id":13}]}"""),
        )
        assertEquals(listOf("12", "13"), result.map { it.id })
        assertEquals(listOf("第十二期", "13"), result.map { it.title })
    }

    @Test
    fun `week missing blank or null titles fall back to issue id`() {
        val result = JmResponseParser.weekIssues(
            JSONObject("""{"categories":[{"id":12},{"id":"13","title":"  "},{"id":14,"title":null}]}"""),
        )
        assertEquals(listOf("12", "13", "14"), result.map { it.title })
    }

    @Test
    fun `week all invalid and empty categories produce no issues`() {
        listOf(
            """{"categories":[]}""",
            """{"categories":[null,{}, {"id":null}, {"id":0}, {"id":true}, {"id":"bad"}]}""",
        ).forEach { assertTrue(JmResponseParser.weekIssues(JSONObject(it)).isEmpty()) }
    }

    @Test
    fun `week malformed categories is a clear retryable error`() {
        listOf("{}", """{"categories":{}}""", """{"categories":null}""").forEach {
            val error = assertThrows(IOException::class.java) { JmResponseParser.weekIssues(JSONObject(it)) }
            assertTrue(error.message!!.contains("categories[]"))
        }
    }

    @Test
    fun `complete series keeps real chapter ids names and sort`() {
        val result = chapters("""[{"id":123,"name":"第一章","sort":"5"},{"id":"456","name":"第二章","sort":6}]""")
        assertEquals(listOf("123", "456"), result.items.map { it.id })
        assertEquals(listOf("第5话 第一章", "第6话 第二章"), result.items.map { it.name })
        assertEquals(listOf(5, 6), result.items.map { it.sort })
        assertNull(result.warning)
    }

    @Test
    fun `missing names and sort use safe display fallbacks`() {
        val result = chapters("""[{"id":123},{"id":"456","name":null,"sort":"bad"}]""")
        assertEquals(listOf("第1话", "第2话"), result.items.map { it.name })
        assertEquals(listOf(1, 2), result.items.map { it.sort })
        assertTrue(result.warning?.contains("缺少名称") != true)
        assertTrue(result.warning!!.contains("2 项缺少有效排序"))
    }

    @Test
    fun `empty names with valid sort are normal chapters without warnings or ID suffixes`() {
        val result = chapters("""[{"id":123,"name":"","sort":1},{"id":456,"name":null,"sort":2}]""")
        assertEquals(listOf("第1话", "第2话"), result.items.map { it.name })
        assertNull(result.warning)
    }

    @Test
    fun `invalid chapters are skipped and returned order keeps original positions`() {
        val result = chapters("""[null,{"id":123},true,{"id":"bad"},{"id":456,"name":"保留"}]""")
        assertEquals(listOf("123", "456"), result.items.map { it.id })
        assertEquals(listOf(2, 5), result.items.map { it.sort })
        assertTrue(result.warning!!.contains("已跳过 3 项"))
    }

    @Test
    fun `empty valid series is not a schema error`() {
        val result = chapters("[]")
        assertTrue(result.items.isEmpty())
        assertNull(result.warning)
    }

    @Test
    fun `explicit empty series uses album id for Venera compatible single episode`() {
        val result = JmResponseParser.chapters(
            JSONObject("""{"series":[]}"""),
            singleChapterAlbumId = "999",
        )
        assertEquals(listOf("999"), result.items.map { it.id })
        assertEquals(listOf("第1话"), result.items.map { it.name })
        assertEquals(listOf(1), result.items.map { it.sort })
        assertNull(result.warning)
    }

    @Test
    fun `chapter image parser accepts Venera filenames and reports empty image lists`() {
        assertEquals(
            listOf("001.jpg", "002.jpg"),
            JmResponseParser.chapterImages(JSONObject("""{"images":[" 001.jpg ","002.jpg"]}""")),
        )
        listOf("{}", """{"images":[]}""", """{"images":[""]}""").forEach {
            val error = assertThrows(IOException::class.java) { JmResponseParser.chapterImages(JSONObject(it)) }
            assertTrue(error.message!!.contains("images"))
        }
    }

    @Test
    fun `malformed series throws instead of fabricating album based chapters`() {
        listOf("{}", """{"id":"999","series":null}""", """{"series":{}}""").forEach {
            val error = assertThrows(IOException::class.java) { JmResponseParser.chapters(JSONObject(it)) }
            assertTrue(error.message!!.contains("series[]"))
            assertTrue(error.message!!.contains("重试"))
        }
        val error = assertThrows(IOException::class.java) {
            JmResponseParser.chapters(JSONObject("{}"), singleChapterAlbumId = "999")
        }
        assertTrue(error.message!!.contains("series[]"))
    }

    @Test
    fun `nonempty series without valid ids is an error not empty success`() {
        val error = assertThrows(IOException::class.java) {
            chapters("""[{"name":"没有 ID","sort":1},null,{"id":null},{"id":0},{"id":-1},{"id":"abc"}]""")
        }
        assertTrue(error.message!!.contains("没有有效章节 ID"))
        assertTrue(error.message!!.contains("重试"))
    }

    @Test
    fun `album detail maps confirmed scalar and related fields`() {
        val details = JmResponseParser.albumDetails(
            JSONObject(
                """{"author":["作者甲","作者乙"],"description":"作品简介","tags":["韩漫",{"id":17,"name":"校园"}],"addtime":1712345678,"total_views":"235","is_favorite":true,"related_list":[{"id":123,"name":"相关作品","author":"作者乙","description":"相关简介"}]}""",
            ),
        )

        assertEquals("作者甲", details.author)
        assertEquals("作品简介", details.description)
        assertEquals(listOf("韩漫", "校园"), details.tags.map { it.name })
        assertEquals(17, details.tags[1].id)
        assertEquals("1712345678", details.addtime)
        assertEquals(235, details.hits)
        assertEquals(1, details.favorite)
        assertEquals("123", details.related.single().id)
        assertEquals("相关作品", details.related.single().name)
        assertEquals("作者乙", details.related.single().author)
        assertEquals("相关简介", details.related.single().description)
    }

    @Test
    fun `album detail accepts scalar author and pipe tags and skips invalid related items`() {
        val details = JmResponseParser.albumDetails(
            JSONObject(
                """{"author":"作者丙","description":null,"tags":"标签一| 标签二 ","is_favorite":0,"related_list":[null,{"id":"bad","name":"无效"},{"id":"456","name":"有效"}]}""",
            ),
        )

        assertEquals("作者丙", details.author)
        assertEquals("", details.description)
        assertEquals(listOf("标签一", "标签二"), details.tags.map { it.name })
        assertEquals(0, details.favorite)
        assertEquals(listOf("456"), details.related.map { it.id })
    }

    @Test
    fun `missing optional album details remain empty defaults`() {
        val details = JmResponseParser.albumDetails(JSONObject("{}"))

        assertEquals("", details.author)
        assertEquals("", details.description)
        assertTrue(details.tags.isEmpty())
        assertEquals("", details.addtime)
        assertEquals(0, details.hits)
        assertEquals(0, details.favorite)
        assertTrue(details.related.isEmpty())
    }

    @Test
    fun `structured and boolean ids cannot become real chapter ids`() {
        val result = chapters("""[{"id":{"id":123}},{"id":true},{"id":[123]},{"id":456}]""")
        assertEquals(listOf("456"), result.items.map { it.id })
        assertTrue(result.warning!!.contains("已跳过 3 项"))
    }

    private fun chapters(series: String) = JmResponseParser.chapters(JSONObject("""{"id":"999","series":$series}"""))
}
