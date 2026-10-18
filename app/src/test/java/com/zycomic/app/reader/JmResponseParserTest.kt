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
        assertEquals(listOf("第一章", "第二章"), result.items.map { it.name })
        assertEquals(listOf(5, 6), result.items.map { it.sort })
        assertNull(result.warning)
    }

    @Test
    fun `missing names and sort use safe display fallbacks`() {
        val result = chapters("""[{"id":123},{"id":"456","name":null,"sort":"bad"}]""")
        assertEquals(listOf("章节 123", "章节 456"), result.items.map { it.name })
        assertEquals(listOf(1, 2), result.items.map { it.sort })
        assertTrue(result.warning!!.contains("2 项缺少名称"))
        assertTrue(result.warning!!.contains("2 项缺少有效排序"))
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
    fun `malformed series throws instead of fabricating album based chapters`() {
        listOf("{}", """{"id":"999","series":null}""", """{"series":{}}""").forEach {
            val error = assertThrows(IOException::class.java) { JmResponseParser.chapters(JSONObject(it)) }
            assertTrue(error.message!!.contains("series[]"))
            assertTrue(error.message!!.contains("重试"))
        }
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
    fun `structured and boolean ids cannot become real chapter ids`() {
        val result = chapters("""[{"id":{"id":123}},{"id":true},{"id":[123]},{"id":456}]""")
        assertEquals(listOf("456"), result.items.map { it.id })
        assertTrue(result.warning!!.contains("已跳过 3 项"))
    }

    private fun chapters(series: String) = JmResponseParser.chapters(JSONObject("""{"id":"999","series":$series}"""))
}
