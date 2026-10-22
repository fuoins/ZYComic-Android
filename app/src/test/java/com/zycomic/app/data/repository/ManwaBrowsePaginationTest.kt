package com.zycomic.app.data.repository

import com.zycomic.app.data.dto.Manga
import com.zycomic.app.ui.browse.ManwaFirstPageRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.SocketException

class ManwaBrowsePaginationTest {
    @Test
    fun `retry failed page only without repeating or duplicating successful pages`() = runBlocking {
        val pages = mutableListOf<Int>()
        val retry = ManwaFirstPageRetry(enabled = true, isCurrent = { true }, waitBeforeRetry = {})
        var failed = false
        val result = MangaRepository.loadWithFilter(page = 1, targetCount = 2) { page ->
            retry.run {
                pages += page
                if (page == 2 && !failed) {
                    failed = true
                    throw SocketException("Software caused connection abort")
                }
                listOf(Manga(id = page.toString(), name = "book-$page"))
            }
        }
        assertEquals(listOf(1, 2, 2), pages)
        assertEquals(listOf("1", "2"), result.map { it.id })
    }

    @Test
    fun `cancellation after a successful page never returns partial stale data`() = runBlocking {
        val cancellation = CancellationException("account changed")
        var observed: CancellationException? = null
        try {
            MangaRepository.loadWithFilter(page = 1, targetCount = 2) { page ->
                if (page == 2) throw cancellation
                listOf(Manga(id = "1", name = "book-1"))
            }
        } catch (error: CancellationException) {
            observed = error
        }
        assertSame(cancellation, observed)
    }

    @Test
    fun `account transition during retry wait cancels without replay`() = runBlocking {
        val session = ManwaSessionVerificationPolicy()
        session.restore("account-a") {}
        val ticket = session.beginRefresh {}
        var reads = 0
        val retry = ManwaFirstPageRetry(
            enabled = true,
            isCurrent = { session.isCurrent(ticket) },
            waitBeforeRetry = { session.beginLocalTransition {} },
        )
        var cancelled = false
        try {
            retry.run {
                reads++
                throw SocketException("Software caused connection abort")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
        assertEquals(1, reads)
    }
}
