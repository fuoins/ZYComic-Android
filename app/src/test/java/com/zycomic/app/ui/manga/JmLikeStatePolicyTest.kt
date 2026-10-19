package com.zycomic.app.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmLikeStatePolicyTest {
    @Test
    fun `confirmed like is one way and increments a known count once`() {
        assertEquals(JmLikeConfirmation(accountAlbumKey = "account-a-album-10", likes = 42), JmLikeStatePolicy.confirmSuccess("account-a-album-10", 41))
        assertEquals(JmLikeConfirmation(accountAlbumKey = "account-a-album-10", likes = null), JmLikeStatePolicy.confirmSuccess("account-a-album-10", null))
        assertEquals(Int.MAX_VALUE, JmLikeStatePolicy.confirmSuccess("account-a-album-10", Int.MAX_VALUE).likes)
    }

    @Test
    fun `same account confirmation beats stale album liked and count fields`() {
        val state = JmLikeStatePolicy.mergeAlbum(
            albumLiked = false,
            albumLikes = 10,
            currentAccountAlbumKey = "account-a-album-10",
            confirmation = JmLikeConfirmation(accountAlbumKey = "account-a-album-10", likes = 11),
        )
        assertEquals(JmLikeState(liked = true, likes = 11), state)
    }

    @Test
    fun `fresh larger server count is not frozen at the persisted confirmation`() {
        val state = JmLikeStatePolicy.mergeAlbum(false, 90, "a", JmLikeConfirmation("a", 11))
        assertEquals(JmLikeState(true, 90), state)
    }

    @Test
    fun `another account uses its own album values instead of previous confirmation`() {
        val state = JmLikeStatePolicy.mergeAlbum(
            albumLiked = false,
            albumLikes = 4,
            currentAccountAlbumKey = "account-b-album-10",
            confirmation = JmLikeConfirmation(accountAlbumKey = "account-a-album-10", likes = 11),
        )
        assertEquals(JmLikeState(liked = false, likes = 4), state)
    }

    @Test
    fun `unknown outcome blocks duplicates only for the current account session`() {
        assertTrue(JmLikeStatePolicy.outcomeUnknownForCurrentSession(7, 7))
        assertFalse(JmLikeStatePolicy.outcomeUnknownForCurrentSession(8, 7))
        assertFalse(JmLikeStatePolicy.outcomeUnknownForCurrentSession(null, 7))
    }

    @Test
    fun `persistent account album keys are stable isolated and hashed`() {
        val first = JmLikeStatePolicy.accountAlbumKey("alice", "10")
        assertEquals(first, JmLikeStatePolicy.accountAlbumKey("alice", "10"))
        assertTrue(first != JmLikeStatePolicy.accountAlbumKey("bob", "10"))
        assertTrue(first != JmLikeStatePolicy.accountAlbumKey("alice", "11"))
        assertFalse(first!!.contains("alice"))
        assertEquals(null, JmLikeStatePolicy.accountAlbumKey(null, "10"))
    }
}
