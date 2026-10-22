package com.zycomic.app.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderDetailRouteTest {
    @Test
    fun `reader database URLs resolve to current detail IDs with platform preserved`() {
        assertEquals("123", ReaderDetailRoute.bookId(ManwaSource.id, "/manga/123"))
        assertEquals("jm:123", ReaderDetailRoute.bookId(JmSource.id, "/album/123"))
        assertEquals("picacg:abc123", ReaderDetailRoute.bookId(PicacgSource.id, "/comic/abc123"))
    }

    @Test
    fun `trailing slash query and fragment do not become part of the remote ID`() {
        assertEquals("123", ReaderDetailRoute.bookId(ManwaSource.id, "/manga/123/?from=reader"))
        assertEquals("jm:456", ReaderDetailRoute.bookId(JmSource.id, "/album/456#chapter"))
        assertEquals("picacg:abc123", ReaderDetailRoute.bookId(PicacgSource.id, "/comic/abc123?chapter=2"))
    }

    @Test
    fun `wrong platform malformed URLs and missing remote IDs do not open another book`() {
        assertNull(ReaderDetailRoute.bookId(ManwaSource.id, "/album/123"))
        assertNull(ReaderDetailRoute.bookId(JmSource.id, "/comic/123"))
        assertNull(ReaderDetailRoute.bookId(PicacgSource.id, "/manga/123"))
        assertNull(ReaderDetailRoute.bookId(PicacgSource.id, "/comic/"))
        assertNull(ReaderDetailRoute.bookId(PicacgSource.id, "/comic/123/extra"))
        assertNull(ReaderDetailRoute.bookId(ManwaSource.id, "123"))
        assertNull(ReaderDetailRoute.bookId(Long.MIN_VALUE, "/manga/123"))
    }
}
