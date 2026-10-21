package com.zycomic.app.picacg

import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** User-editable fields for the Picacg registration endpoint. Gender is intentionally not user-editable. */
data class PicacgRegistrationDraft(
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val question1: String = "",
    val answer1: String = "",
    val question2: String = "",
    val answer2: String = "",
    val question3: String = "",
    val answer3: String = "",
    val birthday: String = "",
)

enum class PicacgRegistrationIssue {
    NAME_REQUIRED,
    NAME_TOO_LONG,
    USERNAME_INVALID,
    PASSWORD_TOO_SHORT,
    BIRTHDAY_INVALID,
    UNDERAGE,
    SECURITY_ANSWERS_REQUIRED,
}

/** Pure validation and draft generation policy; never performs network requests. */
object PicacgRegistrationPolicy {
    private val dateFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private const val SAFE_ALPHABET = "abcdefghijkmnpqrstuvwxyz0123456789"
    private val secureRandom = SecureRandom()

    fun validate(draft: PicacgRegistrationDraft, today: LocalDate = LocalDate.now()): PicacgRegistrationIssue? {
        if (draft.name.isBlank()) return PicacgRegistrationIssue.NAME_REQUIRED
        if (draft.name.length > 50) return PicacgRegistrationIssue.NAME_TOO_LONG
        if (!draft.username.matches(Regex("[A-Za-z0-9]+"))) return PicacgRegistrationIssue.USERNAME_INVALID
        if (draft.password.length < 8) return PicacgRegistrationIssue.PASSWORD_TOO_SHORT
        val birthday = runCatching { LocalDate.parse(draft.birthday, dateFormatter) }.getOrNull()
            ?: return PicacgRegistrationIssue.BIRTHDAY_INVALID
        if (birthday.isAfter(today.minusYears(18))) return PicacgRegistrationIssue.UNDERAGE
        if (listOf(draft.question1, draft.answer1, draft.question2, draft.answer2, draft.question3, draft.answer3).any { it.isBlank() }) {
            return PicacgRegistrationIssue.SECURITY_ANSWERS_REQUIRED
        }
        return null
    }

    /** Creates a compliant editable draft only; callers decide when to send it and retain it on failure. */
    fun generateDraft(today: LocalDate = LocalDate.now()): PicacgRegistrationDraft {
        val base = randomText(7)
        val suffix = today.format(DateTimeFormatter.BASIC_ISO_DATE)
        return PicacgRegistrationDraft(
            name = "weba$base${secureRandom.nextInt(1000)}",
            username = "$base${suffix}u",
            password = "$base${suffix}p",
            question1 = randomText(8),
            answer1 = randomText(9),
            question2 = randomText(8),
            answer2 = randomText(9),
            question3 = randomText(8),
            answer3 = randomText(9),
            birthday = today.minusYears(20).withMonth(1).withDayOfMonth(1).toString(),
        )
    }

    fun toPayload(draft: PicacgRegistrationDraft): JSONObject {
        require(validate(draft) == null) { "Picacg registration draft is invalid" }
        return JSONObject()
            .put("name", draft.name.trim())
            .put("email", draft.username)
            .put("password", draft.password)
            .put("question1", draft.question1.trim())
            .put("answer1", draft.answer1.trim())
            .put("question2", draft.question2.trim())
            .put("answer2", draft.answer2.trim())
            .put("question3", draft.question3.trim())
            .put("answer3", draft.answer3.trim())
            .put("birthday", draft.birthday)
            .put("gender", "f")
    }

    private fun randomText(length: Int) = buildString(length) {
        repeat(length) { append(SAFE_ALPHABET[secureRandom.nextInt(SAFE_ALPHABET.length)]) }
    }
}
