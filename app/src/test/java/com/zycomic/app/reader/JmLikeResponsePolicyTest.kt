package com.zycomic.app.reader

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class JmLikeResponsePolicyTest {
    @Test
    fun `numeric code 200 accepts every non error status just like Venera`() {
        listOf("success", "ok", "fail", "", "unknown").forEach { status ->
            assertDoesNotThrow { JmLikeResponsePolicy.requireSuccess(JSONObject().put("code", 200).put("status", status)) }
        }
        assertDoesNotThrow { JmLikeResponsePolicy.requireSuccess(JSONObject("{\"code\":200}")) }
        assertDoesNotThrow { JmLikeResponsePolicy.requireSuccess(JSONObject("{\"code\":200,\"status\":null}")) }
    }

    @Test
    fun `explicit error fails even with code 200 and preserves server message`() {
        val error = assertThrows(IOException::class.java) {
            JmLikeResponsePolicy.requireSuccess(JSONObject("{\"code\":200,\"status\":\"error\",\"msg\":\"denied\"}"))
        }
        assertEquals("denied", error.message)
    }

    @Test
    fun `success and ok never override missing malformed or rejected code`() {
        listOf("success", "ok", "error", "").forEach { status ->
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
