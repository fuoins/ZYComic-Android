package com.zycomic.app.net

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmAccountSnapshotTest {
    @Test
    fun `login fields parse string and numeric values while missing fields remain null`() {
        val snapshot = JmAccountSnapshot.fromJson(
            JSONObject(
                """
                {
                  "level": 6,
                  "exp": "8875",
                  "nextLevelExp": 9450,
                  "coin": "3312",
                  "album_favorites": 74
                }
                """.trimIndent(),
            ),
        )

        assertEquals("6", snapshot.level)
        assertEquals("8875", snapshot.exp)
        assertEquals("9450", snapshot.nextLevelExp)
        assertEquals("3312", snapshot.coin)
        assertEquals("74", snapshot.albumFavorites)
        assertNull(snapshot.albumFavoritesMax)
    }

    @Test
    fun `missing and malformed counters never become zero`() {
        val snapshot = JmAccountSnapshot.fromJson(
            JSONObject("""{"level":" ","exp":-1,"nextLevelExp":2.5,"coin":true,"album_favorites":{},"album_favorites_max":null}"""),
        )

        assertNull(snapshot.level)
        assertNull(snapshot.exp)
        assertNull(snapshot.nextLevelExp)
        assertNull(snapshot.coin)
        assertNull(snapshot.albumFavorites)
        assertNull(snapshot.albumFavoritesMax)
    }

    @Test
    fun `snapshot serializes and restores without losing nullable profile fields`() {
        val original = JmAccountSnapshot(level = "6", exp = "8875", nextLevelExp = "9450", coin = "3312", albumFavorites = "74")

        val restored = JmAccountSnapshotPolicy.decode(JmAccountSnapshotPolicy.encode(original))

        assertEquals(original, restored)
        assertNull(JmAccountSnapshotPolicy.decode(null))
        assertNull(JmAccountSnapshotPolicy.decode("not-json"))
    }

    @Test
    fun `snapshot is retained only for the same account identity`() {
        val snapshot = JmAccountSnapshot(level = "6", coin = "3312")

        assertEquals(snapshot, JmAccountSnapshotPolicy.forAccount(snapshot, "alice", "123", "alice", "123"))
        assertNull(JmAccountSnapshotPolicy.forAccount(snapshot, "alice", "123", "bob", "456"))
        assertNull(JmAccountSnapshotPolicy.forAccount(snapshot, "alice", "123", "alice", null))
    }

    @Test
    fun `new login attempt logout and session revision invalidate old responses`() {
        val policy = JmLoginAttemptPolicy()
        val first = policy.begin(sessionRevision = 7)
        assertTrue(policy.isCurrent(first, sessionRevision = 7))

        val second = policy.begin(sessionRevision = 7)
        assertFalse(policy.isCurrent(first, sessionRevision = 7))
        assertTrue(policy.isCurrent(second, sessionRevision = 7))
        assertFalse(policy.isCurrent(second, sessionRevision = 8))

        policy.invalidate()
        assertFalse(policy.isCurrent(second, sessionRevision = 7))
    }
}
