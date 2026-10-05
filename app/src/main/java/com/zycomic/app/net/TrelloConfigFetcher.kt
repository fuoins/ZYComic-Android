package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

object TrelloConfigFetcher {
    private const val BOARD_URL =
        "https://trello.com/1/board/690563d8e49ef9441cbbaab1?cards=visible&card_fields=desc,name"

    /** 从 Trello "test" card 的 desc 解密出线路列表，任何异常返回 emptyList。 */
    suspend fun fetchLines(): List<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(BOARD_URL).get().build()
            val body = NetworkModule.newSpeedTestClient().newCall(req).execute().use { it.body?.string() }
                ?: return@withContext emptyList()
            val cards = org.json.JSONObject(body).optJSONArray("cards") ?: return@withContext emptyList()
            val desc = findTestCardDesc(cards) ?: return@withContext emptyList()
            val plain = Crypto.decryptResponse(desc, "0")
            val hosts = org.json.JSONObject(plain).getJSONObject("data").optJSONArray("hosts")
                ?: return@withContext emptyList()
            (0 until hosts.length()).map { hosts.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findTestCardDesc(cards: JSONArray): String? {
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i) ?: continue
            if (c.optString("name") == "test") return c.optString("desc").ifBlank { null }
        }
        return null
    }
}
