package com.zycomic.app.picacg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PicacgRegistrationPolicyTest {
    private val today = LocalDate.of(2026, 10, 21)

    @Test
    fun `accepts valid account and an exactly eighteen year old birthday`() {
        assertNull(PicacgRegistrationPolicy.validate(validDraft().copy(birthday = "2008-10-21"), today))
    }

    @Test
    fun `enforces nickname account password birthday and all three security pairs`() {
        val draft = validDraft()
        assertEquals(PicacgRegistrationIssue.NAME_REQUIRED, PicacgRegistrationPolicy.validate(draft.copy(name = "  "), today))
        assertEquals(PicacgRegistrationIssue.NAME_TOO_LONG, PicacgRegistrationPolicy.validate(draft.copy(name = "n".repeat(51)), today))
        assertNull(PicacgRegistrationPolicy.validate(draft.copy(name = "n".repeat(50)), today))
        assertEquals(PicacgRegistrationIssue.USERNAME_INVALID, PicacgRegistrationPolicy.validate(draft.copy(username = "reader.name"), today))
        assertEquals(PicacgRegistrationIssue.PASSWORD_TOO_SHORT, PicacgRegistrationPolicy.validate(draft.copy(password = "1234567"), today))
        assertEquals(PicacgRegistrationIssue.BIRTHDAY_INVALID, PicacgRegistrationPolicy.validate(draft.copy(birthday = "2008-02-30"), today))
        assertEquals(PicacgRegistrationIssue.UNDERAGE, PicacgRegistrationPolicy.validate(draft.copy(birthday = "2008-10-22"), today))
        assertEquals(PicacgRegistrationIssue.SECURITY_ANSWERS_REQUIRED, PicacgRegistrationPolicy.validate(draft.copy(answer2 = ""), today))
    }

    @Test
    fun `generated draft is valid and yields all eleven wire fields with fixed female gender`() {
        val draft = PicacgRegistrationPolicy.generateDraft(today)
        assertNull(PicacgRegistrationPolicy.validate(draft, today))
        assertTrue(draft.username.matches(Regex("[A-Za-z0-9]+")))
        assertTrue(draft.password.length >= 8)
        assertEquals("2006-01-01", draft.birthday)

        val payload = PicacgRegistrationPolicy.toPayload(draft)
        assertEquals(11, payload.length())
        assertEquals(draft.username, payload.getString("email"))
        assertEquals("f", payload.getString("gender"))
        assertFalse(payload.has("username"))
    }

    private fun validDraft() = PicacgRegistrationDraft(
        name = "Reader",
        username = "Reader2026",
        password = "password-long",
        question1 = "Question one",
        answer1 = "Answer one",
        question2 = "Question two",
        answer2 = "Answer two",
        question3 = "Question three",
        answer3 = "Answer three",
        birthday = "2000-01-01",
    )
}
