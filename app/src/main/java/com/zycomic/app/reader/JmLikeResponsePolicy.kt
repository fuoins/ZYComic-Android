package com.zycomic.app.reader

import org.json.JSONObject
import java.io.IOException

/** Validates the decrypted success body for the one-way album-like endpoint. */
internal object JmLikeResponsePolicy {
    fun requireSuccess(response: JSONObject) {
        val code = response.opt("code")
        if (code !is Number || code.toDouble() != 200.0 || response.optString("status") != "success") {
            throw IOException(response.optString("msg").takeUnless { it.isBlank() || it == "null" } ?: "JM 点赞操作失败")
        }
    }
}
