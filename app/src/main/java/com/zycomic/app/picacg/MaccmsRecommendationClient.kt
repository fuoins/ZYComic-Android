package com.zycomic.app.picacg

import com.zycomic.app.net.MaccmsPinnedTransport
import com.zycomic.app.net.MaccmsSpeedTester
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Fetches anonymous recommendations from the independent Maccms endpoint. */
class MaccmsRecommendationClient internal constructor(
    private val baseUrl: HttpUrl = PRODUCTION_BASE_URL,
    private val lines3Ip: String? = null,
) {
    /** Returns at most ten distinct, valid comic IDs, excluding [comicId] itself. */
    suspend fun recommendationIds(comicId: String): List<String> {
        require(COMIC_ID.matches(comicId)) { "Invalid comic ID" }
        val url = baseUrl.newBuilder()
            .addPathSegments("picacomic/rec")
            .addPathSegment(comicId)
            .addQueryParameter("limit", "10")
            .build()
        val request = Request.Builder().url(url).get().build()
        val http = if (baseUrl.host in MaccmsSpeedTester.routes) {
            MaccmsPinnedTransport.client(BASE_HTTP, baseUrl.host, lines3Ip)
        } else {
            // Internal constructor permits local transport tests; production uses only fixed macapi hosts.
            BASE_HTTP
        }
        return MaccmsPinnedTransport.request(http, request) { response ->
            if (!response.isSuccessful) throw MaccmsRecommendationHttpException(response.code)
            parseIds(response.body?.string().orEmpty(), comicId)
        }
    }

    private fun parseIds(body: String, selfId: String): List<String> {
        val root = try {
            when {
                body.trimStart().startsWith("[") -> JSONArray(body)
                body.trimStart().startsWith("{") -> parseEnvelope(JSONObject(body))
                else -> throw IOException("Maccms recommendation response is not a JSON array")
            }
        } catch (failure: org.json.JSONException) {
            throw IOException("Invalid Maccms recommendation JSON", failure)
        }

        val seen = mutableSetOf(selfId.lowercase(Locale.ROOT))
        val ids = ArrayList<String>(10)
        for (index in 0 until root.length()) {
            val value = root.opt(index)
            if (value !is String || !COMIC_ID.matches(value)) continue
            if (seen.add(value.lowercase(Locale.ROOT))) ids += value
            if (ids.size == 10) break
        }
        return ids
    }

    private fun parseEnvelope(response: JSONObject): JSONArray {
        val hasCode = response.has("code")
        if (hasCode) {
            val code = response.opt("code")
            if (code !is Number || code.toDouble() != 1.0) {
                throw IOException("Maccms recommendation response indicates failure")
            }
        } else if (response.has("recommendations")) {
            throw IOException("Maccms recommendation response has no success code")
        }

        val key = when {
            response.has("recommendations") -> "recommendations"
            response.has("data") -> "data"
            else -> null
        } ?: throw IOException("Maccms recommendation response has no recommendations array")
        return response.optJSONArray(key)
            ?: throw IOException("Maccms recommendation response has no recommendations array")
    }

    private companion object {
        val BASE_HTTP = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        val PRODUCTION_BASE_URL: HttpUrl = "https://macapi1.com/".toHttpUrl()
        val COMIC_ID = Regex("[0-9a-fA-F]{24}")
    }
}

internal class MaccmsRecommendationHttpException(val statusCode: Int) :
    IOException("Maccms recommendation request failed (HTTP $statusCode)")
