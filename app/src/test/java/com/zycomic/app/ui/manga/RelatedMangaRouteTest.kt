package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelatedMangaRouteTest {
    @Test
    fun `JM related cards stay on JM and preserve string album ids`() {
        assertEquals("jm:17", relatedMangaRoute("17", isJm = true))
        assertEquals("jm:12345678901234567890", relatedMangaRoute("12345678901234567890", isJm = true))
        assertEquals("jm:17", relatedMangaRoute("jm:17", isJm = true))
    }

    @Test
    fun `Manwa related navigation remains unchanged`() {
        assertEquals("17", relatedMangaRoute("17", isJm = false))
    }
}
