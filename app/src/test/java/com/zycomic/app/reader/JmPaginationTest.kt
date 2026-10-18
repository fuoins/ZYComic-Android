package com.zycomic.app.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmPaginationTest {
    @Test
    fun `search pagination follows total rather than short response length`() {
        assertTrue(JmPagination.searchHasMore(page = 4, total = 397))
        assertFalse(JmPagination.searchHasMore(page = 5, total = 397))
        assertFalse(JmPagination.searchHasMore(page = 1, total = 0))
        assertTrue(JmPagination.searchHasMore(page = 125, total = 10_001))
    }

    @Test
    fun `category pagination stops on duplicate first item and at hard cap`() {
        assertTrue(JmPagination.categoryHasMore(page = 1, total = 10_000, firstId = "a", previousFirstId = null))
        assertFalse(JmPagination.categoryHasMore(page = 2, total = 10_000, firstId = "z", previousFirstId = "z"))
        assertFalse(JmPagination.categoryHasMore(page = 125, total = 10_000, firstId = "z", previousFirstId = null))
        assertFalse(JmPagination.categoryHasMore(page = 2, total = 100, firstId = null, previousFirstId = null))
    }

    @Test
    fun `promote pagination stops on repeat and hard cap`() {
        assertTrue(JmPagination.cappedListHasMore(page = 1, itemCount = 80, firstId = "a", previousFirstId = null))
        assertFalse(JmPagination.cappedListHasMore(page = 2, itemCount = 80, firstId = "z", previousFirstId = "z"))
        assertFalse(JmPagination.cappedListHasMore(page = 125, itemCount = 80, firstId = "z", previousFirstId = null))
        assertFalse(JmPagination.cappedListHasMore(page = 1, itemCount = 0, firstId = null, previousFirstId = null))
    }

    @Test
    fun `weekly page total uses twenty items per page`() {
        assertEquals(20, JmPagination.WEEK_ITEMS_PER_PAGE)
        assertTrue(JmPagination.totalHasMore(page = 1, total = 21, itemsPerPage = JmPagination.WEEK_ITEMS_PER_PAGE))
        assertFalse(JmPagination.totalHasMore(page = 1, total = 20, itemsPerPage = JmPagination.WEEK_ITEMS_PER_PAGE))
        assertFalse(JmPagination.totalHasMore(page = 2, total = 21, itemsPerPage = JmPagination.WEEK_ITEMS_PER_PAGE))
    }

    @Test
    fun `serialization page total uses forty items per page`() {
        assertEquals(40, JmPagination.SERIALIZATION_ITEMS_PER_PAGE)
        assertTrue(JmPagination.totalHasMore(page = 1, total = 41, itemsPerPage = JmPagination.SERIALIZATION_ITEMS_PER_PAGE))
        assertFalse(JmPagination.totalHasMore(page = 1, total = 40, itemsPerPage = JmPagination.SERIALIZATION_ITEMS_PER_PAGE))
        assertFalse(JmPagination.totalHasMore(page = 2, total = 41, itemsPerPage = JmPagination.SERIALIZATION_ITEMS_PER_PAGE))
    }
}
