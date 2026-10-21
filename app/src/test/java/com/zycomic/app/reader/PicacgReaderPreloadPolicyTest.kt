package com.zycomic.app.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PicacgReaderPreloadPolicyTest {
    @Test
    fun `Picacg look-ahead is capped at three pages without discarding explicit smaller settings`() {
        assertEquals(0, PicacgReaderPreloadPolicy.taskLimit(0, isPicacg = true, readerThreadsEnabled = true))
        assertEquals(2, PicacgReaderPreloadPolicy.taskLimit(2, isPicacg = true, readerThreadsEnabled = true))
        assertEquals(3, PicacgReaderPreloadPolicy.taskLimit(3, isPicacg = true, readerThreadsEnabled = true))
        assertEquals(3, PicacgReaderPreloadPolicy.taskLimit(8, isPicacg = true, readerThreadsEnabled = true))
    }

    @Test
    fun `disabled reader threads disable look-ahead and non Picacg keeps existing cap`() {
        assertEquals(0, PicacgReaderPreloadPolicy.taskLimit(8, isPicacg = true, readerThreadsEnabled = false))
        assertEquals(8, PicacgReaderPreloadPolicy.taskLimit(8, isPicacg = false, readerThreadsEnabled = true))
        assertEquals(10, PicacgReaderPreloadPolicy.taskLimit(14, isPicacg = false, readerThreadsEnabled = true))
    }
}
