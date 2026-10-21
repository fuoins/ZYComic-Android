package com.zycomic.app.reader

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class PicacgPageListProgressTest {
    @Test
    fun `reports batches only to the listener in the current coroutine context`() = runBlocking {
        val first = mutableListOf<Triple<Int, Int, Boolean>>()
        val second = mutableListOf<Triple<Int, Int, Boolean>>()

        withContext(PicacgPageListProgress { batch, images, hasMore -> first += Triple(batch, images, hasMore) }) {
            reportPicacgPageListProgress(batch = 1, fetchedImages = 40, hasMore = true)
            withContext(PicacgPageListProgress { batch, images, hasMore -> second += Triple(batch, images, hasMore) }) {
                reportPicacgPageListProgress(batch = 2, fetchedImages = 73, hasMore = false)
            }
            reportPicacgPageListProgress(batch = 3, fetchedImages = 91, hasMore = false)
        }

        assertEquals(listOf(Triple(1, 40, true), Triple(3, 91, false)), first)
        assertEquals(listOf(Triple(2, 73, false)), second)
    }

    @Test
    fun `does nothing when no progress listener is installed`() = runBlocking {
        reportPicacgPageListProgress(batch = 1, fetchedImages = 0, hasMore = false)
    }
}
