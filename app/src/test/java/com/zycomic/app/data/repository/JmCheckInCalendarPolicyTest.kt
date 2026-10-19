package com.zycomic.app.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

class JmCheckInCalendarPolicyTest {
    private val today = LocalDate.parse("2026-10-19")

    @Test
    fun `daily preview candidates are only past unsigned dates`() {
        val calendar = JmCheckInResponseParser.parseCalendar(
            """{"daily_id":"73","event_name":"October","currentProgress":"71.4%","record":[[
                {"date":"17","signed":false,"bonus":false},
                {"date":"18","signed":null,"bonus":true},
                {"date":"19","signed":false,"bonus":false},
                {"date":"20","signed":null,"bonus":true},
                {"date":"16","signed":true,"bonus":false}
            ]] }""",
            today,
            "account-hash",
            7L,
        )

        assertEquals("73", calendar.dailyId)
        assertEquals("October", calendar.eventName)
        assertEquals(listOf(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-18")), calendar.makeupCandidates(today).map { it.date })
        assertEquals("account-hash", calendar.accountHash)
        assertEquals(7L, calendar.sessionRevision)
    }

    @Test
    fun `makeup preview includes every past unsigned day but excludes signed today and future`() {
        val records = (1..31).joinToString(",") { day ->
            val signed = when (day) {
                2 -> "true"
                4 -> "null"
                19 -> "false"
                else -> "false"
            }
            """{"date":"%02d","signed":%s}""".format(day, signed)
        }
        val calendar = JmCheckInResponseParser.parseCalendar(
            """{"daily_id":"73","record":[[$records]]}""",
            today,
            "account-hash",
            7L,
        )

        assertEquals(
            (1..18).filter { it != 2 }.map { LocalDate.of(2026, 10, it) },
            calendar.makeupCandidates(today).map { it.date },
        )
    }

    @Test
    fun `calendar requires positive daily id and record and ignores full dates outside its month`() {
        val calendar = JmCheckInResponseParser.parseCalendar(
            """{"daily_id":"73","record":[[{"date":"2026-10-01","signed":false},{"date":"2026-11-01","signed":false}]]}""",
            today,
            "account-hash",
            7L,
        )
        assertEquals(listOf(LocalDate.parse("2026-10-01")), calendar.days.map { it.date })

        listOf("null", "abc", "0", "-1").forEach { dailyId ->
            assertThrows(IOException::class.java) {
                JmCheckInResponseParser.parseCalendar(
                    """{"daily_id":"$dailyId","record":[[{"date":"01","signed":false}]]}""",
                    today,
                    "account-hash",
                    7L,
                )
            }
        }
        assertThrows(IOException::class.java) {
            JmCheckInResponseParser.parseCalendar("""{"daily_id":"73"}""", today, "account-hash", 7L)
        }
        assertThrows(IOException::class.java) {
            JmCheckInResponseParser.parseCalendar("""{"daily_id":"73","record":[]}""", today, "account-hash", 7L)
        }
    }

    @Test
    fun `annual list maps exact year month id and only joins nonempty artwork`() {
        val years = JmCheckInResponseParser.parseAwardYears("""{"list":[{"title":"2024"},{"title":"2026"},{"title":"oops"}]}""")
        val months = JmCheckInResponseParser.parseMonthlyAwards(
            """{"list":[
                {"id":"74","year":"2026","month":"2","img":"/award/feb.jpg"},
                {"id":"73","year":"2026","month":"1","img":null},
                {"id":"90","year":"2025","month":"12","img":"/wrong.jpg"},
                {"id":"bad","year":"2026","month":"13","img":"/wrong.jpg"},
                {"id":"null","year":"2026","month":"3","img":"/wrong.jpg"},
                {"id":"abc","year":"2026","month":"4","img":"/wrong.jpg"}
            ]} """,
            "2026",
        )

        assertEquals(listOf("2026", "2024"), years)
        assertEquals(listOf("73", "74"), months.map { it.id })
        assertEquals(1, months[0].month)
        assertNull(months[0].imagePath)
        assertEquals("/award/feb.jpg", months[1].imagePath)
        assertEquals("https://cdn.example/award/feb.jpg", JmCheckInResponseParser.joinImageUrl("https://cdn.example/", months[1].imagePath))
        assertEquals("https://cdn.example/award/feb.jpg", JmCheckInResponseParser.joinImageUrl(null, "https://cdn.example/award/feb.jpg"))
        assertEquals("https://cdn.example/award/feb.jpg", JmCheckInResponseParser.joinImageUrl("https://other.example", "//cdn.example/award/feb.jpg"))
        assertNull(JmCheckInResponseParser.joinImageUrl("https://cdn.example", null))
        assertNull(JmCheckInResponseParser.joinImageUrl(null, "   "))
        assertNull(JmCheckInResponseParser.joinImageUrl(null, "/award/feb.jpg"))
        assertNull(JmCheckInResponseParser.joinImageUrl("http://cdn.example", "/award/feb.jpg"))
        assertNull(JmCheckInResponseParser.joinImageUrl("https://cdn.example", "http://cdn.example/award.jpg"))
        assertNull(JmCheckInResponseParser.joinImageUrl(null, "javascript:alert(1)"))
        assertNull(JmCheckInResponseParser.joinImageUrl(null, "https://"))
        assertFalse(JmCheckInResponseParser.requiresImageHost("https://cdn.example/award/feb.jpg"))
        assertFalse(JmCheckInResponseParser.requiresImageHost("//cdn.example/award/feb.jpg"))
        assertFalse(JmCheckInResponseParser.requiresImageHost("http://cdn.example/award/feb.jpg"))
        assertTrue(JmCheckInResponseParser.requiresImageHost("/award/feb.jpg"))
        assertFalse(JmCheckInResponseParser.requiresImageHost(null))
        assertNull(JmCheckInResponseParser.parseImageHost("""{"img_host":"http://unsafe.example"}"""))
        assertEquals("https://cdn.example", JmCheckInResponseParser.parseImageHost("""{"data":{"img_host":"https://cdn.example/"}}"""))
        assertEquals(listOf("2026", "1000"), JmCheckInResponseParser.parseAwardYears("""{"list":[{"title":"2026"},{"title":"1000"},{"title":"0001"},{"title":"0010"},{"title":"0999"}]}"""))
        assertThrows(IOException::class.java) { JmCheckInResponseParser.parseMonthlyAwards("""{"list":[]}""", "0010") }
        assertTrue(isValidAwardYear("2026"))
        assertFalse(isValidAwardYear("0010"))
    }

    @Test
    fun `unsafe nonempty award image path is preserved while resolved URL stays null`() {
        val imagePath = "http://unsafe.example/reward.jpg"
        val award = JmCheckInResponseParser.parseMonthlyAwards(
            """{"list":[{"id":"73","year":"2026","month":"1","img":"$imagePath"}]}""",
            "2026",
        ).single()

        assertEquals(imagePath, award.imagePath)
        assertNull(JmCheckInResponseParser.joinImageUrl(null, award.imagePath))
    }

    @Test
    fun `makeup success requires positive confirmation and future-date rejection is surfaced`() {
        JmCheckInResponseParser.parseMakeupResponse("""{"msg":"補簽成功 Jcoin:10 EXP:10","makeup_used":0}""")
        val error = assertThrows(IOException::class.java) {
            JmCheckInResponseParser.parseMakeupResponse("""{"msg":"隻能補簽今天之前的日期"}""")
        }
        assertTrue(error.message.orEmpty().contains("拒绝"))
        assertThrows(IOException::class.java) { JmCheckInResponseParser.parseMakeupResponse("{}") }
    }

    @Test
    fun `malformed JSON stays classified as retryable without exposing response contents`() {
        val error = assertThrows(JmInvalidJsonResponseException::class.java) {
            JmCheckInResponseParser.parseAwardYears("not-json-secret-account")
        }
        assertTrue(error.message.orEmpty().contains("响应无效"))
        assertFalse(error.message.orEmpty().contains("secret-account"))
    }
}
