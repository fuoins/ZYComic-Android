package com.zycomic.app.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JmBrowseParametersTest {
    @Test
    fun `week filter starts at page one and never includes category order`() {
        assertEquals(
            mapOf("id" to "262", "type" to "manga", "page" to "1"),
            JmBrowseParameters.weekFilter("262", "manga", 0),
        )
        assertEquals("2", JmBrowseParameters.weekFilter("262", "hanman", 2)["page"])
    }

    @Test
    fun `serialization request contains only type date and one based page`() {
        assertEquals(
            mapOf("type" to "all", "date" to "7", "page" to "1"),
            JmBrowseParameters.serialization("all", 7, 0),
        )
        assertEquals("manga", JmBrowseParameters.serialization("manga", 0, 2)["type"])
        assertEquals("2", JmBrowseParameters.serialization("hanman", 3, 2)["page"])
    }
}
