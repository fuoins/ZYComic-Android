package com.zycomic.app.picacg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** Returns at most ten distinct, valid comic IDs, excluding [comicId] itself. */
    suspend fun recommendationIds(comicId: String): List<String> {
        require(COMIC_ID.matches(comicId)) { "Invalid comic ID" }
        return withContext(Dispatchers.IO) {
            val url = baseUrl.newBuilder()
                .addPathSegments("maccms/json/baofeng/picacomic/rec")
                .addPathSegment(comicId)
                .addQueryParameter("limit", "10")
                .build()
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw MaccmsRecommendationHttpException(response.code)
                parseIds(response.body?.string().orEmpty(), comicId)
            }
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
        // The documented endpoint uses code=0 and a top-level recommendations array, not data.
        if (response.has("code") && response.opt("code") != 0) {
            throw IOException("Maccms recommendation response indicates failure")
        }
        if (response.has("recommendations")) {
            if (response.opt("code") != 0) throw IOException("Maccms recommendation response has no success code")
            return response.optJSONArray("recommendations")
                ?: throw IOException("Maccms recommendation response has no recommendations array")
        }
        // Retain support for the legacy envelope without confusing it with documented responses.
        return response.optJSONArray("data")
            ?: throw IOException("Maccms recommendation response has no recommendations array")
    }

    private companion object {
        val PRODUCTION_BASE_URL: HttpUrl = "https://macapi1.com/".toHttpUrl()
        val COMIC_ID = Regex("[0-9a-fA-F]{24}")
    }
}

internal class MaccmsRecommendationHttpException(val statusCode: Int) :
    IOException("Maccms recommendation request failed (HTTP $statusCode)")
