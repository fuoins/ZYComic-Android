package com.zycomic.app.reader

import org.json.JSONObject
import java.io.IOException

/** Matches Venera's likeComic check on the decrypted body, not the outer API envelope. */
internal object JmLikeResponsePolicy {
    fun requireSuccess(response: JSONObject) {
        val code = response.opt("code")
        if (code !is Number || code.toDouble() != 200.0 || response.opt("status") == "error") {
            throw IOException(response.optString("msg").takeUnless { it.isBlank() || it == "null" } ?: "JM 点赞操作失败")
        }
    }
}
