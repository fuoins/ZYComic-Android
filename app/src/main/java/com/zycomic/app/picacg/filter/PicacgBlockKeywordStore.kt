package com.zycomic.app.picacg.filter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Result exposed to the manager UI so invalid entries and duplicates can be explained separately. */
enum class PicacgKeywordAddResult { ADDED, INVALID, DUPLICATE }

/** Picacg-only, device-local keyword persistence; this namespace is not shared with other platforms. */
object PicacgBlockKeywordStore {
    private const val PREFERENCES_NAME = "picacg_block_keywords"
    private const val KEYWORDS_KEY = "keywords"
    private val lock = Any()

    @Volatile private var preferences: android.content.SharedPreferences? = null

    private val _keywords = MutableStateFlow<List<String>>(emptyList())
    val keywords: StateFlow<List<String>> = _keywords.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Safe to call from app startup or the management screen; works without a Picacg login. */
    fun initialize(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            preferences = prefs
            _keywords.value = decode(prefs.getString(KEYWORDS_KEY, null))
        }
    }

    fun addKeyword(keyword: String): PicacgKeywordAddResult = synchronized(lock) {
        val normalized = PicacgKeywordFilter.normalize(keyword) ?: return PicacgKeywordAddResult.INVALID
        if (normalized in _keywords.value) return PicacgKeywordAddResult.DUPLICATE
        persist(_keywords.value + normalized)
        PicacgKeywordAddResult.ADDED
    }

    /** Adds all valid non-duplicate terms from a preset group; returns the number newly added. */
    fun addKeywords(keywords: Iterable<String>): Int = synchronized(lock) {
        val updated = PicacgKeywordFilter.addAll(_keywords.value, keywords)
        val added = updated.size - _keywords.value.size
        if (added > 0) persist(updated)
        added
    }

    fun removeKeyword(keyword: String) = synchronized(lock) {
        val updated = PicacgKeywordFilter.remove(_keywords.value, keyword)
        if (updated != _keywords.value) persist(updated)
    }

    private fun persist(updated: List<String>) {
        preferences?.edit()?.putString(KEYWORDS_KEY, JSONArray(updated).toString())?.apply()
        _keywords.value = updated
        _revision.value += 1L
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { PicacgKeywordFilter.normalize(array.optString(it)) }.distinct()
        }.getOrDefault(emptyList())
    }
}
