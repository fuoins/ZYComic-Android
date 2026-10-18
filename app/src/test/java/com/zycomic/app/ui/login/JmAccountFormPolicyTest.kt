package com.zycomic.app.ui.login

import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmAccountFormPolicyTest {
    @Test
    fun `registration sends five measured fields and accepts only supported non-plus email`() {
        val fields = JmAccountFormPolicy.registrationFields(
            username = "reader",
            email = "reader.name@gmail.com",
            password = "p4ssword",
            passwordConfirm = "p4ssword",
            gender = "Female",
        )
        assertEquals(listOf("username", "email", "password", "password_confirm", "gender"), fields.map { it.first })
        assertEquals("reader.name@gmail.com", fields[1].second)
        assertThrows(IllegalArgumentException::class.java) {
            JmAccountFormPolicy.registrationFields("reader", "reader+tag@gmail.com", "p4ssword", "p4ssword", "Female")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JmAccountFormPolicy.forgotFields("reader+tag@qq.com")
        }
    }

    @Test
    fun `activation requires six ASCII decimal digits and resend uses username plus password`() {
        assertEquals(listOf("username", "numcode"), JmAccountFormPolicy.activationFields("reader", "123456").map { it.first })
        assertThrows(IllegalArgumentException::class.java) {
            JmAccountFormPolicy.activationFields("reader", "١٢٣٤٥٦")
        }
        assertEquals(
            listOf("username" to "reader", "password" to "p4ssword"),
            JmAccountFormPolicy.resendFields(" reader ", "p4ssword"),
        )
        assertEquals(listOf("email" to "reader@qq.com"), JmAccountFormPolicy.forgotFields(" reader@qq.com "))
    }

    @Test
    fun `response messages handle empty string and array error shapes and cooldown remains failure`() {
        assertEquals("已註冊", JmAccountFormPolicy.message("ok", "已註冊", "", null))
        assertEquals(
            "用戶名已經存在！\n電子EMAIL已經被另一個用戶！",
            JmAccountFormPolicy.message("fail", "", JSONArray("[\"用戶名已經存在！\",\"電子EMAIL已經被另一個用戶！\"]"), null),
        )
        assertEquals("信件已發出，請十分鐘後再試 !", JmAccountFormPolicy.message("fail", "", "信件已發出，請十分鐘後再試 !", null))
        assertFalse(JmAccountFormPolicy.succeeded("fail", null))
        assertTrue(JmAccountFormPolicy.succeeded(null, JmAccountFormPolicy.PROFILE_UPDATE_SUCCESS))
    }

    @Test
    fun `password form replaces only two secrets and preserves all 22 other profile values`() {
        val original = JmAccountFormPolicy.profileFieldNames.associateWith { name ->
            when (name) {
                "password", "password_confirm" -> ""
                "gender" -> null
                else -> "original-$name"
            }
        }
        val form = JmAccountFormPolicy.passwordChangeFields(original, "new-secret", "new-secret").toMap()
        assertEquals(JmAccountFormPolicy.profileFieldNames, form.keys.toList())
        assertEquals("new-secret", form["password"])
        assertEquals("new-secret", form["password_confirm"])
        JmAccountFormPolicy.profileFieldNames
            .filterNot { it == "password" || it == "password_confirm" }
            .forEach { name -> assertEquals(original[name].orEmpty(), form[name], name) }

        val afterReadback = original.toMutableMap().apply {
            this["password"] = ""
            this["password_confirm"] = ""
        }
        assertTrue(JmAccountFormPolicy.nonPasswordFieldsPreserved(original, afterReadback))
        afterReadback["firstName"] = "new-secret"
        assertFalse(JmAccountFormPolicy.nonPasswordFieldsPreserved(original, afterReadback))
    }

    @Test
    fun `missing profile field fails closed instead of synthesizing a default`() {
        val incomplete = JmAccountFormPolicy.profileFieldNames.drop(1).associateWith { "existing" }
        assertThrows(IllegalArgumentException::class.java) {
            JmAccountFormPolicy.passwordChangeFields(incomplete, "new-secret", "new-secret")
        }
    }
}
