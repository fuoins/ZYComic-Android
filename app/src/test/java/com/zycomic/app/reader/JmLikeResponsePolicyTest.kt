package com.zycomic.app.reader

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class JmLikeResponsePolicyTest {
    @Test
    fun `only numeric code 200 and explicit success status confirm a like`() {
        JmLikeResponsePolicy.requireSuccess(JSONObject("{\"code\":200,\"status\":\"success\"}"))
    }

    @Test
    fun `explicit error fails even with code 200 and preserves server message`() {
        val error = assertThrows(IOException::class.java) {
            JmLikeResponsePolicy.requireSuccess(JSONObject("{\"code\":200,\"status\":\"error\",\"msg\":\"denied\"}"))
        }
        assertEquals("denied", error.message)
    }

    @Test
    fun `unknown statuses fail even with code 200`() {
        listOf("ok", "fail", "error", "", "unknown").forEach { status ->
            assertThrows(IOException::class.java) {
                JmLikeResponsePolicy.requireSuccess(JSONObject().put("code", 200).put("status", status))
            }
        }
    }

    @Test
    fun `success never overrides missing malformed or rejected code`() {
        listOf("success").forEach { status ->
            listOf<Any>(199, 201, 400, 401, 500, 200.5, "200", "bad", true, JSONObject.NULL).forEach { code ->
                assertThrows(IOException::class.java) {
                    JmLikeResponsePolicy.requireSuccess(JSONObject().put("code", code).put("status", status))
                }
            }
            assertThrows(IOException::class.java) { JmLikeResponsePolicy.requireSuccess(JSONObject().put("status", status)) }
        }
    }

    @Test
    fun `missing blank and null failure messages use safe fallback`() {
        listOf("{}", "{\"msg\":\"\"}", "{\"msg\":null}").forEach { json ->
            val error = assertThrows(IOException::class.java) { JmLikeResponsePolicy.requireSuccess(JSONObject(json)) }
            assertEquals("JM 点赞操作失败", error.message)
        }
    }
}
