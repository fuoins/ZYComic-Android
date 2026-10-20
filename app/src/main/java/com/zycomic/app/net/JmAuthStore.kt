package com.zycomic.app.net

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** JM-only encrypted token and automatic login credential storage. The API's optional `s` is never stored. */
object JmAuthStore {
    private const val PREFS = "jm_auth_secure"
    private const val TOKEN = "jwt_ciphertext"
    private const val ACCOUNT_NAME = "account_name"
    private const val ACCOUNT_UID = "account_uid"
    private const val PROFILE = "account_profile"
    private const val REMEMBERED_CREDENTIALS = "remembered_credentials_ciphertext"
    private const val KEY_ALIAS = "zycomic_jm_jwt_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val CREDENTIALS_AAD = "jm-remembered-password-v1".toByteArray(Charsets.UTF_8)

    @Volatile private var preferences: android.content.SharedPreferences? = null

    @Volatile var accountName: String? = null
        private set

    @Volatile private var accountUid: String? = null
    private val loginAttempts = JmLoginAttemptPolicy()

    private val _accountNameFlow = MutableStateFlow<String?>(null)
    val accountNameFlow: StateFlow<String?> = _accountNameFlow.asStateFlow()

    private val _loggedInFlow = MutableStateFlow(false)
    val loggedInFlow: StateFlow<Boolean> = _loggedInFlow.asStateFlow()

    private val _sessionRevisionFlow = MutableStateFlow(0L)
    val sessionRevisionFlow: StateFlow<Long> = _sessionRevisionFlow.asStateFlow()
    val sessionRevision: Long get() = _sessionRevisionFlow.value

    private val _profileFlow = MutableStateFlow<JmAccountSnapshot?>(null)
    val profileFlow: StateFlow<JmAccountSnapshot?> = _profileFlow.asStateFlow()

    val isLoggedIn: Boolean
        get() = token() != null

