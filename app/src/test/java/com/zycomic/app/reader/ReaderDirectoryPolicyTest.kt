package com.zycomic.app.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderDirectoryPolicyTest {
    @Test
    fun `incomplete prefix resolves only targets already fetched`() {
        assertEquals(0, ReaderDirectoryPolicy.resolveTarget(-3, fetched = 2, complete = false))
        assertEquals(1, ReaderDirectoryPolicy.resolveTarget(1, fetched = 2, complete = false))
        assertEquals(null, ReaderDirectoryPolicy.resolveTarget(2, fetched = 2, complete = false))
    }

    @Test
    fun `completed directory clamps an out of range target to its real tail`() {
        assertEquals(null, ReaderDirectoryPolicy.resolveTarget(0, fetched = 0, complete = true))
        assertEquals(2, ReaderDirectoryPolicy.resolveTarget(99, fetched = 3, complete = true))
    }

    @Test
    fun `incomplete count remains unknown and fetched tail is not chapter end`() {
        assertEquals(-1, ReaderDirectoryPolicy.totalPages(fetched = 4, complete = false))
        assertEquals(4, ReaderDirectoryPolicy.totalPages(fetched = 4, complete = true))
        assertFalse(ReaderDirectoryPolicy.isLastPage(index = 3, fetched = 4, complete = false))
        assertTrue(ReaderDirectoryPolicy.isLastPage(index = 3, fetched = 4, complete = true))
        assertFalse(ReaderDirectoryPolicy.isLastPage(index = 0, fetched = 0, complete = true))
    }

    @Test
    fun `temporary prefix tail does not look like a stable page identity boundary`() {
        val tailInPrefix = ReaderDirectoryPolicy.resolveTarget(target = 2, fetched = 3, complete = false)
        assertEquals(2, tailInPrefix)
        assertFalse(ReaderDirectoryPolicy.isLastPage(index = tailInPrefix!!, fetched = 3, complete = false))
        assertEquals(-1, ReaderDirectoryPolicy.totalPages(fetched = 3, complete = false))
    }

    @Test
    fun `fresh snapshots reuse only same-position entries with unchanged manifest identity`() {
        data class Entry(val id: String)
        val snapshots = ReaderPageSnapshots<Entry> { old, fresh -> old.id == fresh.id }
        val first = Entry("a")
        val second = Entry("b")
        val initial = snapshots.accept(listOf(first, second))
        val sameManifest = snapshots.accept(listOf(Entry("a"), Entry("b")))

        assertSame(initial[0], sameManifest[0])
        assertSame(initial[1], sameManifest[1])
    }

    @Test
    fun `changed fresh manifest entry gets a fresh object while unchanged sibling stays stable`() {
        data class Entry(val url: String, val payload: String)
        val snapshots = ReaderPageSnapshots<Entry> { old, fresh -> old.url == fresh.url }
        val oldA = Entry("old-url", "old")
        val oldB = Entry("same-url", "old")
        val first = snapshots.accept(listOf(oldA, oldB))
        val newA = Entry("fresh-url", "fresh payload")
        val newB = Entry("same-url", "updated metadata")
        val changed = snapshots.accept(listOf(newA, newB))

        assertNotSame(first[0], changed[0])
        assertEquals("fresh payload", changed[0].payload)
        assertSame(first[1], changed[1])
        assertEquals("old", changed[1].payload)
    }

    @Test
    fun `a later fresh snapshot reconciles from the most recently accepted manifest`() {
        data class Entry(val url: String)
        val snapshots = ReaderPageSnapshots<Entry> { old, fresh -> old.url == fresh.url }
        val changed = Entry("changed-url")
        val accepted = snapshots.accept(listOf(Entry("original-url"), changed))
        val next = snapshots.accept(listOf(Entry("new-url"), Entry("changed-url")))

        assertSame(accepted[1], next[1])
        assertNotSame(accepted[0], next[0])
    }
}
