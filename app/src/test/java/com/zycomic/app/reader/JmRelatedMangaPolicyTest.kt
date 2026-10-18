package com.zycomic.app.reader

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmRelatedMangaPolicyTest {
    @Test
    fun `setting image host accepts the documented dynamic https host`() {
        assertEquals(
            "https://cdn.example.test",
            JmRelatedMangaPolicy.settingImageHost(JSONObject("""{"img_host":"https://cdn.example.test/"}""")),
        )
        assertEquals(
            "https://cdn.example.test",
            JmRelatedMangaPolicy.settingImageHost(JSONObject("""{"data":{"img_host":"https://cdn.example.test"}}""")),
        )
        assertNull(JmRelatedMangaPolicy.settingImageHost(JSONObject("""{"img_host":"http://cdn.example.test"}""")))
        assertNull(JmRelatedMangaPolicy.settingImageHost(JSONObject("""{"img_host":"not a url"}""")))
    }

    @Test
    fun `cover url uses current detail addtime as version`() {
        assertEquals(
            "https://cdn.example.test/media/albums/17_3x4.jpg?v=1712345678",
            JmRelatedMangaPolicy.coverUrl("https://cdn.example.test", "17", "1712345678"),
        )
        assertNull(JmRelatedMangaPolicy.coverUrl("https://cdn.example.test", "bad", "1712345678"))
    }

    @Test
    fun `settings host cache expires and is scoped to route and configuration`() {
        var now = 100L
        val cache = JmRelatedMangaPolicy.ImageHostCache(ttlMillis = 50L, clockMillis = { now })
        val firstRoute = JmRelatedMangaPolicy.cacheKey("https://api-a.test/", "v1|line0")
        val switchedRoute = JmRelatedMangaPolicy.cacheKey("https://api-b.test", "v1|line0")
        val updatedConfig = JmRelatedMangaPolicy.cacheKey("https://api-a.test", "v2|line0")

        cache.put(firstRoute, "https://cdn-a.test")
        assertEquals("https://cdn-a.test", cache.get(firstRoute))
        assertNull(cache.get(switchedRoute))
        assertNull(cache.get(updatedConfig))
        now += 50L
        assertNull(cache.get(firstRoute))
    }

    @Test
    fun `album request tries requested dynamic host before unchanged configured fallbacks`() {
        val candidates = JmRelatedMangaPolicy.imageHostsForAlbum(
            "cdn-dynamic.test",
            listOf("cdn-active.test", "cdn-fallback.test", "CDN-DYNAMIC.TEST"),
        )

        assertEquals(listOf("cdn-dynamic.test", "cdn-active.test", "cdn-fallback.test"), candidates)
        assertTrue(JmRelatedMangaPolicy.imageHostsForAlbum("", listOf("fallback.test")).isNotEmpty())
    }
}