    @Synchronized
    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasRestoredToken = token() != null
        accountName = if (hasRestoredToken) preferences?.getString(ACCOUNT_NAME, null) else null
        accountUid = if (hasRestoredToken) preferences?.getString(ACCOUNT_UID, null) else null
        _profileFlow.value = if (hasRestoredToken) JmAccountSnapshotPolicy.decode(preferences?.getString(PROFILE, null)) else null
        if (!hasRestoredToken) preferences?.edit()?.remove(REMEMBERED_CREDENTIALS)?.apply()
        _accountNameFlow.value = accountName
        _loggedInFlow.value = hasRestoredToken
        loginAttempts.invalidate()
        _sessionRevisionFlow.value++
    }

    @Synchronized
    fun token(): String? = runCatching {
        val stored = preferences?.getString(TOKEN, null) ?: return null
        val packed = Base64.decode(stored, Base64.NO_WRAP)
        val nonce = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, nonce))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
    }.getOrNull()

    @Synchronized
    fun save(token: String, username: String, uid: String? = null) {
        require(token.isNotBlank())
        val profile = JmAccountSnapshotPolicy.forAccount(_profileFlow.value, accountName, accountUid, username, uid)
        saveValues(token, username, uid, profile)
    }

    /** Begins a login request; later logins and session changes invalidate this attempt. */
    @Synchronized
    fun beginLoginAttempt(preserveSessionOnUnauthorized: Boolean = false): JmLoginAttempt =
        loginAttempts.begin(sessionRevision, preserveSessionOnUnauthorized)

    /** A refresh attempt may start only against the same session revision whose credentials it read. */
    @Synchronized
    fun beginLoginAttempt(preserveSessionOnUnauthorized: Boolean, expectedSessionRevision: Long): JmLoginAttempt? {
        val revision = sessionRevision
        if (!JmCredentialRefreshPolicy.isSameSession(expectedSessionRevision, revision)) return null
        return loginAttempts.begin(revision, preserveSessionOnUnauthorized)
    }

    /** Save a validated login response only while it still belongs to the active session and attempt. */
    @Synchronized
    fun saveLogin(
        attempt: JmLoginAttempt,
        token: String,
        username: String,
        uid: String?,
        profile: JmAccountSnapshot,
        credentialUsername: String,
        credentialPassword: String,
        commitCookies: () -> Unit = {},
    ): Boolean {
        return loginAttempts.runIfCurrent(attempt, sessionRevision) {
            require(token.isNotBlank())
            require(JmCredentialRefreshPolicy.shouldStorePassword(credentialUsername, credentialPassword))
            val credentials = encryptRememberedCredentials(username, uid, credentialUsername, credentialPassword)
            saveValues(token, username, uid, profile, credentials)
            commitCookies()
        }
    }

    /** Whether a tagged login request still belongs to the active attempt and account revision. */
    @Synchronized
    fun isLoginCurrent(attempt: JmLoginAttempt): Boolean = loginAttempts.isCurrent(attempt, sessionRevision)

    /** A login-request 401 may clear state only while that request's attempt is still current. */
    @Synchronized
    fun clearIfCurrent(attempt: JmLoginAttempt): Boolean {
        if (!loginAttempts.isCurrent(attempt, sessionRevision)) return false
        clear()
        return true
    }

    private fun saveValues(
        token: String,
        username: String,
        uid: String?,
        profile: JmAccountSnapshot?,
        credentialsCiphertext: String? = null,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val editor = checkNotNull(preferences) { "JM auth store has not been initialized" }
            .edit()
            .putString(TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP))
            .putString(ACCOUNT_NAME, username)
            .apply { if (uid == null) remove(ACCOUNT_UID) else putString(ACCOUNT_UID, uid) }
        if (profile == null) editor.remove(PROFILE) else editor.putString(PROFILE, JmAccountSnapshotPolicy.encode(profile))
        if (credentialsCiphertext == null) editor.remove(REMEMBERED_CREDENTIALS) else editor.putString(REMEMBERED_CREDENTIALS, credentialsCiphertext)
        editor.apply()
        accountName = username
        accountUid = uid
        _profileFlow.value = profile
        _accountNameFlow.value = username
        _loggedInFlow.value = true
        _sessionRevisionFlow.value++
    }

    @Synchronized
    fun uid(): String? = accountUid

    /** Returns saved login credentials only when bound to the currently authenticated account. */
    @Synchronized
    internal fun rememberedCredentialsForCurrentSession(): JmRememberedCredentials? {
        if (token() == null) return null
        val serialized = preferences?.getString(REMEMBERED_CREDENTIALS, null) ?: return null
        return runCatching {
            val payload = JSONObject(decryptString(serialized, CREDENTIALS_AAD))
            val savedAccount = payload.optString("accountName").takeIf(String::isNotBlank)
            val savedUid = payload.optString("accountUid").takeIf(String::isNotBlank)
            val loginUsername = payload.optString("username").takeIf(String::isNotBlank)
            val password = payload.optString("password").takeIf(String::isNotBlank)
            if (!JmCredentialRefreshPolicy.belongsToAccount(savedAccount, savedUid, accountName, accountUid) || loginUsername == null || password == null) {
                null
            } else {
                JmRememberedCredentials(loginUsername, password, accountName, accountUid, sessionRevision)
            }
        }.getOrElse {
            preferences?.edit()?.remove(REMEMBERED_CREDENTIALS)?.apply()
            null
        }
    }

    /** Keep the authenticated identity stable for one synchronous request. */
    @Synchronized
    fun <T> withAccount(uid: String, action: () -> T): T? {
        if (accountUid != uid || token() == null) return null
        return action()
    }

    @Synchronized
    fun clear() {
        preferences?.edit()?.remove(TOKEN)?.remove(ACCOUNT_NAME)?.remove(ACCOUNT_UID)?.remove(PROFILE)?.remove(REMEMBERED_CREDENTIALS)?.apply()
        accountName = null
        accountUid = null
        loginAttempts.invalidate()
        _profileFlow.value = null
        _accountNameFlow.value = null
        _loggedInFlow.value = false
        _sessionRevisionFlow.value++
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encryptRememberedCredentials(account: String, uid: String?, username: String, password: String): String {
        val payload = JSONObject()
            .put("accountName", account)
            .put("accountUid", uid ?: "")
            .put("username", username)
            .put("password", password)
            .toString()
        return encryptString(payload, CREDENTIALS_AAD)
    }

    private fun encryptString(value: String, aad: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decryptString(value: String, aad: ByteArray): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > 12) { "Invalid encrypted JM credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(packed.copyOfRange(12, packed.size)).toString(Charsets.UTF_8)
    }
}

/** Password-bearing object intentionally does not expose credentials in its toString(). */
internal class JmRememberedCredentials(
    val username: String,
    val password: String,
    val accountName: String?,
    val accountUid: String?,
    val sessionRevision: Long,
)
