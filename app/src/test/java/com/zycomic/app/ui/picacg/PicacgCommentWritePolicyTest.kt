package com.zycomic.app.ui.picacg

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgCommentWritePolicyTest {
    @Test
    fun `only an explicit refresh of the affected location can unlock uncertain writes`() {
        val blocked = PicacgCommentWriteLocation("thread", 2, 10)
        assertFalse(PicacgCommentWritePolicy.resolves(blocked, blocked))
        assertFalse(PicacgCommentWritePolicy.resolves(blocked, PicacgCommentWriteLocation(null, 2, 11)))
        assertFalse(PicacgCommentWritePolicy.resolves(blocked, PicacgCommentWriteLocation("thread", 1, 11)))
        assertTrue(PicacgCommentWritePolicy.resolves(blocked, PicacgCommentWriteLocation("thread", 2, 11)))
    }

    @Test
    fun `an unlocked state does not resolve any pending operation`() {
        assertFalse(PicacgCommentWritePolicy.resolves(null, PicacgCommentWriteLocation(null, 1, 1)))
    }
}
