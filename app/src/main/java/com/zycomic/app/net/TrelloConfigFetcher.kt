package com.zycomic.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object TrelloConfigFetcher {
    private const val BOARD_URL =
        "https://trello.com/1/board/690563d8e49ef9441cbbaab1?cards=visible&card_fields=desc,name"

    private val LINE_PATHS = listOf(
        "data.hosts", "data.lines", "data.api_urls", "data.api_domains", "data.domains",
        "hosts", "lines", "api_urls", "api_domains", "domains"
    )

    suspend fun fetchLines(): List<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(BOARD_URL).get().build()
            val body = NetworkModule.newSpeedTestClient().newCall(req).execute().use { it.body?.string() }
                ?: return@withContext emptyList()
            val cards = JSONObject(body).optJSONArray("cards") ?: return@withContext emptyList()
            val desc = findTestCardDesc(cards) ?: return@withContext emptyList()
            val plain = Crypto.decryptResponse(desc, "0")
            parseLines(JSONObject(plain))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseLines(root: JSONObject): List<String> {
        for (path in LINE_PATHS) {
            val arr = optJSONArrayByPath(root, path) ?: continue
            val lines = parseArray(arr)
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    private fun optJSONArrayByPath(root: JSONObject, path: String): JSONArray? {
        val parts = path.split(".")
        var obj: JSONObject? = root
        for (i in 0 until parts.size - 1) {
            obj = obj?.optJSONObject(parts[i])
        }
        return obj?.optJSONArray(parts.last())
    }

    private fun parseArray(arr: JSONArray): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val url = when (val item = arr.opt(i)) {
                is String -> item
                is JSONObject -> item.optString("url").ifBlank { item.optString("api_url") }.ifBlank { item.optString("domain") }
                else -> null
            }
            if (!url.isNullOrBlank()) result.add(url.trim())
        }
        return result
    }

    private fun findTestCardDesc(cards: JSONArray): String? {
        for (i in 0 until cards.length()) {
            val c = cards.optJSONObject(i) ?: continue
            if (c.optString("name") == "test") return c.optString("desc").ifBlank { null }
        }
        return null
    }
}
