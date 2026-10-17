package com.zycomic.app.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmImagePolicyTest {

    @Test
    fun `sources2 hosts are deduplicated case insensitively without reordering`() {
        assertEquals(
            listOf("cdn-b.example", "CDN-A.example", "cdn-c.example"),
            JmImagePolicy.deduplicateImageHosts(
                listOf(
                    " cdn-b.example ",
                    "CDN-A.example",
                    "cdn-b.EXAMPLE",
                    "",
                    "cdn-msp1.jmapinodeudzn.net",
                    "cdn-c.example",
                ),
            ),
        )
    }

    @Test
    fun `empty or unavailable static pool falls back in the required order without msp1`() {
        assertEquals(
            listOf(
                "cdn-msp.jmapinodeudzn.net",
                "cdn-msp2.jmapinodeudzn.net",
                "cdn-msp3.jmapinodeudzn.net",
                "cdn-msp4.jmapinodeudzn.net",
            ),
            JmImagePolicy.imageCandidates(emptyList(), emptySet()),
        )
        assertEquals(
            listOf("cdn-msp2.jmapinodeudzn.net", "cdn-msp4.jmapinodeudzn.net"),
            JmImagePolicy.imageCandidates(
                configured = listOf("custom.example"),
                availableHosts = setOf("cdn-msp4.jmapinodeudzn.net", "cdn-msp2.jmapinodeudzn.net"),
            ),
        )
        assertFalse(JmImagePolicy.DEFAULT_IMAGE_DOMAINS.any { it.startsWith("cdn-msp1.") })
    }

    @Test
    fun `healthy configured pool keeps sources2 order and excludes unavailable hosts`() {
        assertEquals(
            listOf("cdn-second.example", "cdn-first.example"),
            JmImagePolicy.imageCandidates(
                configured = listOf("cdn-second.example", "cdn-first.example", "cdn-third.example"),
                availableHosts = setOf("cdn-first.example", "cdn-second.example"),
            ),
        )
    }

    @Test
    fun `scramble threshold is not treated as strip count and extension is ignored`() {
        val path = "/media/photos/340481/00001.webp"
        assertEquals(20, JmImagePolicy.scrambleCount(path))
        assertEquals(20, JmImagePolicy.scrambleCount(path, threshold = 220_980))
        assertEquals(0, JmImagePolicy.scrambleCount(path, threshold = 340_482))
        assertEquals(0, JmImagePolicy.scrambleCount("/media/photos/220979/00001.webp"))
        assertEquals(10, JmImagePolicy.scrambleCount("/media/photos/220980/00001.webp"))
        assertEquals(10, JmImagePolicy.scrambleCount("/media/photos/268849/00001.webp"))
        assertEquals(6, JmImagePolicy.scrambleCount("/media/photos/268850/00001.webp"))
        assertEquals(20, JmImagePolicy.scrambleCount("/media/photos/340481/00001"))
    }

    @Test
    fun `last hexadecimal character code determines strip count and high base is eight`() {
        // MD5("34048100001") ends in c: 'c'.code % 10 gives 9, unlike digitToInt(16) which gives 2.
        assertEquals(20, JmImagePolicy.scrambleCount("/media/photos/340481/00001.jpg"))
        // MD5("42192600001") ends in f: 'f'.code % 8 gives 6.
        assertEquals(14, JmImagePolicy.scrambleCount("/media/photos/421926/00001.webp"))
        // MD5("42192500001") ends in b: 'b'.code % 10 gives 8.
        assertEquals(18, JmImagePolicy.scrambleCount("/media/photos/421925/00001.webp"))
    }

    @Test
    fun `covers are exempt and exact high threshold interval is observable`() {
        assertEquals(0, JmImagePolicy.scrambleCount("/media/albums/421926_3x4.jpg"))
        assertTrue(JmImagePolicy.isScrambleBoundaryChapter(421_925))
        assertTrue(JmImagePolicy.isScrambleBoundaryChapter(421_926))
        assertTrue(JmImagePolicy.isScrambleBoundaryChapter(421_927))
        assertFalse(JmImagePolicy.isScrambleBoundaryChapter(421_924))
        assertFalse(JmImagePolicy.isScrambleBoundaryChapter(421_928))
    }
}
