package com.zycomic.app.ui.login

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelPlatformTest {
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `platform switch clears all credentials and resets account mode`() {
        val model = LoginViewModel()
        model.username.value = "test-user"
        model.password.value = "not-a-real-password"
        model.confirmPassword.value = "not-a-real-password"
        model.email.value = "example@example.invalid"
        model.authCode.value = "test-code"
        model.captchaInput.value = "test-captcha"
        model.captchaImage.value = byteArrayOf(1)
        model.mode.value = 2
        model.success.value = true

        model.resetForPlatform()

        assertEquals("", model.username.value)
        assertEquals("", model.password.value)
        assertEquals("", model.confirmPassword.value)
        assertEquals("", model.email.value)
        assertEquals("", model.authCode.value)
        assertEquals("", model.captchaInput.value)
        assertNull(model.captchaImage.value)
        assertEquals(0, model.mode.value)
        assertFalse(model.success.value)
        assertNull(model.error.value)
    }
}
