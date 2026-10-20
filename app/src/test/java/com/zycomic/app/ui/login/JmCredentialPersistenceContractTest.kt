package com.zycomic.app.ui.login

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Offline contracts for automatic credential persistence; no Android runtime or live login required. */
class JmCredentialPersistenceContractTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isDirectory(it.resolve("app/src/main/java/com/zycomic/app/ui/login")) }

    private fun source(path: String): String = root.resolve("app/src/main/java/com/zycomic/app/$path").toFile().readText()

    @Test
    fun `all JM login entry points share the validated encrypted login flow`() {
        assertTrue(source("ui/login/JmAccountPanel.kt").contains("JmAccountLogin.login(username, password)"))
        assertTrue(source("ui/login/LoginViewModel.kt").contains("JmAccountLogin.login(u, p)"))
        val detail = source("ui/manga/MangaDetailViewModel.kt")
            .substringAfter("fun loginJm(").substringBefore("fun logoutJm(")
        val login = detail.indexOf("JmAccountLogin.login(username.trim(), password)")
        assertTrue(login >= 0)
        assertTrue(detail.indexOf("jmAccountName.value = username.trim()") > login)
        assertTrue(detail.contains("catch (e: CancellationException)"))
        val adapter = source("reader/JmSource.kt")
        assertFalse(adapter.contains("suspend fun login("))
        assertFalse(adapter.contains("postJson(\"/login\""))
        assertFalse(adapter.contains("JmAuthStore.save("))
        assertTrue(adapter.contains("fun logout()"))
    }

    @Test
    fun `accepted login requires credentials and encrypts them before committing session or cookies`() {
        val store = source("net/JmAuthStore.kt")
        val save = store.substringAfter("fun saveLogin(").substringBefore("fun isLoginCurrent(")
        assertTrue(save.contains("credentialUsername: String,"))
        assertTrue(save.contains("credentialPassword: String,"))
        assertFalse(save.contains("rememberPassword"))
        assertTrue(save.contains("loginAttempts.runIfCurrent(attempt, sessionRevision)"))
        val validation = save.indexOf("require(JmCredentialRefreshPolicy.shouldStorePassword(credentialUsername, credentialPassword))")
        val encrypt = save.indexOf("val credentials = encryptRememberedCredentials(username, uid, credentialUsername, credentialPassword)")
        val persist = save.indexOf("saveValues(token, username, uid, profile, credentials)")
        val cookies = save.indexOf("commitCookies()")
        assertTrue(validation >= 0 && encrypt > validation && persist > encrypt && cookies > persist)
        assertTrue(store.contains("KeyStore.getInstance(ANDROID_KEYSTORE)"))
        assertTrue(store.contains("Cipher.getInstance(\"AES/GCM/NoPadding\")"))
        assertTrue(store.contains("return encryptString(payload, CREDENTIALS_AAD)"))
        assertTrue(store.contains("putString(REMEMBERED_CREDENTIALS, credentialsCiphertext)"))
    }

    @Test
    fun `shared login and manual refresh retain credential and session guards without opt in`() {
        val login = source("ui/login/JmAccountLogin.kt")
        assertFalse(login.contains("rememberPassword"))
        assertFalse(login.contains("勾选"))
        assertTrue(login.contains("require(username.isNotBlank())"))
        assertTrue(login.contains("require(password.isNotBlank())"))
        assertTrue(login.contains("credentialUsername = username.trim()"))
        assertTrue(login.contains("credentialPassword = password"))
        assertTrue(login.contains("JmAuthStore.rememberedCredentialsForCurrentSession()"))
        assertTrue(login.contains("preserveSessionOnUnauthorized = true"))
        assertTrue(login.contains("expectedSessionRevision = credentials.sessionRevision"))
        assertTrue(login.contains("expectedAccountName = credentials.accountName"))
        assertTrue(login.contains("expectedAccountUid = credentials.accountUid"))
    }

    @Test
    fun `remember password control is removed while account action row remains`() {
        val panel = source("ui/login/JmAccountPanel.kt")
        assertFalse(panel.contains("rememberPassword"))
        assertFalse(panel.contains("Checkbox"))
        assertFalse(panel.contains("jm_remember_password"))
        assertTrue(panel.contains("import androidx.compose.foundation.layout.Row"))
        assertTrue(panel.contains("Row {"))
        val resources = root.resolve("i18n-kmk/src/commonMain/moko-resources/base/strings.xml").toFile().readText()
        assertFalse(resources.contains("name=\"jm_remember_password\""))
    }
}
