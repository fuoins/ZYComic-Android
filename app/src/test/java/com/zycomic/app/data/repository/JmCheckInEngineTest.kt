package com.zycomic.app.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

class JmCheckInEngineTest {
    private val today = "2026-10-18"
    private val account = "account-hash"

    @Test
    fun `pure response parsing rejects fail status and nonempty error message`() {
        assertThrows(IOException::class.java) {
            JmCheckInResponseParser.parseDailySnapshot("{\"status\":\"fail\",\"daily_id\":\"d\"}", LocalDate.parse(today))
        }
        assertThrows(IOException::class.java) {
            JmCheckInResponseParser.parseDailySnapshot("{\"status\":\"success\",\"errorMsg\":\"denied\",\"daily_id\":\"d\"}", LocalDate.parse(today))
        }
    }

    @Test
    fun `null error message is not an API error and empty submit array is accepted`() {
        assertEquals(
            "daily-id",
            JmCheckInResponseParser.parseDailySnapshot(
                "{\"status\":\"success\",\"errorMsg\":null,\"daily_id\":\"daily-id\",\"record\":[]}",
                LocalDate.parse(today),
            ).dailyId,
        )
        JmCheckInResponseParser.parseSubmitResponse("[]")
    }

    @Test
    fun `empty record readback does not confirm today's sign in`() {
        val snapshot = JmCheckInResponseParser.parseDailySnapshot(
            "{\"daily_id\":\"daily-id\",\"record\":[]}",
            LocalDate.parse(today),
        )
        assertFalse(snapshot.signedToday)
    }

    @Test
    fun `automatic attempt is persisted and is not retried after failure while manual can retry`() = runBlocking {
        val api = FakeApi().apply { failSubmit = true }
        val state = FakeState(account)
        val engine = JmCheckInEngine(api, state)

        assertThrows(IOException::class.java) { runBlocking { engine.checkIn("101", account, today, automatic = true) } }
        assertEquals(today, state.attempted[account])
        assertNull(engine.checkIn("101", account, today, automatic = true))
        assertEquals(1, api.fetchCount)

        api.failSubmit = false
        assertEquals("签到成功", engine.checkIn("101", account, today, automatic = false))
        assertEquals(listOf("GET", "POST:daily-id", "GET", "POST:daily-id", "GET"), api.calls)
    }

    @Test
    fun `concurrent automatic and manual calls are serialized and only one write occurs`() = runBlocking {
        val api = FakeApi(delayMillis = 30)
        val state = FakeState(account)
        val engine = JmCheckInEngine(api, state)

        val automatic = async { engine.checkIn("101", account, today, automatic = true) }
        val manual = async { engine.checkIn("101", account, today, automatic = false) }

        assertEquals("签到成功", automatic.await())
        assertEquals("今日已签到", manual.await())
        assertEquals(1, api.submitCount)
        assertEquals(2, api.fetchCount)
    }

    @Test
    fun `a successful cached sign in returns already signed and automatic duplicates return null`() = runBlocking {
        val api = FakeApi()
        val state = FakeState(account).apply { successful[account] = today }
        val engine = JmCheckInEngine(api, state)

        assertEquals("今日已签到", engine.checkIn("101", account, today, automatic = false))
        assertNull(engine.checkIn("101", account, today, automatic = true))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `restored success is offline scoped to the current account and today`() {
        val yesterday = LocalDate.parse(today).minusDays(1).toString()
        val successfulDates = mapOf(account to today, "other-account" to yesterday)
        val readDate: (String) -> String? = { successfulDates[it] }

        assertEquals("今日已签到", JmCheckInUiState.restoredMessage(account, today, readDate))
        assertNull(JmCheckInUiState.restoredMessage("other-account", today, readDate))
        assertNull(JmCheckInUiState.restoredMessage(null, today, readDate))
        assertNull(JmCheckInUiState.restoredMessage(account, LocalDate.parse(today).plusDays(1).toString(), readDate))
    }

    @Test
    fun `automatic check-in can be attempted again after date rollover`() = runBlocking {
        val yesterday = LocalDate.parse(today).minusDays(1).toString()
        val api = FakeApi()
        val state = FakeState(account).apply {
            successful[account] = yesterday
            attempted[account] = yesterday
        }
        val engine = JmCheckInEngine(api, state)

        assertEquals("签到成功", engine.checkIn("101", account, today, automatic = true))
        assertEquals(today, state.successful[account])
        assertEquals(today, state.attempted[account])
        assertEquals(1, api.submitCount)
    }

    @Test
    fun `an automatic skip does not clear a persisted success date`() = runBlocking {
        val state = FakeState(account).apply {
            autoEnabled = false
            successful[account] = today
        }
        val engine = JmCheckInEngine(FakeApi(), state)

        assertNull(engine.checkIn("101", account, today, automatic = true))
        assertEquals(today, state.successful[account])
    }

    @Test
    fun `automatic disabled skips request without marking an attempt`() = runBlocking {
        val api = FakeApi()
        val state = FakeState(account).apply { autoEnabled = false }
        val engine = JmCheckInEngine(api, state)

        assertNull(engine.checkIn("101", account, today, automatic = true))
        assertTrue(state.attempted.isEmpty())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `write response without confirmed daily readback is not success`() = runBlocking {
        val api = FakeApi().apply { signOnSubmit = false }
        val state = FakeState(account)
        val engine = JmCheckInEngine(api, state)

        assertThrows(IOException::class.java) { runBlocking { engine.checkIn("101", account, today, automatic = false) } }
        assertFalse(state.successful.containsKey(account))
        assertEquals(listOf("GET", "POST:daily-id", "GET"), api.calls)
    }

    @Test
    fun `account switching before request prevents writes`() = runBlocking {
        val api = FakeApi()
        val state = FakeState("different-account")
        val engine = JmCheckInEngine(api, state)

        assertThrows(IOException::class.java) { runBlocking { engine.checkIn("101", account, today, automatic = false) } }
        assertTrue(api.calls.isEmpty())
    }

    private class FakeApi(private val delayMillis: Long = 0) : JmCheckInApi {
        val calls = mutableListOf<String>()
        var fetchCount = 0
        var submitCount = 0
        var failSubmit = false
        var signOnSubmit = true
        private var signedToday = false

        override suspend fun fetchDaily(uid: String): JmDailySnapshot {
            delay(delayMillis)
            calls += "GET"
            fetchCount++
            return JmDailySnapshot("daily-id", signedToday)
        }

        override suspend fun submit(uid: String, dailyId: String) {
            delay(delayMillis)
            calls += "POST:$dailyId"
            submitCount++
            if (failSubmit) throw IOException("fake failure")
            if (signOnSubmit) signedToday = true
        }
    }

    private class FakeState(private var accountHash: String?) : JmCheckInState {
        var autoEnabled = true
        val attempted = mutableMapOf<String, String>()
        val successful = mutableMapOf<String, String>()

        override fun isAutomaticEnabled(): Boolean = autoEnabled
        override fun attemptedDate(accountHash: String): String? = attempted[accountHash]
        override fun successfulDate(accountHash: String): String? = successful[accountHash]
        override fun markAttempt(accountHash: String, date: String) {
            attempted[accountHash] = date
        }
        override fun markSuccess(accountHash: String, date: String) {
            successful[accountHash] = date
        }
        override fun currentAccountHash(): String? = accountHash
    }
}
